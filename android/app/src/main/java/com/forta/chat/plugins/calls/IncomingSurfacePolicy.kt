package com.forta.chat.plugins.calls

import android.telecom.Connection

/**
 * Whether `ensureIncomingCallVisible` should treat the incoming-call surface as
 * already up, and so skip showing a ringer for the call JS is asking about.
 *
 * The check used to be `IncomingCallActivity.currentInstance != null ||
 * currentConnection != null`, with no reference to the call being asked about.
 * Any connection left in the single slot therefore silenced every later call on
 * the `/sync` route — permanently, before the task-removal fix, and for up to
 * one `armRingTimeout` afterwards. On the Samsung repro the phone skipped with
 * "ringer already up" while nothing was on screen and nothing was audible.
 *
 * Only one case changes: a connection that is RINGING for a *different* call
 * while nothing presents it — no incoming activity, no armed ringer. Nobody can
 * see or hear such a connection, so it is an orphan, and
 * `onCreateIncomingConnection` already knows how to displace it. Every other
 * case answers exactly as before:
 *
 *  - the incoming activity is up — one ringer at a time, whoever it belongs to;
 *  - the slot holds the very call being asked about — the idempotent re-ask
 *    this method exists to absorb;
 *  - the slot holds an established call — the second caller gets busy, which is
 *    a product decision (see [DisplacedConnectionPolicy]);
 *  - a ringer is armed for someone — including the notification-only ringer
 *    that a revoked full-screen intent leaves behind.
 *
 * Deliberately narrower than [DisplacedConnectionPolicy.mayRelease]: that also
 * admits DIALING, and an outgoing call the user just placed must not be
 * displaced by an incoming one arriving over `/sync`.
 */
object IncomingSurfacePolicy {

    /**
     * @param requestedCallId the call JS wants shown; null/empty means "whatever is current"
     * @param activityUp `IncomingCallActivity.currentInstance != null`
     * @param slotCallId the connection in the slot, or null when the slot is empty
     * @param slotState that connection's `Connection.getState()`, ignored when [slotCallId] is null
     * @param ringingCallId `IncomingRinger.ringingCallId` — null when nothing is ringing
     */
    fun isAlreadyVisibleFor(
        requestedCallId: String?,
        activityUp: Boolean,
        slotCallId: String?,
        slotState: Int?,
        ringingCallId: String?,
    ): Boolean {
        if (activityUp) return true
        if (slotCallId == null) return false
        if (CallSlotPolicy.owns(slotCallId, requestedCallId)) return true
        if (slotState != Connection.STATE_RINGING) return true
        if (ringingCallId != null) return true
        return false
    }
}
