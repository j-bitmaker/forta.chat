import { describe, it, expect } from "vitest";
import { indexCallEvents, isMissedCallHangup } from "./call-outcome";

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
