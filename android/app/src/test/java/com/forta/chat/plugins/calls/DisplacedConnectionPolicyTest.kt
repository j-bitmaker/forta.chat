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

    @Test
    fun `a second registration of the call already ringing is the same call`() {
        // Measured on the Samsung 2026-09-10: with the app alive in the
        // background one call push reaches Telecom twice — FCM rings natively
        // and the push forwarded to JS reports the call again. Displacing the
        // first connection fired callEnded into JS for the call still ringing.
        assertTrue(
            DisplacedConnectionPolicy.isSameRingingCall(
                "1789063215601vJG9diMjapJ0caug", Connection.STATE_RINGING, "1789063215601vJG9diMjapJ0caug",
            )
        )
    }

    @Test
    fun `a different callId is another call and still displaces the ringing one`() {
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall("old-call", Connection.STATE_RINGING, "new-call"))
    }

    @Test
    fun `a missing callId on either side never counts as the same call`() {
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall("", Connection.STATE_RINGING, ""))
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall(null, Connection.STATE_RINGING, "call"))
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall("call", Connection.STATE_RINGING, null))
    }

    @Test
    fun `a same-id connection that no longer rings is not kept in place of the new one`() {
        // Only a live ring is worth keeping: a finished connection presents
        // nothing, and an established one is answered busy before this is asked.
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall("call", Connection.STATE_DISCONNECTED, "call"))
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall("call", Connection.STATE_ACTIVE, "call"))
        assertFalse(DisplacedConnectionPolicy.isSameRingingCall("call", Connection.STATE_NEW, "call"))
    }
}
