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
}
