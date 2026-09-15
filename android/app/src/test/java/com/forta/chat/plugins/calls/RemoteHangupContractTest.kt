package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A push-delivered hangup takes down only the surfaces of the call it ended.
 *
 * The push branch used to stop every ring, close whatever incoming screen was
 * up and cancel both call notifications, whichever call the hangup was for.
 * That was harmless while no hangup push ever arrived; a push rule for
 * `m.call.hangup` delivers them all, and a late one would silence the next
 * call. Source-level, like [CallTeardownContractTest]: the service and the
 * activity cannot run under JUnit.
 */
class RemoteHangupContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val push by lazy { withoutComments(source("com/forta/chat/FortaFirebaseMessagingService.kt")) }
    private val ringer by lazy { withoutComments(source("com/forta/chat/plugins/calls/IncomingCallActivity.kt")) }

    private val hangup by lazy {
        val start = push.indexOf("msgType == \"m.call.hangup\"")
        val end = push.indexOf("if (msgType == \"m.call.invite\")", start)
        require(start >= 0 && end > start) { "hangup branch not found in FortaFirebaseMessagingService.kt" }
        push.substring(start, end)
    }

    @Test
    fun theEndedCall_isResolvedBeforeAnySurfaceIsTouched() {
        val resolved = hangup.indexOf("val endedCallId")
        listOf("dismissIfShowing(", "dismissIncomingCallNotification(", "dismissPushCallNotification(").forEach {
            val at = hangup.indexOf(it)
            assertTrue("$it must come after endedCallId is resolved:\n$hangup", resolved in 0 until at)
        }
    }

    @Test
    fun theIncomingScreen_isAskedToCloseOnlyForTheEndedCall() {
        assertTrue(
            "the screen dismissal must carry the ended call:\n$hangup",
            hangup.contains("IncomingCallActivity.dismissIfShowing(endedCallId)"),
        )
    }

    @Test
    fun bothCallNotifications_areCancelledOnlyForTheEndedCall() {
        listOf(
            "CallConnectionService\\.dismissIncomingCallNotification\\(this\\)",
            "dismissPushCallNotification\\(this,\\s*roomId\\)",
        ).forEach { call ->
            val guarded = Regex(
                "if\\s*\\(\\s*RemoteHangupPolicy\\.endsSurface\\([^)]*\\)\\s*\\)\\s*\\{\\s*" +
                    "(com\\.forta\\.chat\\.plugins\\.calls\\.)?$call",
            )
            assertTrue("$call must sit under a RemoteHangupPolicy guard:\n$hangup", guarded.containsMatchIn(hangup))
        }
    }

    @Test
    fun theIncomingScreen_keysItsRingAndItsScreenByTheEndedCall() {
        val dismiss = functionBody(ringer, "fun\\s+dismissIfShowing\\s*\\(")
        assertFalse("a keyed dismissal must not stop every ring:\n$dismiss", dismiss.contains("IncomingRinger.stopAll()"))
        assertTrue(
            "the ring and the screen must each be checked against the ended call:\n$dismiss",
            Regex("RemoteHangupPolicy\\.endsSurface\\(").findAll(dismiss).count() >= 2,
        )
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
