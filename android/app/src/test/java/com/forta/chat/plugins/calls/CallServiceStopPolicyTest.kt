package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redial race: the previous call's ACTION_STOP lands after the next call's
 * ACTION_START and must not take the new call down with it.
 */
class CallServiceStopPolicyTest {

    @Test
    fun `a stop issued before the next start is stale once that start ran`() {
        assertTrue(CallServiceStopPolicy.isStale(stopGeneration = 1L, currentGeneration = 2L))
    }

    @Test
    fun `a stop for the current start is honoured`() {
        assertFalse(CallServiceStopPolicy.isStale(stopGeneration = 2L, currentGeneration = 2L))
    }

    @Test
    fun `a stop without a generation is honoured for compatibility`() {
        assertFalse(CallServiceStopPolicy.isStale(stopGeneration = -1L, currentGeneration = 7L))
    }

    @Test
    fun `a stop from the future is not honoured either`() {
        // Cannot happen with a monotonic counter; if it does, the safe answer
        // is still "this is not the call you are running".
        assertTrue(CallServiceStopPolicy.isStale(stopGeneration = 3L, currentGeneration = 2L))
    }
}
