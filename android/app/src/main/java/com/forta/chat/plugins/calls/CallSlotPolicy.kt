package com.forta.chat.plugins.calls

/**
 * Whether the single Telecom slot holds the call a caller is talking about.
 *
 * Every reader of [CallConnectionService.currentConnection] used to act on
 * whatever was there: JS reporting call B ended (a refused second invite, a
 * stale one) tore down call A, and reportCallConnected for B activated A.
 * The slot stays single — a second call is BUSY, not queued — but each
 * reader now checks the id first.
 *
 * Ids are not always comparable. A connection created from a push carries
 * the push's call_id, or its event_id when the homeserver sent none; Matrix
 * event ids start with `$` and never equal a Matrix call_id. Such a slot is
 * treated as unkeyed rather than unreachable: refusing to end it would leave
 * Telecom holding the device in a call audio mode until reboot, which is
 * worse than the bug this rule fixes.
 */
object CallSlotPolicy {

    private const val EVENT_ID_PREFIX = "$"

    fun owns(slotCallId: String, requestedCallId: String?): Boolean {
        if (requestedCallId.isNullOrEmpty()) return true
        if (slotCallId.isEmpty()) return true
        if (slotCallId.startsWith(EVENT_ID_PREFIX)) return true
        return slotCallId == requestedCallId
    }
}
