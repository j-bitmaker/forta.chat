package com.forta.chat.plugins.calls

import android.telecom.Connection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingAcceptPolicyTest {

    private val PUSH_A = "\$evtA"
    private val PUSH_B = "\$evtB"
    private val MATRIX_A = "1789034161187pxgxtIqnl2JkYLG4"
    private val MATRIX_B = "1789034202148J6ae4EAw61zFoWRR"

    @Test
    fun `the ringing call this surface shows may be answered`() {
        assertTrue(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_RINGING, MATRIX_A, MATRIX_A),
        )
    }

    @Test
    fun `an established call is never answered, even when the ids look comparable`() {
        // The BUSY collision: the user is talking to A, a push ringer for B is
        // on screen, and the tap must not reach A's connection.
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_ACTIVE, MATRIX_A, MATRIX_B),
        )
    }

    @Test
    fun `an established call is never answered when the slot id is a push event id`() {
        // This is the case an id-only guard misses, and it is the COMMON shape
        // on this homeserver: a push-created connection is keyed by the event
        // id, and CallSlotPolicy.owns treats such a slot as unkeyed on purpose
        // so Telecom can always be ended. Answering has no such need.
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_ACTIVE, PUSH_A, PUSH_B),
        )
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_ACTIVE, PUSH_A, MATRIX_B),
        )
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_ACTIVE, "", MATRIX_B),
        )
    }

    @Test
    fun `a held call is never answered`() {
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_HOLDING, MATRIX_A, MATRIX_B),
        )
    }

    @Test
    fun `an outgoing or already dead connection is never answered`() {
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_DIALING, MATRIX_A, MATRIX_A),
        )
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_DISCONNECTED, MATRIX_A, MATRIX_A),
        )
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_NEW, MATRIX_A, MATRIX_A),
        )
    }

    @Test
    fun `a ringing slot keyed by a push event id still answers`() {
        // Cold start from push is the whole reason the ringer exists: the ids
        // cannot be compared, both calls are merely ringing, and nobody's
        // conversation is at stake.
        assertTrue(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_RINGING, PUSH_A, MATRIX_B),
        )
    }

    @Test
    fun `a ringing slot for a different Matrix call is not answered by a stale surface`() {
        assertFalse(
            IncomingAcceptPolicy.mayAnswerSlot(Connection.STATE_RINGING, MATRIX_A, MATRIX_B),
        )
    }
}
