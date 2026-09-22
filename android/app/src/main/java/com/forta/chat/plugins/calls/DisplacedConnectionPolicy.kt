package com.forta.chat.plugins.calls

import android.telecom.Connection

/**
 * Whether a Telecom connection may be torn down by the incoming-call
 * machinery — i.e. whether it is anything other than a conversation in
 * progress.
 *
 * [CallConnectionService.currentConnection] holds one connection at a time and
 * every consumer reads it by slot, with no `callId` check. Nothing in either
 * `addNewIncomingCall` caller checks whether a call is already in progress, so
 * a second caller ringing mid-conversation reaches the incoming path and the
 * ringer it puts on screen ends up pointed at that single slot. Three places
 * would otherwise hang up the live call:
 *
 *  - `onCreateIncomingConnection`, by displacing it — it now returns BUSY for
 *    the second call instead, leaving the live one in the slot and endable.
 *  - `IncomingCallActivity`'s Decline button, and
 *  - its 30-second auto-reject, both of which reject whatever is in the slot.
 *
 * The outgoing path deliberately does **not** consult this: JS refuses to dial
 * while a call is live, so a connection still reading as established at dial
 * time is a previous call whose teardown has not landed — sparing it would
 * strand it and hold the device in a call audio mode.
 *
 * The state ints are compile-time constants, so this stays unit-testable
 * without an Android runtime.
 */
object DisplacedConnectionPolicy {

    /**
     * @param state the connection's `Connection.getState()`
     * @return true when no conversation is riding on it, so releasing is safe
     */
    fun mayRelease(state: Int): Boolean =
        state != Connection.STATE_ACTIVE && state != Connection.STATE_HOLDING

    /**
     * Whether a connection nothing presents may be released to clear the slot
     * for a new incoming call.
     *
     * Stricter than [mayRelease] on purpose. That one answers "is anyone
     * talking on it", which is the right question when Telecom hands us a
     * second call and one of the two has to yield. This one is asked about a
     * connection we believe is stranded — nothing on screen, nothing audible —
     * and the belief was formed a moment earlier, on another thread. Between
     * the two, Telecom can answer the call on its own from a Bluetooth headset,
     * Android Auto or the system call UI, none of which touch our activity: the
     * connection goes RINGING -> ACTIVE and `onAnswer` does not latch
     * `released`, so nothing downstream would stop the disconnect from cutting
     * off a call the user had just taken. It also catches a connection torn
     * down meanwhile by one of the off-looper paths — those set the disconnected
     * state before vacating the slot, so a stale-looking slot reads as
     * DISCONNECTED here rather than RINGING.
     *
     * So: only a connection still ringing may be released this way. Anything
     * else either became a real call or is already being torn down by the path
     * that owns it.
     *
     * @param state the connection's `Connection.getState()`
     * @return true when the connection is still merely ringing
     */
    fun mayReleaseUnpresented(state: Int): Boolean = state == Connection.STATE_RINGING

    /**
     * Whether a new incoming registration names the call already ringing in the
     * slot, which then has to stay rather than be displaced.
     *
     * One call can reach Telecom twice. While the app is alive, the FCM service
     * rings a call push natively and forwards it to JS, which reports the same
     * call again; both registrations carry the push's `call_id`. Displacing the
     * first tears it down through `onDisconnect`, and the `callEnded` that sends
     * names the call still ringing — JS cannot tell it from a real end and hangs
     * up whatever call it holds by then.
     *
     * Exact id only. A push keyed by the event_id and a JS report keyed by the
     * Matrix callId of one call stay two registrations: matching them by room
     * would also keep a stale ring in place of a caller's quick redial from the
     * same room.
     *
     * @param slotCallId the callId of the connection in the slot
     * @param slotState that connection's `Connection.getState()`
     * @param incomingCallId the callId of the registration being created
     * @return true when the slot already rings for exactly this call
     */
    fun isSameRingingCall(slotCallId: String?, slotState: Int, incomingCallId: String?): Boolean =
        !slotCallId.isNullOrEmpty() &&
            slotCallId == incomingCallId &&
            slotState == Connection.STATE_RINGING
}
