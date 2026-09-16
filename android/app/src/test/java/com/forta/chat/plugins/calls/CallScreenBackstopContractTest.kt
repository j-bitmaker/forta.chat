package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The call screen closes after the peer's hangup even when JS behind it is silent.
 *
 * CallActivity was closed only by JS (`dismissCallUI`) and by the ongoing-call
 * notification's hangup action. Chromium freezes the page behind the screen
 * (see page-awake-tone.ts), and a frozen page never processes the hangup: the
 * push branch disconnected the Telecom connection and released the audio, but
 * the finished call stayed on screen. Source-level, like
 * [RemoteHangupContractTest]: the service and the activity cannot run under JUnit.
 */
class CallScreenBackstopContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val push by lazy { withoutComments(source("com/forta/chat/FortaFirebaseMessagingService.kt")) }
    private val screen by lazy { withoutComments(source("com/forta/chat/plugins/calls/CallActivity.kt")) }

    private val hangup by lazy {
        val start = push.indexOf("msgType == \"m.call.hangup\"")
        val end = push.indexOf("if (msgType == \"m.call.invite\")", start)
        require(start >= 0 && end > start) { "hangup branch not found in FortaFirebaseMessagingService.kt" }
        push.substring(start, end)
    }

    @Test
    fun onlyAHangupPush_armsTheBackstop_forTheCallItEnded() {
        // A select_answer push names a call this device may have just answered:
        // arming on it would close every answered call's screen.
        val armed = Regex(
            "if\\s*\\(\\s*msgType\\s*==\\s*\"m\\.call\\.hangup\"\\s*\\)\\s*\\{\\s*" +
                "(com\\.forta\\.chat\\.plugins\\.calls\\.)?CallActivity\\.scheduleRemoteHangupClose\\(endedCallId\\)",
        )
        assertTrue("the hangup branch must arm the backstop for m.call.hangup only:\n$hangup", armed.containsMatchIn(hangup))
    }

    @Test
    fun theBackstop_waitsForJs_thenClosesOnlyTheEndedCallsScreen() {
        val body = functionBody(screen, "fun\\s+scheduleRemoteHangupClose\\s*\\(")
        listOf("postDelayed", "CallScreenBackstopPolicy.GRACE_MS", "CallScreenBackstopPolicy.closes(", "finish()").forEach {
            assertTrue("scheduleRemoteHangupClose must use $it:\n$body", body.contains(it))
        }
        assertTrue(
            "the id check must come before finish():\n$body",
            body.indexOf("CallScreenBackstopPolicy.closes(") < body.indexOf("finish()"),
        )
    }

    @Test
    fun theScreen_knowsWhichCallItShows() {
        val create = functionBody(screen, "override\\s+fun\\s+onCreate\\s*\\(")
        assertTrue("onCreate must record its call id:\n$create", Regex("shownCallId\\s*=.*EXTRA_CALL_ID").containsMatchIn(create))
        assertTrue("onCreate must register the instance:\n$create", create.contains("currentInstance = this"))
        val relaunch = functionBody(screen, "override\\s+fun\\s+onNewIntent\\s*\\(")
        assertTrue("a relaunch must update the call id:\n$relaunch", relaunch.contains("shownCallId") && relaunch.contains("EXTRA_CALL_ID"))
        val destroy = functionBody(screen, "override\\s+fun\\s+onDestroy\\s*\\(")
        assertTrue(
            "onDestroy must drop only its own registration:\n$destroy",
            Regex("if\\s*\\(\\s*currentInstance\\s*===\\s*this\\s*\\)\\s*currentInstance\\s*=\\s*null").containsMatchIn(destroy),
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
