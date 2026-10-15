import { describe, it, expect } from "vitest";
import {
  indexCallEvents,
  isMissedCallHangup,
  isOrphanedInvite,
  ORPHAN_INVITE_GRACE_MS,
  orphanInviteDueAt,
} from "./call-outcome";

const CALLER = "@caller:server";
const CALLEE = "@callee:server";

const invite = (callId: string, sender = CALLER) => ({
  type: "m.call.invite",
  sender,
  content: { call_id: callId, version: 1, lifetime: 60000 },
});
const answer = (callId: string, sender = CALLEE) => ({
  type: "m.call.answer",
  sender,
  content: { call_id: callId, version: 1 },
});
const selectAnswer = (callId: string, sender = CALLER) => ({
  type: "m.call.select_answer",
  sender,
  content: { call_id: callId, version: 1, selected_party_id: "p1" },
});
const hangup = (callId: string, sender: string, reason?: string) => ({
  type: "m.call.hangup",
  sender,
  content: { call_id: callId, version: 1, ...(reason ? { reason } : {}) },
});

describe("isMissedCallHangup", () => {
  it("reads a timed-out invite as missed, with or without the rest of the call in view", () => {
    expect(isMissedCallHangup(hangup("c1", CALLER, "invite_timeout"), indexCallEvents([]))).toBe(true);
    expect(isMissedCallHangup(hangup("c1", CALLER, "invite_timeout"), indexCallEvents([invite("c1")]))).toBe(true);
  });

  it("reads a call the caller ended while it still rang as missed", () => {
    const h = hangup("c1", CALLER, "user_hangup");
    expect(isMissedCallHangup(h, indexCallEvents([invite("c1"), h]))).toBe(true);
  });

  it("reads it as missed when the caller's SDK leaves the reason out for an old peer", () => {
    const h = hangup("c1", CALLER);
    expect(isMissedCallHangup(h, indexCallEvents([invite("c1"), h]))).toBe(true);
  });

  it("does not read a call that was answered as missed, whoever ends it", () => {
    const events = [invite("c1"), answer("c1")];
    expect(isMissedCallHangup(hangup("c1", CALLER, "user_hangup"), indexCallEvents(events))).toBe(false);
    expect(isMissedCallHangup(hangup("c1", CALLEE, "user_hangup"), indexCallEvents(events))).toBe(false);
  });

  it("counts the caller's select_answer as an answer when the answer itself is out of view", () => {
    const events = [invite("c1"), selectAnswer("c1")];
    expect(isMissedCallHangup(hangup("c1", CALLER, "user_hangup"), indexCallEvents(events))).toBe(false);
  });

  it("does not read the callee's own hangup before an answer as missed — that is a decline", () => {
    const h = hangup("c1", CALLEE, "user_hangup");
    expect(isMissedCallHangup(h, indexCallEvents([invite("c1"), h]))).toBe(false);
  });

  it("keeps the old reading when the invite is out of view", () => {
    const h = hangup("c1", CALLER, "user_hangup");
    expect(isMissedCallHangup(h, indexCallEvents([h]))).toBe(false);
  });

  it("does not let another call's answer or invite decide this one", () => {
    const h = hangup("c2", CALLER, "user_hangup");
    expect(isMissedCallHangup(h, indexCallEvents([invite("c1"), answer("c1"), invite("c2"), h]))).toBe(true);
    expect(isMissedCallHangup(h, indexCallEvents([invite("c1"), h]))).toBe(false);
  });

  it("ignores a hangup without a call id unless it timed out", () => {
    const h = { type: "m.call.hangup", sender: CALLER, content: { reason: "user_hangup" } };
    expect(isMissedCallHangup(h, indexCallEvents([invite("c1"), h]))).toBe(false);
  });

  it("skips entries that are not events", () => {
    const h = hangup("c1", CALLER, "user_hangup");
    expect(isMissedCallHangup(h, indexCallEvents([null, undefined, { type: "m.call.invite" }, invite("c1")]))).toBe(true);
  });
});

describe("isOrphanedInvite", () => {
  // The caller's tab closed, or the app was killed, before its SDK sent `invite_timeout`:
  // no hangup ever came, so the call left no record in the chat.
  const sent = 1_000_000;
  const at = (ms: number, callId = "c1", sender = CALLER, lifetime: number | undefined = 60_000) => ({
    type: "m.call.invite",
    sender,
    event_id: `$invite-${callId}`,
    origin_server_ts: ms,
    content: { call_id: callId, version: 1, ...(lifetime === undefined ? {} : { lifetime }) },
  });
  const due = sent + 60_000 + ORPHAN_INVITE_GRACE_MS;

  it("reads an invite nothing ended as orphaned once its lifetime and the grace are over", () => {
    const i = at(sent);
    expect(orphanInviteDueAt(i)).toBe(due);
    expect(isOrphanedInvite(i, indexCallEvents([i]), due)).toBe(true);
    expect(isOrphanedInvite(i, indexCallEvents([i]), due - 1)).toBe(false);
  });

  it("is not orphaned while the call may still end on its own", () => {
    const i = at(sent);
    expect(isOrphanedInvite(i, indexCallEvents([i]), sent + 30_000)).toBe(false);
  });

  it("is not orphaned once a hangup or an answer of the call is in view", () => {
    const i = at(sent);
    expect(isOrphanedInvite(i, indexCallEvents([i, hangup("c1", CALLER, "invite_timeout")]), due)).toBe(false);
    expect(isOrphanedInvite(i, indexCallEvents([i, answer("c1")]), due)).toBe(false);
    expect(isOrphanedInvite(i, indexCallEvents([i, selectAnswer("c1")]), due)).toBe(false);
  });

  // Web bench 2026-10-15: nobody answered, the callee's app rejected the call after 30 s, and
  // the caller's SDK ended it on the reject without a hangup, so neither side got a record.
  it("reads a rejected call without a hangup as orphaned at once: it is over and left no record", () => {
    const i = at(sent);
    const reject = { type: "m.call.reject", sender: CALLEE, content: { call_id: "c1", version: 1 } };
    expect(isOrphanedInvite(i, indexCallEvents([i, reject]), sent + 32_000)).toBe(true);
    expect(isOrphanedInvite(i, indexCallEvents([i, reject, hangup("c1", CALLER)]), sent + 32_000)).toBe(false);
  });

  it("does not let another call's hangup end this one", () => {
    const i = at(sent);
    expect(isOrphanedInvite(i, indexCallEvents([i, hangup("c2", CALLER)]), due)).toBe(true);
  });

  it("uses the default lifetime when the invite names none, and caps a huge one", () => {
    expect(orphanInviteDueAt(at(sent, "c1", CALLER, undefined))).toBe(due);
    expect(orphanInviteDueAt(at(sent, "c1", CALLER, 3_600_000))).toBe(sent + 5 * 60_000 + ORPHAN_INVITE_GRACE_MS);
  });

  it("ignores events that are not invites, invites without a call id or a timestamp", () => {
    const i = at(sent);
    expect(isOrphanedInvite(hangup("c1", CALLER), indexCallEvents([i]), due)).toBe(false);
    expect(isOrphanedInvite({ ...i, content: { version: 1 } }, indexCallEvents([]), due)).toBe(false);
    expect(isOrphanedInvite({ ...i, origin_server_ts: undefined }, indexCallEvents([]), due)).toBe(false);
  });
});
