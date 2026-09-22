package com.forta.chat.plugins.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redial race as it actually happens: call A's stop is *issued* after call
 * B's start, because the JS finalize of A is still between its native steps
 * when the user dials B. Keyed by call id, A's stop is stale and B's is not.
 */
class CallStartLedgerTest {

    private val ledger = CallStartLedger()

    @Test
    fun `a stop for the previous call issued after the next start is stale`() {
        ledger.record("A", 1L)
        ledger.record("B", 2L)
        val current = 2L

        val stopForA = ledger.generationFor("A", current)
        assertEquals(1L, stopForA)
        assertTrue(CallServiceStopPolicy.isStale(stopForA, current))

        val stopForB = ledger.generationFor("B", current)
        assertEquals(2L, stopForB)
        assertFalse(CallServiceStopPolicy.isStale(stopForB, current))
    }

    @Test
    fun `a teardown step for the previous call is stale once the next start is issued`() {
        // The JS finalize's closeAllPeerConnections, reaching native after
        // launchCallUI for the next call.
        ledger.record("A", 1L)
        assertFalse(CallServiceStopPolicy.isStale(ledger.generationFor("A", 1L), 1L))
        ledger.record("B", 2L)
        assertTrue(CallServiceStopPolicy.isStale(ledger.generationFor("A", 2L), 2L))
        assertFalse(CallServiceStopPolicy.isStale(ledger.generationFor("B", 2L), 2L))
        assertFalse(CallServiceStopPolicy.isStale(ledger.generationFor(null, 2L), 2L))
    }

    @Test
    fun `a second stop for the same call is as stale as the first`() {
        // Telecom's onDisconnect and the JS finalize both stop the same call.
        ledger.record("A", 1L)
        ledger.record("B", 2L)
        assertEquals(1L, ledger.generationFor("A", 2L))
        assertEquals(1L, ledger.generationFor("A", 2L))
    }

    @Test
    fun `a stop for the current call is honoured`() {
        ledger.record("A", 1L)
        assertFalse(CallServiceStopPolicy.isStale(ledger.generationFor("A", 1L), 1L))
    }

    @Test
    fun `a stop without an id is issued against the current generation`() {
        ledger.record("A", 1L)
        ledger.record("B", 2L)
        assertEquals(2L, ledger.generationFor(null, 2L))
        assertEquals(2L, ledger.generationFor("", 2L))
    }

    @Test
    fun `a stop for a call nobody started is issued against the current generation`() {
        // A push-keyed connection carries `$event_id`, never the Matrix call id
        // launchCallUI recorded; refusing its stop would strand the audio mode.
        ledger.record("A", 1L)
        assertEquals(1L, ledger.generationFor("\$event", 1L))
    }

    @Test
    fun `an aliased id stops the same start as the id it aliases`() {
        // A push-keyed Telecom slot stops the service under `$event_id`; the
        // service was started under the Matrix id.
        ledger.record("A", 1L)
        ledger.alias("\$eventA", "A")
        ledger.record("B", 2L)
        assertEquals(1L, ledger.generationFor("\$eventA", 2L))
        assertTrue(CallServiceStopPolicy.isStale(ledger.generationFor("\$eventA", 2L), 2L))
    }

    @Test
    fun `an alias for an unrecorded start records nothing`() {
        ledger.alias("\$eventA", "A")
        ledger.alias(null, "A")
        ledger.alias("\$eventA", null)
        ledger.record("A", 1L)
        ledger.alias("A", "A")
        assertEquals(5L, ledger.generationFor("\$eventA", 5L))
    }

    @Test
    fun `a start without an id records nothing`() {
        ledger.record(null, 1L)
        ledger.record("", 2L)
        assertEquals(3L, ledger.generationFor("", 3L))
    }

    @Test
    fun `starts far behind the newest one are dropped`() {
        ledger.record("A", 1L)
        ledger.record("B", 1L + CallStartLedger.KEEP_GENERATIONS)
        assertEquals(1L, ledger.generationFor("A", 2L))
        ledger.record("C", 2L + CallStartLedger.KEEP_GENERATIONS)
        // A is gone: its stop falls back to the current generation.
        assertEquals(99L, ledger.generationFor("A", 99L))
        assertEquals(1L + CallStartLedger.KEEP_GENERATIONS, ledger.generationFor("B", 99L))
    }
}
