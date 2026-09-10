package com.forta.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [WebViewRecoveryPolicy] only decides; this pins the wiring that makes the
 * decision reachable. Both halves are load-bearing and neither is visible to a
 * JVM unit test: a listener registered after `super.onCreate` is never consulted
 * (the Bridge, and with it the WebViewClient holding the listener list, is built
 * there), and a listener that answers `false` hands the process back to Android —
 * which is precisely the pre-fix behaviour.
 */
class WebViewRecoveryContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            "src/main/java/com/forta/chat/MainActivity.kt",
            "android/app/src/main/java/com/forta/chat/MainActivity.kt",
        )
        val resolved = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("MainActivity.kt not found. Tried: $candidates from ${File(".").absolutePath}")
        resolved.readText()
    }

    @Test
    fun theListenerIsRegisteredBeforeSuperOnCreate_orItIsNeverConsulted() {
        val body = extractFunctionBody("onCreate")
        val registerAt = body.indexOf("bridgeBuilder.addWebViewListener(")
        val superAt = body.indexOf("super.onCreate(")
        assertTrue(
            "MainActivity.onCreate must register the render-process listener on " +
                "bridgeBuilder; Capacitor builds the Bridge inside super.onCreate " +
                "and never re-reads the builder afterwards:\n$body",
            registerAt >= 0,
        )
        assertTrue(
            "addWebViewListener must come BEFORE super.onCreate " +
                "(register=$registerAt, super=$superAt):\n$body",
            superAt >= 0 && registerAt < superAt,
        )
    }

    @Test
    fun aStaleWebViewKeepsTheProcessAlive_ratherThanKillingIt() {
        val body = branch("DISCARD_STALE")
        assertTrue(
            "onRenderProcessGone must answer DISCARD_STALE with true — returning " +
                "false there kills a process that is already serving a new " +
                "activity, which is the bug this fixes:\n$body",
            body.trim().endsWith("true"),
        )
    }

    @Test
    fun onlyTheLoopGuardEverHandsTheProcessBackToAndroid() {
        assertTrue(
            "the loop guard is the only branch allowed to answer false — every " +
                "other false hands Android a process that is serving a live " +
                "activity:\n" + branch("LET_SYSTEM_KILL"),
            branch("LET_SYSTEM_KILL").trim().endsWith("false"),
        )
        for (name in listOf("RECREATE_ACTIVITY", "DISCARD_STALE")) {
            assertTrue(
                "$name must answer true:\n" + branch(name),
                branch(name).trim().endsWith("true"),
            )
        }
    }

    @Test
    fun theRecreateBranchLeavesTheDestroyToCapacitor() {
        // Bridge.onDetachedFromWindow() calls webView.destroy() with no try/catch
        // of its own, and recreate() always routes through it. Destroying first
        // makes that a second destroy, and every plugin notifyListeners in the
        // teardown window would post into an already-destroyed view — Capacitor's
        // legacy reply path does that outside its try/catch.
        val body = branch("RECREATE_ACTIVITY")
        assertTrue(
            "the recreate branch must NOT destroy the WebView itself:\n$body",
            !body.contains("discardDeadWebView("),
        )
        assertTrue(
            "it must still cancel our own queued re-inject:\n$body",
            body.contains("removeCallbacks(reinjectAll)"),
        )
    }

    @Test
    fun theBranchesNobodyElseCleansUpAfter_discardTheWebView() {
        for (name in listOf("DISCARD_STALE", "LET_SYSTEM_KILL")) {
            val body = branch(name)
            assertTrue(
                "$name has no activity teardown coming, so it must discard the " +
                    "dead WebView itself:\n$body",
                body.contains("discardDeadWebView(webView)"),
            )
        }
    }

    @Test
    fun pendingWorkIsCancelledBeforeTheWebViewIsDestroyed() {
        val body = extractPrivateFunctionBody("discardDeadWebView")
        val removeAt = body.indexOf("removeCallbacks(reinjectAll)")
        val destroyAt = body.indexOf("destroy()")
        assertTrue(
            "discardDeadWebView must cancel the queued re-inject before destroying " +
                "the view; injectAllCssVars leaves one on a 500 ms delay and it " +
                "would land on a destroyed WebView " +
                "(remove=$removeAt, destroy=$destroyAt):\n$body",
            removeAt >= 0 && destroyAt >= 0 && removeAt < destroyAt,
        )
    }

    @Test
    fun theRecoveryStampIsProcessScoped_soRecreateCannotResetItsOwnGuard() {
        assertTrue(
            "lastRecoveryAtMs must live in the companion object: an instance " +
                "field is wiped by the very recreate() it rate-limits:\n$source",
            Regex("companion object\\s*\\{[^}]*lastRecoveryAtMs", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(source),
        )
    }

    /** The body of one `RenderProcessRecovery.X -> { ... }` arm. */
    private fun branch(name: String): String {
        val body = extractOverrideBody("onRenderProcessGone")
        val arm = Regex("RenderProcessRecovery\\.$name\\s*->\\s*\\{").find(body)
            ?: error("Could not find the $name arm")
        var depth = 1
        var i = arm.range.last + 1
        val start = i
        while (i < body.length && depth > 0) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return body.substring(start, i - 1)
    }

    private fun extractPrivateFunctionBody(name: String): String = extract(
        Regex("private\\s+fun\\s+$name\\s*\\([^)]*\\)[^{]*\\{"),
        name,
    )

    private fun extractFunctionBody(name: String): String = extract(
        Regex("override\\s+fun\\s+$name\\s*\\([^)]*\\)[^{]*\\{"),
        name,
    )

    /** Multi-line signatures need a laxer opener than [extractFunctionBody]. */
    private fun extractOverrideBody(name: String): String = extract(
        Regex("override\\s+fun\\s+$name\\s*\\(", RegexOption.DOT_MATCHES_ALL),
        name,
        skipToBrace = true,
    )

    private fun extract(signature: Regex, name: String, skipToBrace: Boolean = false): String {
        val match = signature.find(source) ?: error("Could not find fun $name in source")
        var i = match.range.last + 1
        if (skipToBrace) {
            while (i < source.length && source[i] != '{') i++
            i++
        }
        var depth = 1
        val start = i
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(start, i - 1)
    }
}
