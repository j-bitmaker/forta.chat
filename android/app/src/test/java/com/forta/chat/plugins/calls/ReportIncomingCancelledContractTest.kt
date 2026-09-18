package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A call the caller already cancelled must not ring again from the JS path.
 *
 * Found on the Samsung 2026-09-18 (`dual0`, Forta in the background): the
 * hangup push tore the ringer down and marked the call in [CancelledCallStore],
 * then the invite push queued for the paused page reached JS 0.5 s later, and
 * `reportIncomingCall` registered the dead call with Telecom again — the phone
 * rang for another 30 s until the ring timeout. The push path already consults
 * the store; `reportIncomingCall` did not. Source-level, like
 * [IdleProcessExitContractTest]: Telecom cannot exist under JUnit, and the store
 * itself is pinned in [CancelledCallStoreTest].
 */
class ReportIncomingCancelledContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val plugin by lazy { source("com/forta/chat/plugins/calls/CallPlugin.kt") }

    @Test
    fun reportIncomingCall_skipsACallAlreadyCancelled_beforeTelecomHearsOfIt() {
        val body = withoutComments(functionBody(plugin, "fun\\s+reportIncomingCall\\s*\\("))
        val checked = body.indexOf("CancelledCallStore(context).isCancelled(callId)")
        val registered = body.indexOf("addNewIncomingCall(")
        assertTrue("reportIncomingCall must consult CancelledCallStore:\n$body", checked >= 0)
        assertTrue("…before registering the call with Telecom:\n$body", checked < registered)
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
