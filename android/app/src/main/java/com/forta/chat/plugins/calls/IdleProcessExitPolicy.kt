package com.forta.chat.plugins.calls

/**
 * Whether a process whose call just ended may end itself — see [IdleProcessExit].
 *
 * A task with activities means the user can still return to the app, and a
 * Telecom connection is released on its own path, which checks again: both
 * stay for good. A ringer still counting down or a started service (a push
 * being handled, the call service winding down) passes soon, so the check
 * comes back a bounded number of times.
 */
object IdleProcessExitPolicy {
    /** Lets the teardown that scheduled the check run to its end first. */
    const val GRACE_MS = 3_000L
    const val RETRY_MS = 5_000L
    const val MAX_ATTEMPTS = 8

    /**
     * How long a handled call push holds the process. Telecom creates the
     * call's connection a few hundred milliseconds after the push handler
     * returns, and until then nothing else shows that a call is coming.
     */
    const val CALL_PUSH_HOLD_MS = 10_000L

    enum class Decision { EXIT, RETRY, STAY }

    data class Snapshot(
        val uiTaskRunning: Boolean,
        val hasConnection: Boolean,
        val ringerArmed: Boolean,
        val busyServices: Int,
        val recentCallPush: Boolean,
        /** A task swipe mid-call is sending m.call.hangup from native code ([CallHangupSignal]). */
        val hangupSending: Boolean = false,
    )

    fun decide(snapshot: Snapshot, attempt: Int): Decision = when {
        snapshot.uiTaskRunning || snapshot.hasConnection -> Decision.STAY
        snapshot.ringerArmed || snapshot.busyServices > 0 || snapshot.recentCallPush || snapshot.hangupSending ->
            if (attempt + 1 < MAX_ATTEMPTS) Decision.RETRY else Decision.STAY
        else -> Decision.EXIT
    }

    /** Whether a call push handled at [lastCallPushAtMs] still holds the process at [nowMs] (elapsed realtime). */
    fun isRecentCallPush(lastCallPushAtMs: Long?, nowMs: Long): Boolean {
        if (lastCallPushAtMs == null) return false
        return nowMs - lastCallPushAtMs in 0 until CALL_PUSH_HOLD_MS
    }
}
