package com.forta.chat.plugins.calls

/** The extras `IncomingCallActivity` acts on; null means "the intent did not carry it". */
data class IncomingCallExtras(
    val callId: String,
    val callerName: String?,
    val roomId: String?,
    val hasVideo: Boolean?,
    val action: String?,
)

/**
 * What a resident `IncomingCallActivity` should act on after `onNewIntent`.
 *
 * The activity used to `setIntent(new)` unconditionally. The Telecom
 * notification's Accept/Decline intents carried no roomId, so an accept from
 * the shade replaced an intent that had the room with one that did not:
 * the push notification for that room was never dismissed, no pending
 * answer room reached JS, and the JS side rejected the call 30 seconds
 * later. Missing fields now fall back to the intent already held — but only
 * for the *same* call; a second caller's intent is taken as it is.
 */
object IncomingIntentMerge {
    private const val UNKNOWN_CALLER = "Unknown"

    fun merge(current: IncomingCallExtras, incoming: IncomingCallExtras): IncomingCallExtras {
        val sameCall = incoming.callId.isEmpty() || current.callId.isEmpty() || incoming.callId == current.callId
        if (!sameCall) return incoming
        return IncomingCallExtras(
            callId = incoming.callId.ifEmpty { current.callId },
            callerName = incoming.callerName.takeIf { !it.isNullOrBlank() && it != UNKNOWN_CALLER }
                ?: current.callerName ?: incoming.callerName,
            roomId = incoming.roomId.takeIf { !it.isNullOrBlank() } ?: current.roomId,
            hasVideo = incoming.hasVideo ?: current.hasVideo,
            action = incoming.action,
        )
    }
}
