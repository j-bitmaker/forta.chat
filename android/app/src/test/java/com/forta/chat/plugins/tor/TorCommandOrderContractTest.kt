package com.forta.chat.plugins.tor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tor starts and stops must apply in the order JS asked for them.
 *
 * Found on the Samsung 2026-09-15 (`tor-toast1`): JS switched the bridge off and, 72 ms
 * later, set the mode to «Никогда». `configure` ran each request on a thread of its own.
 * Both threads waited for the old Tor to exit, then the earlier restart went on and started
 * Tor again (`torrc written mode=ALWAYS bridge=NONE`) and saved ALWAYS over the NEVER the
 * later request had saved. The app showed «Никогда» while Tor bootstrapped and the route
 * decider read ALWAYS.
 *
 * A queue alone is not enough: `startTor` handed the process launch to a worker thread, so a
 * stop queued right behind a start could run before the process existed and miss it. The
 * reverse proxy started the same way. And a "Bootstrapped 100%" line, or the exit, of a Tor
 * that was stopped can reach the manager after the stop or after the next start began.
 *
 * Source-level, like the call contract tests: the plugin needs a Capacitor bridge and the
 * manager an Android context, and `android.util.Log` is not mocked in these unit tests.
 */
class TorCommandOrderContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val plugin by lazy { source("com/forta/chat/plugins/tor/TorPlugin.kt") }
    private val manager by lazy { source("com/forta/chat/plugins/tor/TorManager.kt") }
    private val runner by lazy { source("com/forta/chat/plugins/tor/ProcessRunner.kt") }

    @Test
    fun thePlugin_hasOneSerialQueueForTorCommands() {
        assertTrue(
            "TorPlugin must keep one single-thread executor named torCommands",
            Regex("val\\s+torCommands\\s*=\\s*Executors\\.newSingleThreadExecutor").containsMatchIn(plugin),
        )
    }

    @Test
    fun configure_appliesThroughTheQueue_notOnAThreadOfItsOwn() {
        val body = withoutComments(functionBody(plugin, "fun\\s+configure\\s*\\("))
        assertNoOwnThread("configure", body)
        val queued = queuedBlocks(body).joinToString("\n")
        assertTrue("configure must restart Tor inside torCommands.execute:\n$body", queued.contains("torManager.restartTor("))
        assertTrue("configure must stop Tor inside torCommands.execute:\n$body", queued.contains("torManager.stopTor()"))
    }

    @Test
    fun stopDaemon_stopsThroughTheQueue() {
        val body = withoutComments(functionBody(plugin, "fun\\s+stopDaemon\\s*\\("))
        assertNoOwnThread("stopDaemon", body)
        assertTrue(
            "stopDaemon must stop Tor inside torCommands.execute:\n$body",
            queuedBlocks(body).any { it.contains("torManager.stopTor()") },
        )
    }

    @Test
    fun startDaemon_startsAndStopsThroughTheQueue() {
        val body = withoutComments(functionBody(plugin, "fun\\s+startDaemon\\s*\\("))
        val queued = queuedBlocks(body)
        assertTrue("startDaemon must start Tor inside torCommands.execute:\n$body", queued.any { it.contains("torManager.startTor(") })
        assertTrue("startDaemon must stop Tor inside torCommands.execute:\n$body", queued.any { it.contains("torManager.stopTor()") })
        assertTrue(
            "startDaemon may not start or stop Tor outside the queue:\n$body",
            occurrences(body, "torManager.startTor(") == queued.sumOf { occurrences(it, "torManager.startTor(") } &&
                occurrences(body, "torManager.stopTor()") == queued.sumOf { occurrences(it, "torManager.stopTor()") },
        )
    }

    @Test
    fun clearTorCache_stopsThroughTheQueue() {
        val body = withoutComments(functionBody(plugin, "fun\\s+clearTorCache\\s*\\("))
        assertNoOwnThread("clearTorCache", body)
        assertTrue(
            "clearTorCache must stop Tor inside torCommands.execute:\n$body",
            queuedBlocks(body).any { it.contains("torManager.stopTor()") },
        )
    }

    @Test
    fun everyQueuedCommand_catchesAndRejects() {
        for (name in listOf("startDaemon", "stopDaemon", "configure", "clearTorCache")) {
            val body = withoutComments(functionBody(plugin, "fun\\s+$name\\s*\\("))
            for (block in queuedBlocks(body)) {
                assertTrue(
                    "$name: nothing catches an exception on the torCommands thread and it ends the app, " +
                        "so the block must start with try and reject the call:\n$block",
                    block.trim().startsWith("try") && block.contains("call.reject("),
                )
            }
        }
    }

    @Test
    fun startTor_launchesTheProcessBeforeItReturns() {
        val body = withoutComments(functionBody(manager, "fun\\s+startTor\\s*\\("))
        val launch = body.indexOf("torRunner.launch(")
        val waiter = body.indexOf("Thread(")
        assertTrue("startTor must launch the Tor process with torRunner.launch(:\n$body", launch >= 0)
        assertFalse("startTor must not start and wait on a worker thread:\n$body", body.contains("torRunner.start("))
        assertTrue("the launch must come before the thread that waits for the exit:\n$body", waiter < 0 || launch < waiter)
    }

    @Test
    fun everyStart_takesANewGeneration_underTheLock() {
        val body = withoutComments(functionBody(manager, "fun\\s+startTor\\s*\\("))
        assertTrue(
            "startTor must set STARTING and take ++startGeneration in the same lock.withLock:\n$body",
            lockedBlocks(body).any { it.contains("setState(TorState.STARTING)") && it.contains("++startGeneration") },
        )
    }

    @Test
    fun anExitedProcess_stopsOnlyItsOwnStart_underTheLock() {
        val body = withoutComments(functionBody(manager, "fun\\s+startTor\\s*\\("))
        val exit = lockedBlocks(body).firstOrNull { it.contains("setState(TorState.STOPPED)") }
            ?: error("the exit handler must set STOPPED inside lock.withLock:\n$body")
        assertTrue(
            "the exit handler must set STOPPED only while its start is the latest one and Tor is starting or running, " +
                "not during a stop or after the next start:\n$exit",
            Regex(
                "if\\s*\\(\\s*generation\\s*==\\s*startGeneration\\s*&&\\s*" +
                    "\\(\\s*current\\s*==\\s*TorState\\.STARTING\\s*\\|\\|\\s*current\\s*==\\s*TorState\\.RUNNING\\s*\\)\\s*\\)" +
                    "\\s*\\{\\s*setState\\(TorState\\.STOPPED\\)",
            ).containsMatchIn(exit),
        )
    }

    @Test
    fun aBootstrapLine_countsOnlyForTheStartInProgress_underTheLock() {
        val body = withoutComments(functionBody(manager, "fun\\s+handleBootstrapLine\\s*\\("))
        val locked = lockedBlocks(body).joinToString("\n")
        for (write in listOf("setState(", "bootstrapPercent.set(", "startReverseProxy()")) {
            assertTrue(
                "$write must run inside lock.withLock, where a stop or a new start cannot slip in between:\n$body",
                occurrences(locked, write) > 0 && occurrences(body, write) == occurrences(locked, write),
            )
        }
        assertTrue(
            "a line from an older start, or read while Tor is not STARTING, must change nothing:\n$body",
            Regex(
                "if\\s*\\(\\s*generation\\s*!=\\s*startGeneration\\s*\\|\\|\\s*state\\.get\\(\\)\\s*!=\\s*TorState\\.STARTING\\s*\\)\\s*return\\b",
            ).containsMatchIn(locked),
        )
        val proxyCheck = Regex("if\\s*\\(\\s*!\\s*startReverseProxy\\(\\)\\s*\\)\\s*\\{[^}]*\\breturn\\b").find(locked)
        assertTrue(
            "Tor may turn RUNNING only after the reverse proxy started:\n$body",
            proxyCheck != null && proxyCheck.range.last < locked.indexOf("setState(TorState.RUNNING)"),
        )
    }

    @Test
    fun startReverseProxy_launchesTheProxyBeforeItReturns_andReportsAFailure() {
        assertTrue(
            "startReverseProxy must return whether the proxy started",
            Regex("fun\\s+startReverseProxy\\s*\\(\\s*\\)\\s*:\\s*Boolean").containsMatchIn(manager),
        )
        val body = withoutComments(functionBody(manager, "fun\\s+startReverseProxy\\s*\\("))
        val launch = body.indexOf("proxyRunner.launch(")
        val waiter = body.indexOf("Thread(")
        assertTrue("startReverseProxy must launch the proxy with proxyRunner.launch(:\n$body", launch >= 0)
        assertFalse("startReverseProxy must not start and wait on a worker thread:\n$body", body.contains("proxyRunner.start("))
        assertTrue("the launch must come before the thread that waits for the exit:\n$body", waiter < 0 || launch < waiter)
        assertTrue(
            "a failed launch must return false:\n$body",
            Regex("catch\\s*\\([^)]*\\)\\s*\\{[^}]*\\breturn\\s+false\\b").containsMatchIn(body),
        )
    }

    @Test
    fun theRunner_launchesWithoutWaiting() {
        val launch = withoutComments(functionBody(runner, "fun\\s+launch\\s*\\("))
        assertTrue("launch must create the process:\n$launch", launch.contains("pb.start()"))
        assertFalse("launch must return without waiting for the exit:\n$launch", launch.contains("waitFor("))
    }

    private fun assertNoOwnThread(name: String, body: String) {
        assertFalse("$name must not start a thread of its own:\n$body", Regex("\\bThread\\s*[({]").containsMatchIn(body))
    }

    private fun occurrences(text: String, needle: String): Int = text.windowed(needle.length).count { it == needle }

    /** Bodies of every `torCommands.execute { … }` block in [body]. */
    private fun queuedBlocks(body: String): List<String> = blocksOpenedBy(body, "torCommands\\.execute\\s*\\{")

    /** Bodies of every `lock.withLock { … }` block in [body]. */
    private fun lockedBlocks(body: String): List<String> = blocksOpenedBy(body, "\\block\\.withLock\\s*\\{")

    private fun blocksOpenedBy(body: String, opener: String): List<String> =
        Regex(opener).findAll(body).map { match ->
            var depth = 1
            var i = match.range.last + 1
            val start = i
            while (i < body.length && depth > 0) {
                when (body[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            body.substring(start, i - 1)
        }.toList()

    /** Drops `//` comment tails so an assertion measures code, not prose. */
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
