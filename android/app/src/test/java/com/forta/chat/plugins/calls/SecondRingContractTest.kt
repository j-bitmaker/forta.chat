package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The push path asks [SecondRingPolicy] before a call invite may take over a
 * ringer that is still ringing for another call.
 *
 * Found on the Samsung 2026-09-10 (`rebind2`): FCM repainted the ringer to a
 * second caller from another room that JS had rejected 250 ms earlier.
 * Source-level, like [AnswerAdoptionContractTest]: FirebaseMessagingService
 * cannot run under JUnit.
 */
class SecondRingContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val push by lazy { source("com/forta/chat/FortaFirebaseMessagingService.kt") }

    private val onMessage by lazy {
        withoutComments(functionBody(push, "override\\s+fun\\s+onMessageReceived\\s*\\("))
    }

    @Test
    fun theInviteBranch_asksThePolicy_beforeClaimingOrPresentingTheCall() {
        val asked = onMessage.indexOf("SecondRingPolicy.mayTakeOverRinger(")
        val claimed = onMessage.indexOf("lastRingingCallId = callId")
        val presented = onMessage.indexOf("showCallNotification(roomId")
        assertTrue("the policy must be asked before the call is claimed:\n$onMessage", asked in 0 until claimed)
        assertTrue("the call must be claimed before it is presented", claimed in 0 until presented)
    }

    @Test
    fun theRingingCall_isTheRingersOwn() {
        assertTrue(onMessage.contains("IncomingRinger.ringingCallId"))
    }

    @Test
    fun aRefusedSecondCall_isOnlyForwardedToJs() {
        val refused = functionBody(onMessage, "if\\s*\\(\\s*!\\s*SecondRingPolicy\\.mayTakeOverRinger\\(")
        assertTrue("JS must still get the push:\n$refused", refused.contains("forwardToJs(data)"))
        assertTrue("the branch must stop there:\n$refused", refused.contains("return"))
        listOf("showCallNotification", "IncomingCallActivity", "addNewIncomingCall", "lastRingingCallId")
            .forEach { assertFalse("a refused second call must not reach $it:\n$refused", refused.contains(it)) }
    }

    @Test
    fun theRingingRoom_comesFromTheSlot_onlyWhenItHoldsTheRingingCall() {
        val body = withoutComments(functionBody(push, "private\\s+fun\\s+ringingRoomFor\\s*\\("))
        listOf("CallConnectionService.currentConnection", "CallSlotPolicy.owns(", ".roomId")
            .forEach { assertTrue("ringingRoomFor must use $it:\n$body", body.contains(it)) }
    }

    private fun withoutComments(body: String): String =
        body.lines().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at >= 0) line.substring(0, at) else line
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
