package com.forta.chat.plugins.calls

/**
 * Which incoming-call surface a push-delivered hangup, reject or
 * select_answer may take down: the ring, the incoming screen, the Telecom
 * incoming notification or the push's own full-screen notification.
 *
 * No such push used to reach the device — the account had no push rule for
 * these events — so the push branch took down whatever was showing. With a
 * rule every one arrives, including a late hangup for a call that ended
 * before the next one started ringing, and a blanket teardown would silence
 * that next call.
 *
 * The push names its call by `call_id`. A push without one carries only the
 * hangup's own event id, which never equals a call id: such a push, like a
 * surface keyed by an event id, cannot be matched and keeps taking down
 * whatever shows.
 */
object RemoteHangupPolicy {

    /** Whether a push that ended [endedId] may take down a surface showing [shownCallId]. */
    fun endsSurface(shownCallId: String?, endedId: String?): Boolean {
        if (endedId.isNullOrEmpty() || CallSlotPolicy.isEventId(endedId)) return true
        return CallSlotPolicy.owns(shownCallId.orEmpty(), endedId)
    }
}
