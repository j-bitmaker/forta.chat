package com.forta.chat

/**
 * What to do when the WebView's render process dies.
 *
 * Capacitor's [com.getcapacitor.BridgeWebViewClient.onRenderProcessGone] returns
 * whatever its `WebViewListener`s return, and with no listener registered that is
 * `false` — which tells Android to kill the whole app process.
 *
 * That default is wrong for us in one specific, reproducible case. Swiping the
 * app out of Recents while a call is ringing destroys the activity and its
 * WebView but NOT the process: Telecom keeps the process alive for the
 * un-disconnected self-managed Connection (`CallConnectionService.currentConnection`,
 * which `releaseOnTaskRemoved` deliberately leaves alone while it is RINGING).
 * The orphaned WebView's renderer is then reaped by the OS, and the notification
 * lands while a *new* MainActivity is already starting up in that same process.
 * Returning `false` there kills a process that is serving a healthy new activity,
 * so the user's first tap on the app icon appears to do nothing and only the
 * second one opens the app. Measured on a Samsung SM-A528B: three runs out of
 * three, and absent from the answered-call path where `onTaskRemoved` fires and
 * the process exits normally.
 */
enum class RenderProcessRecovery {
    /** The dead WebView is the one this live activity is showing — rebuild it. */
    RECREATE_ACTIVITY,

    /**
     * A WebView left behind by an earlier activity in this process. Drop it and
     * keep the process: whatever is running now does not depend on it.
     */
    DISCARD_STALE,

    /**
     * Recovery is looping. Hand the process back to Android — that is the
     * pre-fix behaviour, and it beats recreating an activity forever.
     */
    LET_SYSTEM_KILL,
}

object WebViewRecoveryPolicy {

    /**
     * Two renderer deaths closer together than this are treated as a loop. One
     * rebuild is a recovery; a second one seconds later means the page itself is
     * killing the renderer, and retrying would just burn battery behind a screen
     * the user cannot use anyway.
     */
    const val RECOVERY_COOLDOWN_MS = 30_000L

    /**
     * @param isCurrentWebView the dead WebView is the one this activity's bridge holds.
     * @param activityAlive the activity is neither finishing nor destroyed.
     * @param msSinceLastRecovery elapsed monotonic time since this process last
     *   recreated an activity for this reason, or `null` if it never has.
     */
    fun decide(
        isCurrentWebView: Boolean,
        activityAlive: Boolean,
        msSinceLastRecovery: Long?,
    ): RenderProcessRecovery {
        // Either half being false means rebuilding this activity would be
        // pointless or illegal: a destroyed activity cannot recreate itself, and
        // a WebView we no longer show is not what the user is looking at.
        if (!isCurrentWebView || !activityAlive) return RenderProcessRecovery.DISCARD_STALE
        if (msSinceLastRecovery == null) return RenderProcessRecovery.RECREATE_ACTIVITY
        // A negative reading means the clock moved under us; fail closed rather
        // than treat it as "long ago" and start a rebuild loop.
        if (msSinceLastRecovery < 0L) return RenderProcessRecovery.LET_SYSTEM_KILL
        return if (msSinceLastRecovery < RECOVERY_COOLDOWN_MS) {
            RenderProcessRecovery.LET_SYSTEM_KILL
        } else {
            RenderProcessRecovery.RECREATE_ACTIVITY
        }
    }
}
