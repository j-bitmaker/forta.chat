package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSlotPolicyTest {

    @Test
    fun sameId_owns() {
        assertTrue(CallSlotPolicy.owns("call-a", "call-a"))
    }

    @Test
    fun differentId_doesNotOwn() {
        // The O06 case: JS reports a refused second invite ended; the slot
        // holds the conversation.
        assertFalse(CallSlotPolicy.owns("call-a", "call-b"))
    }

    @Test
    fun noRequestedId_owns() {
        assertTrue(CallSlotPolicy.owns("call-a", null))
        assertTrue(CallSlotPolicy.owns("call-a", ""))
    }

    @Test
    fun unkeyedSlot_owns() {
        // Legacy: an outgoing Connection created before the extras fix.
        assertTrue(CallSlotPolicy.owns("", "call-a"))
    }

    @Test
    fun pushEventIdSlot_isUnkeyed_soItCanStillBeEnded() {
        // A push without call_id keys the Connection by the event id; Matrix
        // call ids never equal it, and refusing to end it would strand Telecom.
        assertTrue(CallSlotPolicy.owns("\$evt123:matrix.org", "call-a"))
    }
}
