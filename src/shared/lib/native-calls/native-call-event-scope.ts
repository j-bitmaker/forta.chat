/**
 * Which call a native call-lifecycle event is talking about.
 *
 * `callEnded` and `callDeclined` name a call, but the JS handlers used to
 * ignore that and act on `callStore.matrixCall` — whatever call JS held at that
 * instant. That is fine while one call exists at a time and wrong the moment
 * two overlap, which is exactly what releasing a stranded connection creates:
 * Telecom will not offer a new incoming call while this app holds a RINGING
 * self-managed one, so the old connection has to be disconnected first, and its
 * `callEnded` then races the new call.
 *
 * Measured on the Samsung 2026-09-10:
 *
 *   04:07:40.404  Releasing an unpresented connection: …556V
 *   04:07:40.406  CallTeardown endCall reason=DISCONNECT callId=…556V
 *   04:07:40.407  reportIncomingCall: …4Ot4                    ← the new call
 *   04:07:40.410  [NativeCallBridge] Call ended natively: …556V
 *   04:07:40.410  Call …4Ot4 hangup() ending call              ← wrong call
 *
 * The new call was torn down 116 ms after it started ringing, by an event that
 * belonged to a call that had already finished.
 */
import { callIdNeedsRoomCorrelation } from "./pending-call-marker";

/** A call as one side of the bridge knows it. */
export interface NativeCallEventTarget {
  callId: string | null | undefined;
  roomId?: string | null;
}

/**
 * True when a native event may act on the call JS is currently holding.
 *
 * Only a positive contradiction withholds the event — ids that are both real
 * and different, or rooms that are both known and different. Everything else
 * keeps the previous behaviour, and deliberately so. A missed teardown is the
 * worse failure here: it leaves JS believing a call is live after Telecom has
 * ended it, which is the "stuck in call mode" state that dominates the bug
 * reports. A spurious one merely ends a call that was about to end anyway.
 *
 * The room is the fallback because the id often cannot be compared at all: a
 * connection created from a push is keyed by the push's `call_id`, which this
 * homeserver fills with the event_id, so it never equals the SDK's
 * `call.callId` (see `callIdNeedsRoomCorrelation`). Requiring id equality there
 * would silently stop the native ringer from ending calls on the primary ringer
 * surface — the trap that broke three earlier designs of the pending-marker
 * match.
 *
 * Known residual, and it needs the room to close: two calls in the SAME room
 * overlapping, where the finished one is push-keyed. Its event is then
 * indistinguishable from one about the live call — no correlator the payload
 * carries can separate them — so it is allowed through, and JS ends the newer
 * call. A redial into a room whose previous push-created connection is still
 * being torn down is the way to reach it.
 */
export function nativeCallEventAppliesTo(
  event: NativeCallEventTarget,
  current: NativeCallEventTarget,
): boolean {
  const comparableIds =
    !callIdNeedsRoomCorrelation(event.callId) &&
    !callIdNeedsRoomCorrelation(current.callId);
  if (comparableIds) return event.callId === current.callId;
  // Nothing JS holds can be contradicted.
  if (!current.callId) return true;
  if (!event.roomId || !current.roomId) return true;
  return event.roomId === current.roomId;
}
