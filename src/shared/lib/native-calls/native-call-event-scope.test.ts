import { describe, expect, it } from "vitest";
import { nativeCallEventAppliesTo } from "./native-call-event-scope";

const ROOM = "!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app";
const OTHER_ROOM = "!QqvSkKmzUfPtNbxWyE:matrix.pocketnet.app";
const OLD_CALL = "1789002434417pyhl9z3azvtR556V";
const NEW_CALL = "1789002456340bHi7Fz1DUPFPFOt4";
const PUSH_EVENT_ID = "$JZBoLpZm0kQeVEyGqPHqyFEjcYVQaSGZ";
const OTHER_PUSH_EVENT_ID = "$aLm1WkYb7cQdRfTgXhUjNpVsZeCyBoIm";

describe("nativeCallEventAppliesTo", () => {
  it("applies when the event names the call JS is holding", () => {
    expect(
      nativeCallEventAppliesTo({ callId: NEW_CALL, roomId: ROOM }, { callId: NEW_CALL, roomId: ROOM }),
    ).toBe(true);
  });

  it("does not apply when the event names a different Matrix call", () => {
    // The orphan release on 2026-09-10: a callEnded for the swiped-away call
    // arrived 4 ms before JS finished offering the NEXT one, and hung it up.
    expect(
      nativeCallEventAppliesTo({ callId: OLD_CALL, roomId: ROOM }, { callId: NEW_CALL, roomId: ROOM }),
    ).toBe(false);
  });

  it("does not apply to a push-keyed event from a different room", () => {
    // A push id can never be compared with a Matrix callId, so the room is the
    // only thing separating a stranded push connection's teardown from the call
    // on screen.
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: OTHER_ROOM },
        { callId: NEW_CALL, roomId: ROOM },
      ),
    ).toBe(false);
  });

  it("applies to a push-keyed event from the room JS is in", () => {
    // Cold start from push: the ringer's decline must still reach the call,
    // and its id will never match. Same room is all the correlation there is.
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: ROOM },
        { callId: NEW_CALL, roomId: ROOM },
      ),
    ).toBe(true);
  });

  it("applies when the event carries no room to correlate by", () => {
    // Rather than withhold: a missed teardown strands JS in a call Telecom has
    // already ended, which is the worse of the two failures.
    expect(
      nativeCallEventAppliesTo({ callId: PUSH_EVENT_ID }, { callId: NEW_CALL, roomId: ROOM }),
    ).toBe(true);
    expect(
      nativeCallEventAppliesTo({ callId: PUSH_EVENT_ID, roomId: ROOM }, { callId: NEW_CALL }),
    ).toBe(true);
  });

  it("applies when the event carries no id at all", () => {
    expect(nativeCallEventAppliesTo({ callId: "" }, { callId: NEW_CALL, roomId: ROOM })).toBe(true);
    expect(nativeCallEventAppliesTo({ callId: undefined }, { callId: NEW_CALL })).toBe(true);
  });

  it("applies when JS holds no call to contradict the event", () => {
    expect(nativeCallEventAppliesTo({ callId: OLD_CALL, roomId: ROOM }, { callId: undefined })).toBe(true);
    expect(nativeCallEventAppliesTo({ callId: OLD_CALL, roomId: ROOM }, { callId: "" })).toBe(true);
  });

  it("lets a same-room push-keyed event through when JS never learned its own native key", () => {
    // Two calls overlapping in ONE room, the finished one push-keyed, and JS
    // with no key for its own call either — which is what a push-created live
    // call looks like, since its key cannot be compared with the SDK's id.
    // Nothing in the payload separates them. Pinned so the remaining gap is a
    // decision on record rather than something a later reader has to
    // rediscover.
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: ROOM },
        { callId: NEW_CALL, roomId: ROOM },
      ),
    ).toBe(true);
  });

  it("withholds a same-room push-keyed event once JS knows its own native key", () => {
    // The same overlap, but JS adopted its call from a native marker and so
    // knows which connection is its own. A teardown from any other connection
    // is then provably not about it — this is the case that used to hang up
    // the newer call on a redial.
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: ROOM },
        { callId: NEW_CALL, roomId: ROOM, nativeKey: OTHER_PUSH_EVENT_ID },
      ),
    ).toBe(false);
  });

  it("applies a push-keyed event that names the very connection JS holds", () => {
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: ROOM },
        { callId: NEW_CALL, roomId: ROOM, nativeKey: PUSH_EVENT_ID },
      ),
    ).toBe(true);
  });

  it("trusts the native key over the room", () => {
    // Both name one connection; a room that disagrees is native telling us
    // something inconsistent, and ending a call that is already over is the
    // cheaper mistake.
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: OTHER_ROOM },
        { callId: NEW_CALL, roomId: ROOM, nativeKey: PUSH_EVENT_ID },
      ),
    ).toBe(true);
  });

  it("never lets a known native key contradict a comparable pair of Matrix ids", () => {
    // The tight rule stays first: an event that carries a real Matrix callId
    // is decided by that id, whatever key the connection was created under.
    expect(
      nativeCallEventAppliesTo(
        { callId: NEW_CALL, roomId: ROOM },
        { callId: NEW_CALL, roomId: ROOM, nativeKey: PUSH_EVENT_ID },
      ),
    ).toBe(true);
  });
});
