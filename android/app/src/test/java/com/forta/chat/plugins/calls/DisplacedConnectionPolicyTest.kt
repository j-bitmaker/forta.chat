package com.forta.chat.plugins.calls

import android.telecom.Connection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplacedConnectionPolicyTest {

    @Test
    fun `an active connection is never released by displacement`() {
        // The regression this guards: nothing checks for a busy state before
        // addNewIncomingCall, so a second caller ringing mid-conversation
        // displaces the live call. Releasing it fires onEnded into JS and hangs
        // up the conversation the user is having.
        assertFalse(DisplacedConnectionPolicy.mayRelease(Connection.STATE_ACTIVE))
    }

    @Test
    fun `a held connection is never released by displacement`() {
        assertFalse(DisplacedConnectionPolicy.mayRelease(Connection.STATE_HOLDING))
    }

    @Test
    fun `a ringing connection is released — this is the orphan being fixed`() {
        assertTrue(DisplacedConnectionPolicy.mayRelease(Connection.STATE_RINGING))
    }

    @Test
    fun `a dialing connection is released`() {
        assertTrue(DisplacedConnectionPolicy.mayRelease(Connection.STATE_DIALING))
    }

    @Test
    fun `every non-established state is releasable`() {
        val nonEstablished = listOf(
            Connection.STATE_INITIALIZING,
            Connection.STATE_NEW,
            Connection.STATE_RINGING,
            Connection.STATE_DIALING,
            Connection.STATE_DISCONNECTED,
            Connection.STATE_PULLING_CALL,
        )
        nonEstablished.forEach { state ->
            assertTrue("state $state should be releasable", DisplacedConnectionPolicy.mayRelease(state))
        }
    }
}
