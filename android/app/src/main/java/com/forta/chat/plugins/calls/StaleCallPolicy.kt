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
 *
 * [isUnadoptedAnswer] is the same kind of net for an answered connection that
 * JS never picked up.
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

    /**
     * True when Telecom answered this connection natively and JS has not
     * reported the call connected within [timeoutMs].
     *
     * `onAnswer` cancels the ring timeout, and nothing else ends an answered
     * connection whose JS never comes: no hangup push reaches this app, and the
     * cold-start sweep leaves a live slot alone. Seen on a Samsung 2026-09-13 —
     * a call swiped away while ringing was answered from AirPods with JS dead
     * and stayed ACTIVE until a force-stop, turning the next call away as busy.
     *
     * As strict as [isStaleRinging]: a call JS reported connected is never
     * released however long it lasts, and the window outlasts a cold start from
     * push, which logs in, syncs and connects inside it.
     *
     * @param isActive      connection state is `STATE_ACTIVE`
     * @param answeredAtMs  `SystemClock.elapsedRealtime()` at `onAnswer`; null when Telecom never answered it
     * @param adoptedByJs   JS reported the call connected
     * @param nowMs         `SystemClock.elapsedRealtime()` now
     * @param timeoutMs     adoption deadline, normally [CallConnection.ANSWER_ADOPTION_TIMEOUT_MS]
     */
    fun isUnadoptedAnswer(
        isActive: Boolean,
        answeredAtMs: Long?,
        adoptedByJs: Boolean,
        nowMs: Long,
        timeoutMs: Long,
    ): Boolean {
        if (!isActive || adoptedByJs || answeredAtMs == null) return false
        if (timeoutMs <= 0L) return false
        val elapsed = nowMs - answeredAtMs
        if (elapsed < 0L) return false
        return elapsed >= timeoutMs
    }
}
