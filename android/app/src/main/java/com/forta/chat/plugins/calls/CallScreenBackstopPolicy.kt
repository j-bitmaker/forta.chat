package com.forta.chat.plugins.calls

/**
 * Whether native may close the call screen after the peer's hangup push ended
 * the call and JS behind the screen did not close it.
 *
 * Only JS closed [CallActivity]. Chromium freezes the page behind it a minute
 * into a call (see page-awake-tone.ts); a frozen page never processes the
 * hangup, so the push branch released the Telecom connection and the audio
 * while the finished call stayed on screen. Live JS closes the screen within
 * milliseconds of native's `callEnded`, so a screen still up after [GRACE_MS]
 * is one JS is not going to close.
 *
 * The screen may be a live call. Unlike the ringer ([RemoteHangupPolicy]),
 * nothing but the screen's own call id closes it: a push without call_id, or a
 * screen that never learnt its id, leaves it to JS.
 */
object CallScreenBackstopPolicy {

    const val GRACE_MS = 5_000L

    fun closes(shownCallId: String?, endedCallId: String?): Boolean {
        if (shownCallId.isNullOrEmpty() || endedCallId.isNullOrEmpty()) return false
        if (CallSlotPolicy.isEventId(shownCallId) || CallSlotPolicy.isEventId(endedCallId)) return false
        return shownCallId == endedCallId
    }
}
