package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The push path does not ring a call that Telecom answers BUSY.
 *
 * Found on the Samsung 2026-09-15 (`ho-reopen1`): while call A held the slot
 * ACTIVE, the push for call B started IncomingCallActivity and armed the ringer
 * 161 ms after `onCreateIncomingConnection` had reported B busy. Nothing could
 * answer B, and it rang for 30 s until `no answer in 30s … auto-rejecting`.
 * Source-level, like [SecondRingContractTest]: FirebaseMessagingService cannot
 * run under JUnit.
 */
class EstablishedCallRingContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val push by lazy { source("com/forta/chat/FortaFirebaseMessagingService.kt") }

    private val onMessage by lazy {
        withoutComments(functionBody(push, "override\\s+fun\\s+onMessageReceived\\s*\\("))
    }

    private val inviteBranch by lazy {
        functionBody(onMessage, "if\\s*\\(\\s*msgType\\s*==\\s*\"m\\.call\\.invite\"\\s*\\)")
    }

    @Test
    fun theInviteBranch_checksForAConversation_beforeClaimingOrPresentingTheCall() {
        val checked = inviteBranch.indexOf("DisplacedConnectionPolicy.mayRelease(")
        val claimed = inviteBranch.indexOf("lastRingingCallId = callId")
        val presented = inviteBranch.indexOf("showCallNotification(roomId")
        assertTrue("the conversation check must come before the call is claimed:\n$inviteBranch", checked in 0 until claimed)
        assertTrue("the call must be claimed before it is presented", claimed in 0 until presented)
    }

    @Test
    fun theConversation_isReadFromTheTelecomSlot() {
        assertTrue(
            "the check must read the slot's state:\n$inviteBranch",
            inviteBranch.contains("CallConnectionService.currentConnection") && inviteBranch.contains(".state"),
        )
    }

    @Test
    fun aCallArrivingDuringAConversation_isOnlyForwardedToJs() {
        val refused = functionBody(inviteBranch, "if\\s*\\(\\s*established\\s*!=\\s*null\\s*\\)")
        assertTrue("JS must still get the push:\n$refused", refused.contains("forwardToJs(data)"))
        assertTrue("the branch must stop there:\n$refused", refused.contains("return"))
        listOf("showCallNotification", "IncomingCallActivity", "addNewIncomingCall", "lastRingingCallId", "IncomingRinger")
            .forEach { assertFalse("a call arriving during a conversation must not reach $it:\n$refused", refused.contains(it)) }
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
