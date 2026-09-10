package com.forta.chat.plugins.calls

import android.telecom.Connection

/**
 * Whether the Telecom slot holds the call an incoming-call surface may answer.
 *
 * [IncomingCallActivity] is launched by the FCM push handler independently of
 * Telecom, so it can be on screen for a call `onCreateIncomingConnection`
 * refused as BUSY — and the slot then holds the conversation the user is
 * having. Answering that connection does not answer the call on screen at all;
 * worse, `CallConnection.onAnswer` writes a fresh pendingAnswer marker for the
 * call in the slot and arms the JS answer wait for its room, which is how a
 * later invite from that room gets picked up with no ringer.
 *
 * The state is the load-bearing half, exactly as on the decline path. Id
 * comparison cannot carry this on its own: [CallSlotPolicy.owns] deliberately
 * treats a slot keyed by a push event id (`$…`) as unkeyed so that Telecom can
 * always be *ended*, and on this homeserver that is the common shape — an
 * id-only guard would pass for most real BUSY collisions. Refusing anything but
 * a ringing connection needs no ids to be comparable.
 *
 * The id check stays as the second half, for the narrower case of a stale
 * surface whose call was displaced by a newer ring.
 *
 * The state ints are compile-time constants, so this stays unit-testable
 * without an Android runtime.
 */
object IncomingAcceptPolicy {

    /**
     * @param slotState the slot connection's `Connection.getState()`
     * @param slotCallId the slot connection's callId
     * @param requestedCallId the call the surface is showing
     * @return true when answering the slot answers the call on screen
     */
    fun mayAnswerSlot(
        slotState: Int,
        slotCallId: String,
        requestedCallId: String?,
    ): Boolean {
        if (slotState != Connection.STATE_RINGING) return false
        return CallSlotPolicy.owns(slotCallId, requestedCallId)
    }
}
