import { describe, it, expect } from "vitest";
import { collapsedCallEventIds, dedupeCallEvents } from "./dedupe-call-events";

type Row = {
  id: string;
  _key?: string;
  callInfo?: { callType: "voice" | "video"; missed: boolean; callId?: string };
};

const call = (id: string, callId?: string, missed = false): Row => ({
  id,
  callInfo: { callType: "voice", missed, callId },
});
const text = (id: string): Row => ({ id });

describe("dedupeCallEvents", () => {
  it("keeps one record when both sides hang up the same call", () => {
    // The real shape of #1027: two hangup events, different event ids, one call.
    const rows = [call("ev1", "call-a"), call("ev2", "call-a")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["ev1"]);
  });

  it("keeps the earliest record so the timeline does not shift", () => {
    const rows = [call("first", "call-a"), call("second", "call-a")];

    expect(dedupeCallEvents(rows)[0].id).toBe("first");
  });

  it("keeps separate calls apart", () => {
    const rows = [call("ev1", "call-a"), call("ev2", "call-b"), call("ev3", "call-a")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["ev1", "ev2"]);
  });

  it("leaves non-call messages untouched and in place", () => {
    const rows = [text("m1"), call("ev1", "call-a"), text("m2"), call("ev2", "call-a")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["m1", "ev1", "m2"]);
  });

  it("keeps every record written before call ids were stored", () => {
    // Old history has no id to group on; dropping such rows on a guess would
    // silently rewrite what the user already saw.
    const rows = [call("old1"), call("old2")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["old1", "old2"]);
  });

  it("returns an empty list unchanged", () => {
    expect(dedupeCallEvents([])).toEqual([]);
  });
});

describe("collapsedCallEventIds", () => {
  it("points a watermark on the dropped hangup at the record that survived", () => {
    // The bug this exists for: "last read" lands on the second hangup because
    // it is the newest event in the room, then dedupeCallEvents removes it and
    // the unread banner searches the timeline for an id that is no longer there.
    const rows = [call("ev1", "call-a"), call("ev2", "call-a")];

    expect(collapsedCallEventIds(rows).get("ev2")).toBe("ev1");
  });

  it("maps the dropped record's stable key as well as its id", () => {
    const rows: Row[] = [
      { ...call("ev1", "call-a"), _key: "key1" },
      { ...call("ev2", "call-a"), _key: "key2" },
    ];

    const collapsed = collapsedCallEventIds(rows);
    expect(collapsed.get("ev2")).toBe("key1");
    expect(collapsed.get("key2")).toBe("key1");
  });

  it("maps every dropped record when a call leaves more than two", () => {
    const rows = [call("ev1", "call-a"), call("ev2", "call-a"), call("ev3", "call-a")];

    const collapsed = collapsedCallEventIds(rows);
    expect(collapsed.get("ev2")).toBe("ev1");
    expect(collapsed.get("ev3")).toBe("ev1");
  });

  it("maps nothing when no record is dropped", () => {
    const rows = [text("m1"), call("ev1", "call-a"), call("ev2", "call-b"), call("old")];

    expect(collapsedCallEventIds(rows).size).toBe(0);
  });

  it("never maps a surviving id, so a live watermark is left alone", () => {
    const rows = [call("ev1", "call-a"), call("ev2", "call-a"), text("m1")];

    const collapsed = collapsedCallEventIds(rows);
    expect(collapsed.has("ev1")).toBe(false);
    expect(collapsed.has("m1")).toBe(false);
  });
});
