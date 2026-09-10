import { describe, expect, it } from "vitest";
import { nativeCallEventAppliesTo } from "./native-call-event-scope";

const ROOM = "!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app";
const OTHER_ROOM = "!QqvSkKmzUfPtNbxWyE:matrix.pocketnet.app";
const OLD_CALL = "1789002434417pyhl9z3azvtR556V";
const NEW_CALL = "1789002456340bHi7Fz1DUPFPFOt4";
const PUSH_EVENT_ID = "$JZBoLpZm0kQeVEyGqPHqyFEjcYVQaSGZ";

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

  it("lets a same-room push-keyed event through — the documented residual", () => {
    // Two calls overlapping in ONE room, the finished one push-keyed: nothing
    // in the payload separates them. Pinned so the gap is a decision on record
    // rather than something a later reader has to rediscover.
    expect(
      nativeCallEventAppliesTo(
        { callId: PUSH_EVENT_ID, roomId: ROOM },
        { callId: NEW_CALL, roomId: ROOM },
      ),
    ).toBe(true);
  });
});
