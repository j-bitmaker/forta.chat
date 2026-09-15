package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JS reporting that a call ended takes down only that call's ring and screen.
 *
 * `reportCallEnded` dismissed whatever rang, although JS always names the
 * call (`finalize-call.ts` → `nativeCallBridge.reportCallEnded(callId)`). A
 * finalize for a stale or refused invite then silenced the call the user was
 * looking at. The dismissal still runs before the slot work and regardless of
 * the slot, because when /sync beats FCM to a hangup this is the only path
 * that takes the ringer down. Source-level, like [CallSlotContractTest]: the
 * plugin cannot run under JUnit.
 */
class ReportCallEndedContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val reportCallEnded by lazy {
        val plugin = withoutComments(source("com/forta/chat/plugins/calls/CallPlugin.kt"))
        functionBody(plugin, "fun\\s+reportCallEnded\\s*\\(")
    }

    @Test
    fun theRingAndScreen_areDismissedOnlyForTheReportedCall() {
        val resolved = reportCallEnded.indexOf("val callId = call.getString(\"callId\")")
        val dismissed = reportCallEnded.indexOf("IncomingCallActivity.dismissIfShowing(callId)")
        assertTrue("the dismissal must carry the reported call:\n$reportCallEnded", dismissed >= 0)
        assertTrue("callId must be read before the dismissal:\n$reportCallEnded", resolved in 0 until dismissed)
    }

    @Test
    fun theDismissal_stillPrecedesTheSlotWork() {
        val dismissed = reportCallEnded.indexOf("IncomingCallActivity.dismissIfShowing(")
        val slot = reportCallEnded.indexOf("CallConnectionService.currentConnection")
        assertTrue("the dismissal must not wait on the slot:\n$reportCallEnded", dismissed in 0 until slot)
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
