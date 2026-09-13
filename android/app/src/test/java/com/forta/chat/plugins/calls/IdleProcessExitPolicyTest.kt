package com.forta.chat.plugins.calls

import com.forta.chat.plugins.calls.IdleProcessExitPolicy.Decision
import com.forta.chat.plugins.calls.IdleProcessExitPolicy.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a process whose call just ended may end itself.
 *
 * Found on the Samsung 2026-09-10: a ringer swiped from Recents while Telecom
 * still held the call left the process alive, marked by the system for a
 * deferred "remove task" kill. AOSP never clears that mark; it fires on the
 * process's next scheduling-group change, which the next call push set off
 * 3–14 ms after `Call invite:`, so that call never rang. The wiring that acts
 * on these decisions is pinned in [IdleProcessExitContractTest].
 */
class IdleProcessExitPolicyTest {

    private val idle = Snapshot(
        uiTaskRunning = false,
        hasConnection = false,
        ringerArmed = false,
        busyServices = 0,
        recentCallPush = false,
    )

    @Test
    fun exits_whenNothingIsLeftToPresent() {
        assertEquals(Decision.EXIT, IdleProcessExitPolicy.decide(idle, attempt = 0))
    }

    @Test
    fun stays_whileATaskStillHasActivities() {
        // The app is open or in the background: the user can come back to it.
        assertEquals(Decision.STAY, IdleProcessExitPolicy.decide(idle.copy(uiTaskRunning = true), attempt = 0))
    }

    @Test
    fun stays_whileTelecomHoldsAConnection() {
        // Its own release schedules the next check; waiting here would only
        // spend the retry budget on a call that can last an hour.
        assertEquals(Decision.STAY, IdleProcessExitPolicy.decide(idle.copy(hasConnection = true), attempt = 0))
    }

    @Test
    fun retries_whileTheRingerStillRings() {
        assertEquals(Decision.RETRY, IdleProcessExitPolicy.decide(idle.copy(ringerArmed = true), attempt = 0))
    }

    @Test
    fun retries_whileAServiceIsStartedOrInTheForeground() {
        // A push being handled right now, or the call service winding down.
        assertEquals(Decision.RETRY, IdleProcessExitPolicy.decide(idle.copy(busyServices = 1), attempt = 0))
    }

    @Test
    fun retries_shortlyAfterACallPush() {
        // The redial race: the push was handled, Telecom has not created the
        // connection yet, and nothing else shows the call is coming.
        assertEquals(Decision.RETRY, IdleProcessExitPolicy.decide(idle.copy(recentCallPush = true), attempt = 0))
    }

    @Test
    fun aCallPush_holdsForItsWindowOnly() {
        val hold = IdleProcessExitPolicy.CALL_PUSH_HOLD_MS
        assertFalse(IdleProcessExitPolicy.isRecentCallPush(lastCallPushAtMs = null, nowMs = 5_000L))
        assertTrue(IdleProcessExitPolicy.isRecentCallPush(lastCallPushAtMs = 5_000L, nowMs = 5_000L))
        assertTrue(IdleProcessExitPolicy.isRecentCallPush(lastCallPushAtMs = 5_000L, nowMs = 5_000L + hold - 1))
        assertFalse(IdleProcessExitPolicy.isRecentCallPush(lastCallPushAtMs = 5_000L, nowMs = 5_000L + hold))
    }

    @Test
    fun givesUp_onTheLastAttempt_ratherThanEndingABusyProcess() {
        val busy = idle.copy(busyServices = 2)
        val last = IdleProcessExitPolicy.MAX_ATTEMPTS - 1
        assertEquals(Decision.RETRY, IdleProcessExitPolicy.decide(busy, attempt = last - 1))
        assertEquals(Decision.STAY, IdleProcessExitPolicy.decide(busy, attempt = last))
    }

    @Test
    fun aRunningTask_staysForGood_evenWhenAServiceIsAlsoBusy() {
        val both = idle.copy(uiTaskRunning = true, busyServices = 1, ringerArmed = true)
        assertEquals(Decision.STAY, IdleProcessExitPolicy.decide(both, attempt = 0))
    }

    @Test
    fun theRetryWindow_outlastsTheRingersOwnDeadline() {
        // A ringer that Telecom refused a connection for ends on its 30 s
        // deadline; the checks must still be running when it does.
        val window = IdleProcessExitPolicy.GRACE_MS +
            (IdleProcessExitPolicy.MAX_ATTEMPTS - 1) * IdleProcessExitPolicy.RETRY_MS
        assertTrue("retry window ${window}ms", window > IncomingRinger.AUTO_REJECT_TIMEOUT_MS)
        assertTrue("retry window ${window}ms", window > IdleProcessExitPolicy.CALL_PUSH_HOLD_MS)
    }
}
