package com.forta.chat.plugins.calls

import android.media.AudioManager

/**
 * Decides what a call ending has to clean up, given how the device looks at
 * that moment.
 *
 * Nine native places used to decide "the call is over" on their own, and none
 * of them was obliged to release everything: the audio mode, the foreground
 * service and the Telecom slot each had a different set of writers, and the
 * paths that skipped the JS finalize (a push-delivered hangup, a Telecom
 * disconnect from a headset, a process that died mid-call) left the device in
 * `MODE_IN_COMMUNICATION` until reboot — the largest cluster of open call
 * reports. [CallTeardown] runs this policy from every native end-of-call hook
 * so the decision lives in one place, and this object stays free of Android
 * services so the rules are unit-testable.
 *
 * Two rules do the real work:
 *
 *  - Nothing global is touched while a *different* call still owns the slot.
 *    Displacing a stale connection at the start of the next call ends the old
 *    one through the same hook, and tearing the router down then would silence
 *    the call that is just starting.
 *  - Global teardown runs only on evidence the audio session was abandoned:
 *    the router still active, or the persisted session marker still open with
 *    the device in VoIP mode. On the normal path JS has already stopped the
 *    router and closed the marker before the Telecom hook fires, so the policy
 *    is a no-op there and never stops the foreground service a step early.
 *
 * `MODE_RINGTONE` and `MODE_IN_CALL` are never reset: the system ringer and a
 * cellular call set them too, and the only honest way out of a stuck ringtone
 * mode is releasing our own Telecom connection (see [StaleCallPolicy]).
 */
object CallTeardownPolicy {

    enum class Reason {
        /** Our Telecom connection was rejected (button, shade, ring timeout). */
        REJECT,
        /** Our Telecom connection was disconnected (JS hangup, headset, displacement). */
        DISCONNECT,
        /** A push-delivered hangup/reject arrived and there was no connection to end. */
        REMOTE_HANGUP,
        /** The plugin loaded in a fresh process; a previous one may have died mid-call. */
        COLD_START,
    }

    enum class Action {
        /** `AudioRouter.forceStop` — restore `MODE_NORMAL`, clear routing, close the marker. */
        FORCE_STOP_ROUTER,
        /** `CallForegroundService.stop` — drop the ongoing-call notification and wake-lock. */
        STOP_FOREGROUND_SERVICE,
    }

    data class State(
        /** `AudioManager.mode`, or null when the getter threw (documented on some OEM ROMs). */
        val audioMode: Int?,
        /** The Telecom slot holds a connection for a call other than the one ending. */
        val otherCallLive: Boolean,
        val foregroundServiceRunning: Boolean,
        val routerActive: Boolean,
        /** Persisted "audio session open" marker written by the router on start. */
        val sessionMarkerOpen: Boolean,
    )

    fun decide(reason: Reason, state: State): List<Action> {
        if (state.otherCallLive) return emptyList()
        val abandoned = state.routerActive ||
            (state.sessionMarkerOpen && state.audioMode == AudioManager.MODE_IN_COMMUNICATION)
        if (!abandoned) return emptyList()
        val actions = mutableListOf(Action.FORCE_STOP_ROUTER)
        if (state.foregroundServiceRunning) actions.add(Action.STOP_FOREGROUND_SERVICE)
        return actions
    }
}
