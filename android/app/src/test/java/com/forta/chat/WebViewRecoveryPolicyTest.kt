package com.forta.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision table behind [WebViewRecoveryPolicy]. The case that motivated it
 * is the last one: a WebView orphaned by a swipe-away must never take the
 * process down with it, because by the time its renderer is reaped a new
 * activity is usually already starting in that process.
 */
class WebViewRecoveryPolicyTest {

    @Test
    fun aLiveActivityLosingItsOwnRenderer_rebuildsItself() {
        assertEquals(
            RenderProcessRecovery.RECREATE_ACTIVITY,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = true,
                activityAlive = true,
                msSinceLastRecovery = null,
            ),
        )
    }

    @Test
    fun aWebViewLeftBehindByAnEarlierActivity_isDroppedWithoutKillingTheProcess() {
        assertEquals(
            RenderProcessRecovery.DISCARD_STALE,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = false,
                activityAlive = true,
                msSinceLastRecovery = null,
            ),
        )
    }

    @Test
    fun aDestroyedActivity_neverTriesToRecreateItself() {
        assertEquals(
            RenderProcessRecovery.DISCARD_STALE,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = true,
                activityAlive = false,
                msSinceLastRecovery = null,
            ),
        )
    }

    @Test
    fun aSecondDeathInsideTheCooldown_handsTheProcessBackToAndroid() {
        assertEquals(
            RenderProcessRecovery.LET_SYSTEM_KILL,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = true,
                activityAlive = true,
                msSinceLastRecovery = WebViewRecoveryPolicy.RECOVERY_COOLDOWN_MS - 1,
            ),
        )
    }

    @Test
    fun aDeathAfterTheCooldown_isRecoveredAgain() {
        assertEquals(
            RenderProcessRecovery.RECREATE_ACTIVITY,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = true,
                activityAlive = true,
                msSinceLastRecovery = WebViewRecoveryPolicy.RECOVERY_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun aBackwardsClockFailsClosed_ratherThanLoopingRebuilds() {
        assertEquals(
            RenderProcessRecovery.LET_SYSTEM_KILL,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = true,
                activityAlive = true,
                msSinceLastRecovery = -1,
            ),
        )
    }

    @Test
    fun aStaleWebViewIsDropped_evenWhileTheCooldownWouldOtherwiseBlockRecovery() {
        // The cooldown guards rebuilds, not the "keep the process alive" answer.
        // Confusing the two would resurrect the original bug: a swipe-away
        // orphan arriving right after a genuine recovery would kill the process.
        assertEquals(
            RenderProcessRecovery.DISCARD_STALE,
            WebViewRecoveryPolicy.decide(
                isCurrentWebView = false,
                activityAlive = true,
                msSinceLastRecovery = 0,
            ),
        )
    }
}
