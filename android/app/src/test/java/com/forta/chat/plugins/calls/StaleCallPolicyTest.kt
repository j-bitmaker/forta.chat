package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Largest open cluster in the call reports is "the phone stays stuck after a
 * call": 14 of them were submitted while the device was still in
 * MODE_RINGTONE. The app never sets that mode — Telecom holds it for as long
 * as a self-managed [CallConnection] is RINGING, so the real defect is a
 * connection nobody ever resolved.
 *
 * [CallConnection.armRingTimeout] is the primary backstop, but it is a main-
 * looper Handler: Doze, a wedged main thread or a process that was frozen and
 * thawed can all delay it past its deadline. This policy is the second net,
 * consulted when the app comes back to the foreground.
 *
 * The bar is deliberately strict: releasing a connection that is *legitimately*
 * ringing would hang up on a call the user is about to answer, which is worse
 * than the bug being fixed. Only a connection that outlived the ring timeout
 * qualifies, because the timeout is the point past which no legitimate ringing
 * connection can still exist.
 */
class StaleCallPolicyTest {

    private val timeout = CallConnection.RING_TIMEOUT_MS

    @Test
    fun `a connection that outlived the ring timeout is stale`() {
        assertTrue(
            StaleCallPolicy.isStaleRinging(
                isRinging = true,
                ringingSinceMs = 0L,
                nowMs = timeout,
                timeoutMs = timeout,
            ),
        )
    }

    @Test
    fun `a connection still inside the ring window is not stale`() {
        // The user is looking at the incoming-call screen right now. Releasing
        // here would decline the call out from under them.
        assertFalse(
            StaleCallPolicy.isStaleRinging(
                isRinging = true,
                ringingSinceMs = 0L,
                nowMs = timeout - 1,
                timeoutMs = timeout,
            ),
        )
    }

    @Test
    fun `a connection that just started ringing is not stale`() {
        assertFalse(
            StaleCallPolicy.isStaleRinging(
                isRinging = true,
                ringingSinceMs = 5_000L,
                nowMs = 5_000L,
                timeoutMs = timeout,
            ),
        )
    }

    @Test
    fun `a connection that is not ringing is never stale`() {
        // An active (answered) call is old by definition — a 40-minute
        // conversation must not be mistaken for an abandoned ring.
        assertFalse(
            StaleCallPolicy.isStaleRinging(
                isRinging = false,
                ringingSinceMs = 0L,
                nowMs = 40 * 60_000L,
                timeoutMs = timeout,
            ),
        )
    }

    @Test
    fun `a clock that moved backwards does not make a connection stale`() {
        // elapsedRealtime cannot go backwards, but the reader and the writer
        // are different call sites; a negative delta must fail closed rather
        // than wrap into a huge positive age.
        assertFalse(
            StaleCallPolicy.isStaleRinging(
                isRinging = true,
                ringingSinceMs = 10_000L,
                nowMs = 1_000L,
                timeoutMs = timeout,
            ),
        )
    }

    @Test
    fun `a non-positive timeout disables the policy instead of releasing everything`() {
        assertFalse(
            StaleCallPolicy.isStaleRinging(
                isRinging = true,
                ringingSinceMs = 0L,
                nowMs = 60_000L,
                timeoutMs = 0L,
            ),
        )
    }

    @Test
    fun `the policy deadline matches the connection ring timeout`() {
        // If the timeout constant is ever shortened below the activity's own
        // 30 s countdown, this net would start firing on live calls.
        assertTrue(
            "RING_TIMEOUT_MS must stay above the incoming-call activity countdown (30s)",
            CallConnection.RING_TIMEOUT_MS > 30_000L,
        )
    }
}
