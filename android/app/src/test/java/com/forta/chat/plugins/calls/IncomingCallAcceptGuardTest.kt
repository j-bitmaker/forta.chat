package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "Accept button does nothing" cluster includes reports where the app
 * disappears on the tap. onCreate's steps are wrapped by [CallCrashGuard.
 * safeStep], but that guard *rethrows* — it exists so the activity's own outer
 * catch can finish gracefully. A click listener has no outer frame: anything
 * escaping it reaches the looper and kills the process while the user's finger
 * is still on the button.
 *
 * The reachable throw is Telecom's: a state transition on a connection it has
 * already destroyed. [CallConnection.armRingTimeout] posts its auto-reject to
 * the same main looper the tap posts to, so a tap landing just after the
 * deadline hits exactly that.
 *
 * Source-level assertions, in the same spirit as
 * [CallForegroundServiceDestroyContractTest] — driving the real Activity would
 * need a Telecom + window shadow stack far more brittle than the contract.
 */
class IncomingCallAcceptGuardTest {

    private fun read(name: String): String {
        val candidates = listOf(
            "src/main/java/com/forta/chat/plugins/calls/$name",
            "android/app/src/main/java/com/forta/chat/plugins/calls/$name",
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$name not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val activity: String by lazy { read("IncomingCallActivity.kt") }
    private val connectionService: String by lazy { read("CallConnectionService.kt") }

    @Test
    fun `the accept listener cannot let a throw reach the looper`() {
        assertTrue(
            "btn_accept's click listener must run through the swallowing guard " +
                "(tryStep), not bare — a throw there kills the process mid-answer:\n" +
                activity.substringAfter("btn_accept").take(200),
            Regex("btn_accept\\)\\?\\.setOnClickListener\\s*\\{\\s*\\n\\s*tryStep\\(")
                .containsMatchIn(activity),
        )
    }

    @Test
    fun `the decline listener cannot let a throw reach the looper`() {
        assertTrue(
            "btn_decline's click listener must run through the swallowing guard:\n" +
                activity.substringAfter("btn_decline").take(200),
            Regex("btn_decline\\)\\?\\.setOnClickListener\\s*\\{\\s*\\n\\s*tryStep\\(")
                .containsMatchIn(activity),
        )
    }

    @Test
    fun `the activity guard used by listeners swallows instead of rethrowing`() {
        // safeStep and tryStep differ only in that one rethrows. Wrapping a
        // listener in the rethrowing one would look correct and change nothing.
        assertTrue(
            "IncomingCallActivity must define a tryStep wrapper over " +
                "CallCrashGuard.tryStep for post-onCreate work",
            Regex("private inline fun tryStep\\([^)]*\\)[^{]*\\{\\s*\\n\\s*CallCrashGuard\\.tryStep\\(")
                .containsMatchIn(activity),
        )
    }

    @Test
    fun `the Telecom handoff is contained so the answer still reaches JS`() {
        // If onAnswer throws, the pending-answer markers and the MainActivity
        // launch below it are what still get the call answered.
        assertTrue(
            "accept() must contain connection.onAnswer() in a swallowing guard",
            activity.contains("tryStep(\"connection.onAnswer\")"),
        )
        assertTrue(
            "decline() must contain connection.onReject() in a swallowing guard",
            activity.contains("tryStep(\"connection.onReject\")"),
        )
    }

    @Test
    fun `onAnswer refuses to transition a connection Telecom already destroyed`() {
        val body = extractOverrideBody(connectionService, "onAnswer")
        val guard = body.indexOf("released.get()")
        assertTrue(
            "onAnswer() must early-return when already released, like " +
                "onReject/onDisconnect do — setActive() on a destroyed " +
                "connection throws:\n$body",
            guard >= 0,
        )
        assertTrue(
            "the released guard must come before setActive():\n$body",
            guard < body.indexOf("setActive()"),
        )
    }

    @Test
    fun `the release latch is atomic because two threads race for it`() {
        // The ring backstop fires on the main looper; the stale-ring sweep
        // reaches onReject from Capacitor's plugin thread. Both are keyed to
        // the same 45 s deadline, so a plain check-then-set lets both pass and
        // transition an already-destroyed connection — which Telecom answers
        // with a throw, on an unattended timer.
        assertTrue(
            "CallConnection.released must be an AtomicBoolean",
            connectionService.contains("private val released = AtomicBoolean(false)"),
        )
        assertTrue(
            "onReject/onDisconnect must latch with compareAndSet, not a plain read",
            Regex("released\\.compareAndSet\\(false, true\\)")
                .findAll(connectionService).count() >= 2,
        )
    }

    @Test
    fun `the ring timeout runnable cannot throw into the looper`() {
        // Nothing catches a throw out of a main-looper Runnable: it kills the
        // process, and this one fires while the phone sits unattended.
        val runnable = connectionService.substringAfter("private val ringTimeoutRunnable")
            .take(500)
        assertTrue(
            "ringTimeoutRunnable must wrap onReject() in a catch:\n$runnable",
            runnable.contains("runCatching { onReject() }"),
        )
    }

    private fun extractOverrideBody(source: String, name: String): String {
        val match = Regex("override\\s+fun\\s+$name\\s*\\([^)]*\\)\\s*\\{").find(source)
            ?: error("Could not find override fun $name")
        var depth = 1
        var i = match.range.last + 1
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
