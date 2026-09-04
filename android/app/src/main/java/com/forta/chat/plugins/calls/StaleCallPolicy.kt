package com.forta.chat.plugins.calls

/**
 * Decides whether a self-managed Telecom connection has been ringing so long
 * that nothing can still be waiting on it.
 *
 * Telecom keeps the device in `MODE_RINGTONE` for as long as a self-managed
 * connection is RINGING, and that mode breaks the phone's media volume until
 * it is released — the symptom behind the largest cluster of open call
 * reports. [CallConnection.armRingTimeout] is the primary backstop, but it is
 * a main-looper Handler: Doze, a wedged main thread, or a frozen-then-thawed
 * process can all push it past its deadline. This policy is the second net,
 * consulted when the app returns to the foreground.
 *
 * Deliberately conservative. Releasing a connection that is legitimately
 * ringing would decline a call the user is about to answer — worse than the
 * bug being fixed — so the only connection that qualifies is one that has
 * already outlived the ring timeout, past which no legitimate ringing
 * connection can exist. Kept free of Android imports so the rule itself is
 * unit-testable.
 */
object StaleCallPolicy {

    /**
     * @param isRinging       connection state is `STATE_RINGING`
     * @param ringingSinceMs  `SystemClock.elapsedRealtime()` when it started
     * @param nowMs           `SystemClock.elapsedRealtime()` now
     * @param timeoutMs       ring deadline, normally [CallConnection.RING_TIMEOUT_MS]
     */
    fun isStaleRinging(
        isRinging: Boolean,
        ringingSinceMs: Long,
        nowMs: Long,
        timeoutMs: Long,
    ): Boolean {
        if (!isRinging) return false
        // A non-positive deadline would make every connection stale on sight.
        if (timeoutMs <= 0L) return false
        val elapsed = nowMs - ringingSinceMs
        // elapsedRealtime does not run backwards, but the two readings come
        // from different call sites; fail closed rather than treat a negative
        // age as "very old".
        if (elapsed < 0L) return false
        return elapsed >= timeoutMs
    }
}
