package com.forta.chat.plugins.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingIntentMergeTest {

    private val held = IncomingCallExtras(
        callId = "call-1",
        callerName = "Alice",
        roomId = "!room:server",
        hasVideo = true,
        action = null,
    )

    @Test
    fun `a shade tap without a room keeps the room the ringer was opened for`() {
        // The Telecom notification's Accept intent: callId + callerName +
        // action, nothing else.
        val tap = IncomingCallExtras("call-1", "Alice", null, null, "accept")
        val merged = IncomingIntentMerge.merge(held, tap)
        assertEquals("!room:server", merged.roomId)
        assertEquals(true, merged.hasVideo)
        assertEquals("accept", merged.action)
        assertEquals("call-1", merged.callId)
    }

    @Test
    fun `fields the new intent does carry win`() {
        val tap = IncomingCallExtras("call-1", "Alice (work)", "!other:server", false, "decline")
        val merged = IncomingIntentMerge.merge(held, tap)
        assertEquals("!other:server", merged.roomId)
        assertEquals(false, merged.hasVideo)
        assertEquals("Alice (work)", merged.callerName)
        assertEquals("decline", merged.action)
    }

    @Test
    fun `the action is always the new intent's, including none`() {
        val plain = IncomingCallExtras("call-1", null, null, null, null)
        assertNull(IncomingIntentMerge.merge(held.copy(action = "accept"), plain).action)
    }

    @Test
    fun `an unknown caller does not overwrite a known one`() {
        val tap = IncomingCallExtras("call-1", "Unknown", null, null, "accept")
        assertEquals("Alice", IncomingIntentMerge.merge(held, tap).callerName)
    }

    @Test
    fun `a missing callId means the same call`() {
        val tap = IncomingCallExtras("", null, null, null, "accept")
        val merged = IncomingIntentMerge.merge(held, tap)
        assertEquals("call-1", merged.callId)
        assertEquals("!room:server", merged.roomId)
    }

    @Test
    fun `a second caller's intent is taken as it is`() {
        // Filling the second call's missing room from the first call's
        // would answer the wrong room.
        val second = IncomingCallExtras("call-2", "Bob", null, null, null)
        val merged = IncomingIntentMerge.merge(held, second)
        assertEquals("call-2", merged.callId)
        assertEquals("Bob", merged.callerName)
        assertNull(merged.roomId)
        assertNull(merged.hasVideo)
    }
}
