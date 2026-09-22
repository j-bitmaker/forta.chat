import { describe, it, expect, vi } from "vitest";
import { createHangupGapCounter, type HangupGapDeps, type HangupGapQuery } from "../hangup-gap-counter";

/**
 * The server counts every hangup a peer sends as unread once the account has the hangup push
 * rule. The live timeline only shows the latest events (the sync filter returns at most 4 per
 * room), so after three missed calls with JS dead the badge kept four of five hangups: 2 → 9
 * instead of 2 → 5 (hburst1, 2026-09-15). The counter fetches the hangups in that gap.
 */
const ME = "@me:s";
const PEER = "@peer:s";
const hangup = (id: string, ts: number, sender = PEER) =>
  ({ type: "m.call.hangup", event_id: id, sender, origin_server_ts: ts });
const flush = () => new Promise((r) => setTimeout(r, 0));

function query(over: Partial<HangupGapQuery> = {}): HangupGapQuery {
  return { roomId: "!r:s", fromToken: "gap", receiptEventId: "$read", myUserId: ME, since: 0, ...over };
}

function deps(over: Partial<HangupGapDeps> = {}): HangupGapDeps & {
  fetchHangups: ReturnType<typeof vi.fn>;
  fetchEventTs: ReturnType<typeof vi.fn>;
  onResolved: ReturnType<typeof vi.fn>;
} {
  return {
    fetchHangups: vi.fn().mockResolvedValue({ chunk: [], end: null }),
    fetchEventTs: vi.fn().mockResolvedValue(1000),
    onResolved: vi.fn(),
    ...over,
  } as never;
}

describe("createHangupGapCounter", () => {
  it("returns null until the gap is counted, then the count, and asks for one recount", async () => {
    const d = deps({
      fetchHangups: vi.fn().mockResolvedValue({ chunk: [hangup("$h3", 3000), hangup("$h2", 2000), hangup("$h0", 900)], end: "t2" }),
    });
    const counter = createHangupGapCounter(d);
    expect(counter.get(query())).toBeNull();
    await flush();
    expect(counter.get(query())).toBe(2);
    expect(d.onResolved).toHaveBeenCalledTimes(1);
    // The page reached an event at or before the receipt: nothing older can be unread.
    expect(d.fetchHangups).toHaveBeenCalledTimes(1);
    expect(d.fetchHangups).toHaveBeenCalledWith("!r:s", "gap", expect.any(Number));
    expect(d.fetchEventTs).toHaveBeenCalledWith("!r:s", "$read");
  });

  it("pages back until it passes the receipt", async () => {
    const d = deps({
      fetchHangups: vi.fn()
        .mockResolvedValueOnce({ chunk: [hangup("$h5", 5000), hangup("$h4", 4000)], end: "t1" })
        .mockResolvedValueOnce({ chunk: [hangup("$h3", 3000), hangup("$mine", 2500, ME), hangup("$h1", 500)], end: "t2" }),
    });
    const counter = createHangupGapCounter(d);
    counter.get(query());
    await flush();
    expect(counter.get(query())).toBe(3);
    expect(d.fetchHangups.mock.calls.map((c: unknown[]) => c[1])).toEqual(["gap", "t1"]);
  });

  it("without a receipt, stops at the date the rule was first seen", async () => {
    const d = deps({
      fetchHangups: vi.fn().mockResolvedValue({ chunk: [hangup("$h3", 3000), hangup("$h2", 2000)], end: "t1" }),
    });
    const counter = createHangupGapCounter(d);
    counter.get(query({ receiptEventId: null, since: 2500 }));
    await flush();
    expect(counter.get(query({ receiptEventId: null, since: 2500 }))).toBe(1);
    expect(d.fetchEventTs).not.toHaveBeenCalled();
    expect(d.fetchHangups).toHaveBeenCalledTimes(1);
  });

  it("stops at the end of the room and at the page cap", async () => {
    const endOfRoom = deps({ fetchHangups: vi.fn().mockResolvedValue({ chunk: [hangup("$h", 5000)] }) });
    const a = createHangupGapCounter(endOfRoom);
    a.get(query());
    await flush();
    expect(a.get(query())).toBe(1);
    expect(endOfRoom.fetchHangups).toHaveBeenCalledTimes(1);

    let ts = 100_000;
    const endless = deps({
      fetchHangups: vi.fn().mockImplementation(async (_r: string, from: string) =>
        ({ chunk: [hangup(`$h${ts}`, (ts -= 10))], end: `${from}+` })),
    });
    const b = createHangupGapCounter(endless, { maxPages: 3 });
    b.get(query());
    await flush();
    expect(endless.fetchHangups).toHaveBeenCalledTimes(3);
    expect(b.get(query())).toBe(3);
  });

  it("fetches a gap once while it is being counted", async () => {
    const d = deps({ fetchHangups: vi.fn().mockResolvedValue({ chunk: [hangup("$h", 5000)], end: null }) });
    const counter = createHangupGapCounter(d);
    counter.get(query());
    counter.get(query());
    await flush();
    counter.get(query());
    expect(d.fetchHangups).toHaveBeenCalledTimes(1);
  });

  it("takes nothing out when the receipt's time cannot be read", async () => {
    const d = deps({ fetchEventTs: vi.fn().mockResolvedValue(null) });
    const counter = createHangupGapCounter(d);
    counter.get(query());
    await flush();
    expect(counter.get(query())).toBe(0);
    expect(d.fetchHangups).not.toHaveBeenCalled();
    expect(d.onResolved).not.toHaveBeenCalled();
  });

  it("keeps what it counted when a later page fails", async () => {
    const d = deps({
      fetchHangups: vi.fn()
        .mockResolvedValueOnce({ chunk: [hangup("$h5", 5000)], end: "t1" })
        .mockRejectedValueOnce(new Error("offline")),
    });
    const counter = createHangupGapCounter(d);
    counter.get(query());
    await flush();
    expect(counter.get(query())).toBe(1);
  });

  it("counts a new gap or a new receipt afresh", async () => {
    const d = deps({ fetchHangups: vi.fn().mockResolvedValue({ chunk: [hangup("$h", 5000)], end: null }) });
    const counter = createHangupGapCounter(d);
    counter.get(query());
    await flush();
    counter.get(query({ fromToken: "gap2" }));
    counter.get(query({ receiptEventId: "$read2" }));
    await flush();
    expect(d.fetchHangups).toHaveBeenCalledTimes(3);
  });

  it("counts a few gaps at a time and asks for a recount once a turned-away gap can start", async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    const d = deps({
      fetchHangups: vi.fn().mockImplementation(async () => { await gate; return { chunk: [], end: null }; }),
    });
    const counter = createHangupGapCounter(d, { maxConcurrent: 2 });
    counter.get(query({ roomId: "!a:s" }));
    counter.get(query({ roomId: "!b:s" }));
    expect(counter.get(query({ roomId: "!c:s" }))).toBeNull();
    await flush();
    expect(d.fetchHangups).toHaveBeenCalledTimes(2);

    release();
    await flush();
    // Both gaps held nothing; the recount is for the room turned away.
    expect(d.onResolved).toHaveBeenCalledTimes(1);
    counter.get(query({ roomId: "!c:s" }));
    await flush();
    expect(d.fetchHangups).toHaveBeenCalledTimes(3);
  });

  // forta-bugs#809, variant A: the select_answer rule is seen later than the hangup rule.
  it("counts the gap's select_answers since their own rule and pages back to the earlier of the two", async () => {
    const selectAnswer = (id: string, ts: number) =>
      ({ type: "m.call.select_answer", event_id: id, sender: PEER, origin_server_ts: ts });
    const d = deps({
      fetchEventTs: vi.fn().mockResolvedValue(100),
      fetchHangups: vi
        .fn()
        .mockResolvedValueOnce({ chunk: [hangup("$h3", 3000), selectAnswer("$s3", 2900), selectAnswer("$s2", 1500)], end: "t2" })
        .mockResolvedValueOnce({ chunk: [hangup("$h1", 1200), hangup("$h0", 400)], end: "t3" }),
    });
    const counter = createHangupGapCounter(d);
    const q = query({ since: 1000, selectAnswerSince: 2000 });
    counter.get(q);
    await flush();
    // $h3, $s3 and $h1; $s2 predates its rule, $h0 predates the hangup rule and ends the paging.
    expect(counter.get(q)).toBe(3);
    expect(d.fetchHangups).toHaveBeenCalledTimes(2);
  });

  it("keeps a count made without the select_answer rule apart from one made with it", async () => {
    const d = deps({
      fetchHangups: vi.fn().mockResolvedValue({
        chunk: [{ type: "m.call.select_answer", event_id: "$s", sender: PEER, origin_server_ts: 3000 }, hangup("$h0", 900)],
        end: null,
      }),
    });
    const counter = createHangupGapCounter(d);
    counter.get(query());
    await flush();
    expect(counter.get(query())).toBe(0);
    counter.get(query({ selectAnswerSince: 0 }));
    await flush();
    expect(counter.get(query({ selectAnswerSince: 0 }))).toBe(1);
  });
});
