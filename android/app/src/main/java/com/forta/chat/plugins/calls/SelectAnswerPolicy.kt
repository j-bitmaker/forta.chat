package com.forta.chat.plugins.calls

/**
 * Whether a push-delivered `m.call.select_answer` names a call this device
 * answered itself, and so must leave it alone.
 *
 * The caller sends `select_answer` after every answer, to every device of the
 * callee. With the account's push rule for it
 * (`call-select-answer-push-rule.ts`) the device that took the call gets the
 * push too, about a second into the conversation; treated like a hangup it
 * disconnected every call the phone answered (`saprobe3`, 2026-09-18).
 *
 * The push gateway does not pass `selected_party_id`, so the device that
 * answered cannot be read from the push. The Telecom slot knows instead:
 * every answer on this device goes through `CallConnection.onAnswer`, which
 * makes the connection ACTIVE before JS sends `m.call.answer`, so a slot
 * still RINGING when the push lands means another device answered. Two
 * devices answering at once is left to JS: a page that has just answered is
 * running and hears the caller's choice from sync.
 *
 * A push that names no call cannot be matched to the slot; a conversation in
 * progress is never ended on such a guess.
 */
object SelectAnswerPolicy {

    /**
     * @param slotCallId the callId of the connection in the Telecom slot, null when it is empty
     * @param slotState that connection's `Connection.getState()`, null when the slot is empty
     * @param selectedCallId the push's `call_id`, or its `event_id` when the homeserver sent none
     */
    fun answeredHere(slotCallId: String?, slotState: Int?, selectedCallId: String?): Boolean {
        if (slotState == null || DisplacedConnectionPolicy.mayRelease(slotState)) return false
        if (selectedCallId.isNullOrEmpty() || CallSlotPolicy.isEventId(selectedCallId)) return true
        return CallSlotPolicy.owns(slotCallId.orEmpty(), selectedCallId)
    }
}
