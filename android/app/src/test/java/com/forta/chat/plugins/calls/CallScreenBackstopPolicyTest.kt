package com.forta.chat.plugins.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether native may close the call screen that JS left open after the peer's
 * hangup push ended the call. The screen may be a live call, so unlike the
 * ringer ([RemoteHangupPolicy]) nothing but an exact call id closes it.
 */
class CallScreenBackstopPolicyTest {

    @Test
    fun `the hangup of the call on screen closes it`() {
        assertTrue(CallScreenBackstopPolicy.closes("call-A", "call-A"))
    }

    @Test
    fun `a hangup for another call leaves a live call on screen`() {
        assertFalse(CallScreenBackstopPolicy.closes("call-B", "call-A"))
    }

    @Test
    fun `a push that names no call closes nothing`() {
        assertFalse(CallScreenBackstopPolicy.closes("call-A", null))
        assertFalse(CallScreenBackstopPolicy.closes("call-A", ""))
        assertFalse(CallScreenBackstopPolicy.closes("call-A", "\$hangup-event"))
    }

    @Test
    fun `a screen that does not know its call is not closed`() {
        assertFalse(CallScreenBackstopPolicy.closes(null, "call-A"))
        assertFalse(CallScreenBackstopPolicy.closes("", "call-A"))
    }

    @Test
    fun `an event id on both sides is not a match`() {
        assertFalse(CallScreenBackstopPolicy.closes("\$event", "\$event"))
    }

    @Test
    fun `JS gets a few seconds to close the screen itself`() {
        // Live JS closes the screen within milliseconds of native's callEnded;
        // a frozen page never does. Long enough for a busy main thread, short
        // enough that a finished call does not look hung.
        assertEquals(5_000L, CallScreenBackstopPolicy.GRACE_MS)
    }
}
