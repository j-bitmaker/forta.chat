package com.forta.chat.plugins.calls

/**
 * "The user already answered / declined this call" — written by the native
 * ringer before the JS app is running, read once by the Capacitor bridge.
 *
 * The three parts travel together on purpose. They used to be three separate
 * `var`s on [CallConnection]'s companion, and every path that touched one
 * without the others could leave the marker naming two different calls: a
 * conditional `roomId` write kept the previous call's room next to the new
 * call's id, and a partial clear left one field behind. Once a write time
 * entered the picture that stopped being cosmetic — the JS matcher ages the
 * room-scoped branch out, so a half-updated pair could present a stale room
 * as freshly marked and swallow an unrelated call. One immutable value makes
 * those states unrepresentable.
 *
 * [atMs] is wall-clock at write time; the JS side refuses to match by room
 * alone once it is older than an invite lifetime. See
 * `src/shared/lib/native-calls/pending-call-marker.ts`.
 */
data class PendingCallMarker(
    val callId: String?,
    val roomId: String?,
    val atMs: Long,
) {
    val isEmpty: Boolean
        get() = callId.isNullOrEmpty() && roomId.isNullOrEmpty()

    /**
     * Drops the whole marker when it names this call by either key.
     *
     * Both keys identify the same call, so a match on one is a match on the
     * marker: clearing only the matching half is what used to leave a stale
     * id paired with a live room.
     */
    fun clearedFor(callId: String?, roomId: String?): PendingCallMarker {
        val byCall = !callId.isNullOrEmpty() && callId == this.callId
        val byRoom = !roomId.isNullOrEmpty() && roomId == this.roomId
        return if (byCall || byRoom) NONE else this
    }

    companion object {
        /** No marker. [atMs] is 0 so a reader can never read it as a fresh one. */
        val NONE = PendingCallMarker(null, null, 0L)

        /**
         * A marker for one call. Empty strings collapse to null so callers do
         * not have to normalise, and a marker with nothing to match on is
         * [NONE] rather than a timestamped blank.
         */
        fun of(callId: String?, roomId: String?, nowMs: Long): PendingCallMarker {
            val call = callId?.ifEmpty { null }
            val room = roomId?.ifEmpty { null }
            return if (call == null && room == null) NONE
            else PendingCallMarker(call, room, nowMs)
        }
    }
}
