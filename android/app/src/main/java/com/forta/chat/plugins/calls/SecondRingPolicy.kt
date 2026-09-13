package com.forta.chat.plugins.calls

/**
 * Whether a call push may take the incoming-call screen over while another
 * call still rings on it.
 *
 * Found on the Samsung 2026-09-10 (`rebind2`): with the app open, caller B from
 * another room arrived while A still rang. JS had already rejected B —
 * `hasLiveCall` counts a ringing call — yet the push repainted the ringer to B
 * 250 ms later. Telecom refused B while A held RINGING, so «Принять» could
 * connect nobody, and A's own 30 s timer then closed the screen showing B. The
 * ringing call now keeps its screen; the second one is left to JS, which turns
 * it away as before.
 *
 * Two cases keep the repaint:
 *  - the same room: a caller who hung up and dialled again while the first ring
 *    is still on screen. No hangup push reaches this app, so with JS dead the
 *    old ringer would otherwise hide the new call until its own deadline;
 *  - a ringing call whose room is unknown, or whose id cannot be compared
 *    ([CallSlotPolicy]): with nothing to go on, nothing changes.
 */
object SecondRingPolicy {

    /**
     * @param newCallId the pushed invite's call id
     * @param newRoomId the pushed invite's room
     * @param ringingCallId `IncomingRinger.ringingCallId` — null when nothing rings
     * @param ringingRoomId the room of the ringing call, null when unknown
     */
    fun mayTakeOverRinger(
        newCallId: String,
        newRoomId: String,
        ringingCallId: String?,
        ringingRoomId: String?,
    ): Boolean {
        if (ringingCallId == null) return true
        if (CallSlotPolicy.owns(ringingCallId, newCallId)) return true
        if (ringingRoomId.isNullOrEmpty()) return true
        return ringingRoomId == newRoomId
    }
}
