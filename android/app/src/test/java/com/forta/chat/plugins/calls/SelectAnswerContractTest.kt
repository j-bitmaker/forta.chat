package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A `m.call.select_answer` push for a call answered on this device touches
 * nothing: the check sits in front of the remote-hangup teardown and leaves
 * through the JS forward alone. Source-level, like [RemoteHangupContractTest]:
 * the service cannot run under JUnit.
 */
class SelectAnswerContractTest {

    private val push by lazy {
        val relative = "com/forta/chat/FortaFirebaseMessagingService.kt"
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        val body = candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
        body.lines().joinToString("\n") { line ->
            val at = line.indexOf("//")
            if (at >= 0) line.substring(0, at) else line
        }
    }

    @Test
    fun theAnsweredHereCheck_comesBeforeTheTeardown() {
        val check = push.indexOf("SelectAnswerPolicy.answeredHere(")
        val teardown = push.indexOf("msgType == \"m.call.hangup\" ||")
        assertTrue("the check must precede the remote-hangup branch", check in 0 until teardown)
    }

    @Test
    fun aCallAnsweredHere_isOnlyForwardedToJs() {
        val guarded = Regex(
            "if\\s*\\(\\s*SelectAnswerPolicy\\.answeredHere\\(connection\\?\\.callId,\\s*connection\\?\\.state,\\s*selectedCallId\\)\\s*\\)\\s*\\{" +
                "\\s*Log\\.i\\([^\\n]*\\)\\s*forwardToJs\\(data\\)\\s*return\\s*\\}",
        )
        assertTrue("an answered-here select_answer must forward to JS and return:\n$push", guarded.containsMatchIn(push))
    }
}
