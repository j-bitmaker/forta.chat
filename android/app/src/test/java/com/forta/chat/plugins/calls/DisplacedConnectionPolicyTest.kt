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

    @Test
    fun `an unpresented slot is released only while it is still ringing`() {
        assertTrue(DisplacedConnectionPolicy.mayReleaseUnpresented(Connection.STATE_RINGING))
    }

    @Test
    fun `a slot answered under us is never released as unpresented`() {
        // The race this closes: the caller decides a connection is stranded on
        // Capacitor's plugin thread, and before the release runs Telecom answers
        // it from a Bluetooth headset, Android Auto or the system call UI —
        // none of which touch our activity, and onAnswer does not latch
        // `released`. Disconnecting then cuts off a call the user just took.
        assertFalse(DisplacedConnectionPolicy.mayReleaseUnpresented(Connection.STATE_ACTIVE))
    }

    @Test
    fun `neither a dialing nor a held slot is released as unpresented`() {
        // Both belong to a path that owns their teardown; only an incoming ring
        // can be stranded with nothing presenting it.
        assertFalse(DisplacedConnectionPolicy.mayReleaseUnpresented(Connection.STATE_DIALING))
        assertFalse(DisplacedConnectionPolicy.mayReleaseUnpresented(Connection.STATE_HOLDING))
        assertFalse(DisplacedConnectionPolicy.mayReleaseUnpresented(Connection.STATE_DISCONNECTED))
        assertFalse(DisplacedConnectionPolicy.mayReleaseUnpresented(Connection.STATE_NEW))
    }
}
