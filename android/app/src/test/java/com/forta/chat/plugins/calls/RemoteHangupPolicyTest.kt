package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which incoming-call surface a push-delivered hangup, reject or
 * select_answer may take down. Once the account has a push rule for these
 * events, every one of them reaches the device — including a late hangup for
 * a call that ended earlier while the next call already rings.
 */
class RemoteHangupPolicyTest {

    @Test
    fun `a hangup for the call on screen takes it down`() {
        assertTrue(RemoteHangupPolicy.endsSurface("call-A", "call-A"))
    }

    @Test
    fun `a hangup for another call leaves the surface alone`() {
        assertFalse(RemoteHangupPolicy.endsSurface("call-B", "call-A"))
    }

    @Test
    fun `a push that names no call takes down whatever shows`() {
        // Without call_id the push carries only the hangup's own event id.
        assertTrue(RemoteHangupPolicy.endsSurface("call-B", null))
        assertTrue(RemoteHangupPolicy.endsSurface("call-B", ""))
        assertTrue(RemoteHangupPolicy.endsSurface("call-B", "\$hangup-event"))
    }

    @Test
    fun `a surface keyed by an event id cannot be told apart and is taken down`() {
        assertTrue(RemoteHangupPolicy.endsSurface("\$invite-event", "call-A"))
    }

    @Test
    fun `nothing on screen is nothing to protect`() {
        assertTrue(RemoteHangupPolicy.endsSurface(null, "call-A"))
        assertTrue(RemoteHangupPolicy.endsSurface("", "call-A"))
    }
}
