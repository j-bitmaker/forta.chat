package com.forta.chat.plugins.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookkeeping behind [IncomingRinger]: one ring per process, keyed by
 * callId, with a deadline that dies with the ring it was posted for.
 */
class IncomingRingerLedgerTest {

    @Test
    fun `arming records the call and stop for it reports it was ringing`() {
        val ledger = IncomingRingerLedger()
        ledger.arm("call-1")
        assertEquals("call-1", ledger.armedCallId)
        assertTrue(ledger.isArmedFor("call-1"))
        assertTrue(ledger.stop("call-1"))
        assertNull(ledger.armedCallId)
    }

    @Test
    fun `a stop for another call is a no-op and says so`() {
        // The second call that displaced the first must not be silenced by
        // the first call's teardown.
        val ledger = IncomingRingerLedger()
        ledger.arm("call-2")
        assertFalse(ledger.stop("call-1"))
        assertEquals("call-2", ledger.armedCallId)
    }

    @Test
    fun `a stop with no id silences whatever rings`() {
        // reportCallConnected carries the Matrix call id, which is not the
        // push-side id the ringer was armed with; an answered call stops the
        // ring regardless.
        val ledger = IncomingRingerLedger()
        ledger.arm("push-event-id")
        assertTrue(ledger.stop(null))
        assertNull(ledger.armedCallId)
        assertFalse(ledger.stop(""))
    }

    @Test
    fun `stopping twice reports ringing only once`() {
        val ledger = IncomingRingerLedger()
        ledger.arm("call-1")
        assertTrue(ledger.stop("call-1"))
        assertFalse(ledger.stop("call-1"))
    }

    @Test
    fun `the deadline fires only while its ring is still the current one`() {
        val ledger = IncomingRingerLedger()
        val token = ledger.arm("call-1")
        assertTrue(ledger.mayFire(token))
        ledger.stop("call-1")
        assertFalse("an answered call must never see the auto-reject", ledger.mayFire(token))
    }

    @Test
    fun `re-arming for a second call retires the first deadline`() {
        val ledger = IncomingRingerLedger()
        val first = ledger.arm("call-1")
        val second = ledger.arm("call-2")
        assertFalse("call-1's deadline would reject call-2 on sight", ledger.mayFire(first))
        assertTrue(ledger.mayFire(second))
    }

    @Test
    fun `re-arming the same call restarts its deadline`() {
        val ledger = IncomingRingerLedger()
        val first = ledger.arm("call-1")
        val again = ledger.arm("call-1")
        assertFalse(ledger.mayFire(first))
        assertTrue(ledger.mayFire(again))
    }

    // -- silence: the ring goes quiet, the deadline keeps running -------------------

    @Test
    fun `silence keeps the call armed and its deadline valid`() {
        // Telecom's silence (a volume key pressed while the call rings) is not
        // an answer and not a decline: an unanswered call still ends at 30 s.
        val ledger = IncomingRingerLedger()
        val token = ledger.arm("call-1")
        assertEquals("call-1", ledger.silence())
        assertEquals("call-1", ledger.armedCallId)
        assertTrue(ledger.isSilenced("call-1"))
        assertTrue(ledger.mayFire(token))
    }

    @Test
    fun `silence with nothing ringing does nothing`() {
        val ledger = IncomingRingerLedger()
        assertNull(ledger.silence())
        ledger.arm("call-1")
        ledger.stop("call-1")
        assertNull(ledger.silence())
    }

    @Test
    fun `a silenced call stays quiet when its ringer is raised again`() {
        // A second ringer instance for the same call (a shade tap while the
        // ringer was not on top) re-arms it; the user has already silenced it.
        val ledger = IncomingRingerLedger()
        ledger.arm("call-1")
        ledger.silence()
        val again = ledger.arm("call-1")
        assertTrue(ledger.isSilenced("call-1"))
        assertTrue(ledger.mayFire(again))
    }

    @Test
    fun `a new call rings even after the previous one was silenced`() {
        val ledger = IncomingRingerLedger()
        ledger.arm("call-1")
        ledger.silence()
        ledger.arm("call-2")
        assertFalse(ledger.isSilenced("call-2"))
        assertFalse(ledger.isSilenced("call-1"))
    }

    @Test
    fun `stopping a silenced call clears the silence`() {
        val ledger = IncomingRingerLedger()
        ledger.arm("call-1")
        ledger.silence()
        ledger.stop("call-1")
        assertFalse(ledger.isSilenced("call-1"))
        ledger.arm("call-1")
        assertFalse("a ring after the stop is a new ring", ledger.isSilenced("call-1"))
    }
}
