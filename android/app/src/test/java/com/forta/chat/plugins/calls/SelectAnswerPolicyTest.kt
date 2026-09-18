package com.forta.chat.plugins.calls

import android.telecom.Connection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `m.call.select_answer` push reaches every device of the callee, the one
 * that answered included. Only a device that did not answer may end its ring.
 */
class SelectAnswerPolicyTest {

    @Test
    fun `the call answered on this device is left alone`() {
        assertTrue(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_ACTIVE, "call-A"))
        assertTrue(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_HOLDING, "call-A"))
    }

    @Test
    fun `a call still ringing here was answered on another device`() {
        assertFalse(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_RINGING, "call-A"))
        assertFalse(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_NEW, "call-A"))
    }

    @Test
    fun `an empty slot answered nothing`() {
        assertFalse(SelectAnswerPolicy.answeredHere(null, null, "call-A"))
    }

    @Test
    fun `another call answered elsewhere does not pass for the conversation in the slot`() {
        assertFalse(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_ACTIVE, "call-B"))
    }

    @Test
    fun `a push that names no call never ends a conversation in progress`() {
        assertTrue(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_ACTIVE, null))
        assertTrue(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_ACTIVE, ""))
        assertTrue(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_ACTIVE, "\$select-answer-event"))
        assertFalse(SelectAnswerPolicy.answeredHere("call-A", Connection.STATE_RINGING, null))
    }

    @Test
    fun `a slot keyed by an event id cannot be told apart and keeps its conversation`() {
        assertTrue(SelectAnswerPolicy.answeredHere("\$invite-event", Connection.STATE_ACTIVE, "call-A"))
    }
}
