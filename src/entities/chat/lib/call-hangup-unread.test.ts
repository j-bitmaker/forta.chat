import { describe, it, expect } from "vitest";
import { unreadPeerHangupCount, unreadCountWithoutHangups, countPeerHangupsAfter, hasCallEvent } from "./call-hangup-unread";

/**
 * With the account's `m.call.hangup` push rule, the server counts every hangup a
 * peer sends as an unread notification. On the test account a missed call raised
 * the chat-list badge by 2 instead of 1, an answered call by 2 instead of 1
 * (`hcount-rule`, 2026-09-15). The badge takes those hangups back out.
 */
const ME = "@me:s";
const PEER = "@peer:s";

function ev(id: string | undefined, type: string, sender: string, ts: number) {
  return { getId: () => id, getType: () => type, getSender: () => sender, getTs: () => ts };
}
const readIds = (ids: string[]) => (id: string) => ids.includes(id);

describe("unreadPeerHangupCount", () => {
  const call = [
    ev("$invite", "m.call.invite", PEER, 100),
    ev("$hangup", "m.call.hangup", PEER, 200),
    ev("$msg", "m.room.message", PEER, 300),
    ev("$hangup2", "m.call.hangup", PEER, 400),
  ];

  it("counts the unread hangups a peer sent since the rule was seen", () => {
    expect(unreadPeerHangupCount(call, { myUserId: ME, since: 0, hasRead: readIds([]) })).toBe(2);
  });

  it("leaves out my own hangups, which the server never counts for me", () => {
    const mine = [ev("$h", "m.call.hangup", ME, 200)];
    expect(unreadPeerHangupCount(mine, { myUserId: ME, since: 0, hasRead: readIds([]) })).toBe(0);
  });

  it("leaves out hangups already read", () => {
    expect(unreadPeerHangupCount(call, { myUserId: ME, since: 0, hasRead: readIds(["$hangup"]) })).toBe(1);
  });

  it("leaves out hangups sent before the rule was seen, which the server did not count", () => {
    expect(unreadPeerHangupCount(call, { myUserId: ME, since: 400, hasRead: readIds([]) })).toBe(1);
    expect(unreadPeerHangupCount(call, { myUserId: ME, since: 401, hasRead: readIds([]) })).toBe(0);
  });

  it("leaves out an event without an id", () => {
    const pending = [ev(undefined, "m.call.hangup", PEER, 200)];
    expect(unreadPeerHangupCount(pending, { myUserId: ME, since: 0, hasRead: readIds([]) })).toBe(0);
  });
});

describe("countPeerHangupsAfter", () => {
  // Raw events from /messages for the stretch the live timeline lost to a gap. The sync filter
  // returns at most 4 room events per response, so after three missed calls with JS dead the
  // live timeline held one hangup of five and the badge went 2 → 9 instead of 2 → 5 (hburst1).
  const raw = (event_id: unknown, sender: unknown, origin_server_ts: unknown, type: unknown = "m.call.hangup") =>
    ({ event_id, sender, origin_server_ts, type });

  it("counts the peer hangups sent after the receipt's event and since the rule was seen", () => {
    const events = [raw("$h3", PEER, 3000), raw("$h2", PEER, 2000), raw("$h1", PEER, 1000)];
    expect(countPeerHangupsAfter(events, { myUserId: ME, since: 0, after: 1500 })).toBe(2);
  });

  it("leaves out my own hangups and hangups at or before the receipt", () => {
    const events = [raw("$mine", ME, 3000), raw("$at", PEER, 1500), raw("$ok", PEER, 1600)];
    expect(countPeerHangupsAfter(events, { myUserId: ME, since: 0, after: 1500 })).toBe(1);
  });

  it("leaves out hangups sent before the rule was seen", () => {
    const events = [raw("$new", PEER, 3000), raw("$old", PEER, 2000)];
    expect(countPeerHangupsAfter(events, { myUserId: ME, since: 2500, after: -Infinity })).toBe(1);
  });

  it("leaves out other event types and malformed events", () => {
    const events = [
      raw("$invite", PEER, 3000, "m.call.invite"),
      raw(undefined, PEER, 3000),
      raw("$nots", PEER, "3000"),
      raw("$ok", PEER, 3000),
    ];
    expect(countPeerHangupsAfter(events, { myUserId: ME, since: 0, after: 0 })).toBe(1);
  });
});

describe("hasCallEvent", () => {
  // Only a room with a call in view is worth a /messages request for the gap: asking for every
  // unread room would send one request per chat after a long offline stretch.
  it("is true when any event is call signalling", () => {
    expect(hasCallEvent([ev("$m", "m.room.message", PEER, 1), ev("$c", "m.call.candidates", PEER, 2)])).toBe(true);
  });

  it("is false for a room with no call in view", () => {
    expect(hasCallEvent([ev("$m", "m.room.message", PEER, 1), ev("$r", "m.reaction", PEER, 2)])).toBe(false);
    expect(hasCallEvent([])).toBe(false);
  });
});

describe("unreadCountWithoutHangups", () => {
  it("takes the hangups out of the server count", () => {
    expect(unreadCountWithoutHangups(4, 2)).toBe(2);
  });

  it("never goes below zero", () => {
    expect(unreadCountWithoutHangups(1, 3)).toBe(0);
  });
});
