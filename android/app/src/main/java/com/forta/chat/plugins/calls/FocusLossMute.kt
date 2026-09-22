package com.forta.chat.plugins.calls

/**
 * The one microphone mute the call service's audio-focus listener owns, so it
 * undoes that mute and nothing else.
 *
 * The listener used to mute on every AUDIOFOCUS_LOSS_TRANSIENT and unmute on
 * every AUDIOFOCUS_GAIN. An outgoing call broke the first half: JS calls
 * `launchCallUI` before `reportOutgoingCall`, so the service is granted GAIN
 * and only then does `placeCall` make Telecom take focus for the call being
 * dialled. That loss is the call itself, yet it muted the mic for the whole
 * conversation — focus came back only when Telecom let go at hangup — while
 * the mute button still read "on". The second half re-opened a mic the user
 * had muted whenever focus returned.
 *
 * "Our Telecom call is up" is the right scope for leaving the mic alone:
 * Telecom locks focus for a call (the service's own re-requests come back
 * DELAYED until hangup), so another app cannot take focus from it and the only
 * loss the listener sees is Telecom's own. Where a connection sits in the slot
 * without Telecom behind it — the `placeCall` fallback, a leftover — a real
 * interruption goes unmuted, which errs toward an open mic, not a silent call.
 *
 * Telecom's loss usually arrives before its outgoing Connection exists, so the
 * listener still mutes; `onCreateOutgoingConnection` then [release]s it.
 * Main thread only.
 */
class FocusLossMute {

    private var muted = false

    /** A transient focus loss arrived. True when the mic should be muted now. */
    fun onTransientLoss(telecomOwnsCall: Boolean): Boolean {
        if (telecomOwnsCall) return false
        muted = true
        return true
    }

    /**
     * Focus came back, or Telecom took the call whose focus the loss was. True
     * when the mic should be unmuted now — only if a loss muted it.
     */
    fun release(): Boolean {
        if (!muted) return false
        muted = false
        return true
    }

    /** The call is over; its mute must not be undone inside the next one. */
    fun clear() {
        muted = false
    }
}
