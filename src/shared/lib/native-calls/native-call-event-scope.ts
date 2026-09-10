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
  /**
   * On `current` only: the key the native side knows this call by.
   *
   * Set only where JS can prove it — an exact id match against a native
   * marker, which means the connection behind this call carries the Matrix
   * callId. Never guessed: a room-correlated marker might belong to another
   * connection in the same room, and JS reporting a call to native under its
   * Matrix id does not make that the connection's key either, because a push
   * may already have created one keyed by the event_id. See `nativeKeyBinding`
   * in `native-call-bridge.ts` for why a guess here is worse than no key.
   */
  nativeKey?: string | null;
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
 * `current.nativeKey` closes half of the same-room case. Two calls in one room
 * overlapping, the finished one push-keyed, used to be indistinguishable: both
 * events name the room, and the id could not be compared with anything JS
 * held. It can be compared with the key JS was handed for its own call, which
 * names one connection exactly — so an event from any other one is provably
 * not about the call JS holds.
 *
 * Only half, because JS may only record that key from an exact id match, which
 * in turn means its own connection is keyed by the Matrix callId — the /sync
 * shape. When the live call came from a push its key cannot be compared with
 * anything either, so nothing can be recorded and the room fallback decides,
 * exactly as before. Correlating THAT case by room to learn the key was tried
 * and rejected: a room match cannot tell a marker written for this call from
 * one another connection left in the room, and a wrong key here is decisive
 * and permanent — it would withhold the call's own teardown for the rest of
 * its life. A missed teardown is the worse failure, and that is the side this
 * errs on; the surviving gap merely ends a call that was about to end anyway.
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
  // An exact native key beats the room: it names the connection, not the
  // conversation, so a second call in the same room no longer looks the same.
  if (current.nativeKey && event.callId) return event.callId === current.nativeKey;
  if (!event.roomId || !current.roomId) return true;
  return event.roomId === current.roomId;
}
