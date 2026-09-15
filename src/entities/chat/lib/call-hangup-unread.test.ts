import { describe, it, expect } from "vitest";
import { unreadPeerHangupCount, unreadCountWithoutHangups } from "./call-hangup-unread";

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

describe("unreadCountWithoutHangups", () => {
  it("takes the hangups out of the server count", () => {
    expect(unreadCountWithoutHangups(4, 2)).toBe(2);
  });

  it("never goes below zero", () => {
    expect(unreadCountWithoutHangups(1, 3)).toBe(0);
  });
});
