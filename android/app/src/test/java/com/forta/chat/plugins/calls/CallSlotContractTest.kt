package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the O06 wiring: the outgoing Connection gets its callId, and the
 * three callId-blind readers of the slot are keyed through [CallSlotPolicy].
 */
class CallSlotContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val plugin by lazy { source("com/forta/chat/plugins/calls/CallPlugin.kt") }
    private val service by lazy { source("com/forta/chat/plugins/calls/CallConnectionService.kt") }
    private val firebase by lazy { source("com/forta/chat/FortaFirebaseMessagingService.kt") }
    private val teardown by lazy { source("com/forta/chat/plugins/calls/CallTeardown.kt") }
    private val ringer by lazy { source("com/forta/chat/plugins/calls/IncomingCallActivity.kt") }

    @Test
    fun outgoingCall_nestsItsExtras_whereTelecomDeliversThem() {
        val body = functionBody(plugin, "fun\\s+reportOutgoingCall\\s*\\(")
        val nested = body.indexOf("putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, callExtras)")
        val place = body.indexOf("telecomManager.placeCall(")
        assertTrue("the callId must travel under EXTRA_OUTGOING_CALL_EXTRAS:\n$body", nested in 0 until place)
        assertTrue(body.contains("putString(\"callId\", callId)"))
    }

    @Test
    fun outgoingConnection_readsTheNestedBundleToo() {
        val body = functionBody(service, "override\\s+fun\\s+onCreateOutgoingConnection\\s*\\(")
        assertTrue(body.contains("getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)"))
        assertTrue("callId must fall back to the nested bundle:\n$body", body.contains("nested.getString(\"callId\", \"\")"))
    }

    @Test
    fun slotReaders_areKeyedByCallId() {
        for (name in listOf("reportCallEnded", "reportCallConnected")) {
            val body = functionBody(plugin, "fun\\s+$name\\s*\\(")
            assertTrue("$name must read the callId:\n$body", body.contains("call.getString(\"callId\")"))
            assertTrue("$name must go through CallSlotPolicy.owns:\n$body", body.contains("CallSlotPolicy.owns(slot.callId, callId)"))
        }
        val start = firebase.indexOf("msgType == \"m.call.hangup\"")
        val end = firebase.indexOf("// Handle calls", start)
        require(start >= 0 && end > start)
        val hangup = firebase.substring(start, end)
        assertTrue("the push hangup must be keyed on endedCallId:\n$hangup", hangup.contains("CallSlotPolicy.owns(connection.callId, endedCallId)"))
    }

    @Test
    fun teardownAndDecline_useTheSameSlotRule() {
        val collect = functionBody(teardown, "fun\\s+collectState\\s*\\(")
        assertTrue("otherCallLive must go through CallSlotPolicy.owns:\n$collect", collect.contains("!CallSlotPolicy.owns(slot.callId, callId)"))
        val decline = functionBody(ringer, "fun\\s+rejectRingingConnection\\s*\\(")
        assertTrue("decline must go through CallSlotPolicy.owns:\n$decline", decline.contains("CallSlotPolicy.owns(connection.callId, callId)"))
    }

    private fun functionBody(src: String, signaturePattern: String): String {
        val match = Regex("$signaturePattern[^{]*\\{").find(src)
            ?: error("Could not find /$signaturePattern/ in source")
        var depth = 1
        var i = match.range.last + 1
        val start = i
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return src.substring(start, i - 1)
    }
}
