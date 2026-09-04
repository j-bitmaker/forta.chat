package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WEE-49: regression for post-call audio mode leak / cellular block (#839, #771).
 *
 * The original `onDestroy()` only released the wake-lock and abandoned audio
 * focus. When Android killed the service without going through `ACTION_STOP`
 * (OEM Doze / battery-saver / swipe-app-out / low-memory SIGKILL), the
 * AudioRouter stayed in `MODE_IN_COMMUNICATION` forever and the device's
 * cellular network was effectively blocked until reboot.
 *
 * Robolectric-free source-level assertion in the same spirit as
 * [AudioRouterModeReapplyTest] — exercising the real lifecycle would require
 * spinning up a Service host and a fake AudioManager, which is far more
 * brittle than the cleanup contract this regression cares about.
 */
class CallForegroundServiceDestroyContractTest {

    private val source: String by lazy {
        val candidates = listOf(
            "src/main/java/com/forta/chat/plugins/calls/CallForegroundService.kt",
            "android/app/src/main/java/com/forta/chat/plugins/calls/CallForegroundService.kt",
        )
        val resolved = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error(
                "CallForegroundService.kt not found. Tried: $candidates from ${File(".").absolutePath}",
            )
        resolved.readText()
    }

    @Test
    fun onDestroy_forceStopsAudioRouter_toRestoreAudioMode() {
        // forceStop() bypasses AudioRouter's `isActive` guard and brute-
        // force restores MODE_NORMAL — the only safe call from a destroy
        // path because the JS-side stopAudioRouting may never have fired.
        val onDestroyBlock = extractFunctionBody("onDestroy")
        assertTrue(
            "onDestroy() must call AudioRouter.forceStop() to recover from " +
                "OEM-killed service paths (WEE-49 / forta-bugs#839):\n$onDestroyBlock",
            onDestroyBlock.contains("AudioRouter.getSharedInstance(") &&
                onDestroyBlock.contains(".forceStop()"),
        )
    }

    @Test
    fun onDestroy_explicitlyStopsForeground_soNotificationDoesNotLinger() {
        val onDestroyBlock = extractFunctionBody("onDestroy")
        assertTrue(
            "onDestroy() must call stopForeground(STOP_FOREGROUND_REMOVE) so " +
                "the call notification disappears when the OS kills the service " +
                "without ACTION_STOP:\n$onDestroyBlock",
            onDestroyBlock.contains("stopForeground(STOP_FOREGROUND_REMOVE)"),
        )
    }

    @Test
    fun onDestroy_wrapsBruteResetInRunCatching_soOneFailureDoesNotSwallowTheNext() {
        val onDestroyBlock = extractFunctionBody("onDestroy")
        // Both brute-force calls must be wrapped — if forceStop throws and
        // stopForeground is not wrapped, the notification would leak forever.
        val runCatchingHits = Regex("runCatching\\s*\\{")
            .findAll(onDestroyBlock).count()
        assertTrue(
            "onDestroy() must wrap at least two brute-force steps in runCatching " +
                "(found $runCatchingHits):\n$onDestroyBlock",
            runCatchingHits >= 2,
        )
    }

    @Test
    fun onDestroy_clearsHasStartedFlag_soStaleUpdatesAreRejectedAfterRebirth() {
        val onDestroyBlock = extractFunctionBody("onDestroy")
        assertTrue(
            "onDestroy() must reset hasStarted=false so a future ACTION_UPDATE " +
                "arriving before the next ACTION_START is ignored " +
                "(WEE-45 protection survives a destroy/recreate cycle):\n$onDestroyBlock",
            onDestroyBlock.contains("hasStarted = false"),
        )
    }

    // -------------------------------------------------------------------------
    // WEE-54 / forta-bugs#839 (reopen) — swipe-out cellular block.
    //
    // onDestroy() hardening (WEE-49) only fired when the OS actually destroyed
    // the service. On swipe-app-out the OS delivers onTaskRemoved but can defer
    // onDestroy, leaving AudioRouter in MODE_IN_COMMUNICATION and cellular
    // blocked. onTaskRemoved must run the same brute-force audio cleanup.
    // -------------------------------------------------------------------------

    @Test
    fun onTaskRemoved_forceStopsAudioRouter_toUnblockCellularOnSwipeOut() {
        val block = extractFunctionBody("onTaskRemoved")
        assertTrue(
            "onTaskRemoved() must call AudioRouter.forceStop() so a swipe-out " +
                "during a call cannot leave MODE_IN_COMMUNICATION set and block " +
                "cellular (WEE-54 / forta-bugs#839 reopen):\n$block",
            block.contains("AudioRouter.getSharedInstance(") &&
                block.contains(".forceStop()"),
        )
    }

    @Test
    fun onTaskRemoved_stopsForegroundAndStopsSelf_soNoZombieServiceHoldsAudioMode() {
        val block = extractFunctionBody("onTaskRemoved")
        assertTrue(
            "onTaskRemoved() must stopForeground(STOP_FOREGROUND_REMOVE):\n$block",
            block.contains("stopForeground(STOP_FOREGROUND_REMOVE)"),
        )
        assertTrue(
            "onTaskRemoved() must stopSelf() so the OS finalises the service " +
                "instead of leaving a zombie record holding the audio mode:\n$block",
            block.contains("stopSelf()"),
        )
    }

    @Test
    fun onTaskRemoved_clearsInstance_soStaleIsRunningCannotResurrectVoipMode() {
        val block = extractFunctionBody("onTaskRemoved")
        // isRunning == (instance != null) is what the AudioRouter orphan
        // watchdog / CallActivity.onResume consult before restoring
        // MODE_IN_COMMUNICATION. onTaskRemoved tears audio down but the OS can
        // defer onDestroy — if instance stays non-null in that window a waking
        // surface re-applies comm mode and re-strands audio (#708 / #462).
        assertTrue(
            "onTaskRemoved() must set instance = null so isRunning matches the " +
                "audio teardown that already ran (WEE-54):\n$block",
            block.contains("instance = null"),
        )
    }

    @Test
    fun onTaskRemoved_wrapsBruteResetInRunCatching_soOneFailureDoesNotSwallowTheNext() {
        val block = extractFunctionBody("onTaskRemoved")
        val runCatchingHits = Regex("runCatching\\s*\\{")
            .findAll(block).count()
        assertTrue(
            "onTaskRemoved() must wrap at least two brute-force steps in " +
                "runCatching (found $runCatchingHits):\n$block",
            runCatchingHits >= 2,
        )
    }


    // -------------------------------------------------------------------------
    // forta-bugs#997 — "the microphone keeps being used by forta".
    //
    // Both teardown paths reset the audio *mode* but neither closed the WebRTC
    // capture path, so AudioRecord stayed held by the process after a swipe-out.
    // startLocalAudio early-returns while localAudioTrack != null, so the
    // orphaned track — bound to a dead PeerConnection — also poisoned the next
    // call. closeAllPeerConnections() → stopLocalMedia() disposes both.
    // -------------------------------------------------------------------------

    @Test
    fun onDestroy_releasesTheCapturePath_soTheMicrophoneIsFreed() {
        val block = extractFunctionBody("onDestroy")
        assertTrue(
            "onDestroy() must release the WebRTC capture path so the mic " +
                "indicator clears (forta-bugs#997):\n$block",
            block.contains("releaseMediaAsync("),
        )
    }

    @Test
    fun onTaskRemoved_releasesTheCapturePath_soSwipeOutFreesTheMicrophone() {
        val block = extractFunctionBody("onTaskRemoved")
        assertTrue(
            "onTaskRemoved() must release the WebRTC capture path — swipe-out " +
                "is the reported repro for the stuck mic (forta-bugs#997):\n$block",
            block.contains("releaseMediaAsync("),
        )
    }

    @Test
    fun captureTeardownIsDispatchedOffTheLifecycleThread() {
        // closeAllPeerConnections blocks on videoCapturer.stopCapture(), which
        // waits for the capture thread to stop — up to about a second on an old
        // camera HAL. Service lifecycle callbacks run on the main thread, so
        // calling it inline would trade a stuck mic for an ANR. Everywhere else
        // in the app this method is reached from Capacitor's plugin thread.
        val helper = extractPrivateFunctionBody("releaseMediaAsync")
        assertTrue(
            "releaseMediaAsync must hand the work to an executor, not run it " +
                "inline:\n$helper",
            helper.contains("mediaReleaseExecutor.execute"),
        )
        assertTrue(
            "releaseMediaAsync must be the thing that closes the peer " +
                "connections:\n$helper",
            helper.contains("closeAllPeerConnections()"),
        )
    }

    @Test
    fun audioModeResetStaysSynchronous_becauseItOutlivesTheProcess() {
        // The counterpart to the test above, and the reason the two are not
        // treated alike: the microphone is process-local, so the OS reclaims it
        // if the process dies before the worker runs. The audio mode is global
        // and survives process death — deferring it would reopen the stranded
        // MODE_IN_COMMUNICATION bug (WEE-49 / WEE-54).
        for (name in listOf("onDestroy", "onTaskRemoved")) {
            val block = extractFunctionBody(name)
            val forceStop = block.indexOf(".forceStop()")
            assertTrue("$name(): forceStop() not found:\n$block", forceStop >= 0)
            assertTrue(
                "$name() must call forceStop() directly, not from the media " +
                    "release worker:\n$block",
                !block.contains("mediaReleaseExecutor"),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Superseded-instance guard.
    //
    // stopSelf() -> onDestroy() is asynchronous and OEM ROMs defer it further,
    // so the OS can tear down a *previous* service instance after the next call
    // has already started a fresh one. The teardown is process-wide (audio mode
    // + PeerConnections are global), so an unguarded run from a dead instance
    // would silence the live call — and `instance = null` would blank the
    // liveness pointer that belongs to its successor.
    // -------------------------------------------------------------------------

    @Test
    fun teardown_skipsGlobalCleanupWhenANewerInstanceHasTakenOver() {
        for (name in listOf("onDestroy", "onTaskRemoved")) {
            val block = extractFunctionBody(name)
            val guard = block.indexOf("isSuperseded()")
            assertTrue(
                "$name() must bail out via isSuperseded() before running the " +
                    "process-wide teardown, or a deferred destroy will tear down " +
                    "the next call:\n$block",
                guard >= 0,
            )
            assertTrue(
                "$name()'s isSuperseded() check must come before forceStop():\n$block",
                guard < block.indexOf(".forceStop()"),
            )
            assertTrue(
                "$name()'s superseded branch must return early:\n$block",
                block.contains("return"),
            )
        }
    }

    @Test
    fun supersededCheck_comparesIdentity_notMereNullness() {
        // `instance != null` alone would make every teardown a no-op once a
        // successor exists *and* would skip cleanup for the last instance too;
        // identity (`!==`) is what distinguishes "I was replaced" from "I am
        // still the live service".
        val body = extractPrivateFunctionBody("isSuperseded")
        assertTrue(
            "isSuperseded() must compare instance identity with !== :\n$body",
            body.contains("!=="),
        )
    }

    /**
     * Extract the body of a top-level `override fun <name>(...)` block from the
     * Kotlin source. Brace-counts so nested blocks (if/try/runCatching) do not
     * end the match early.
     */
    private fun extractFunctionBody(name: String): String {
        val signature = Regex("override\\s+fun\\s+$name\\s*\\([^)]*\\)\\s*\\{")
        val match = signature.find(source)
            ?: error("Could not find override fun $name in source")
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

    /** Same as [extractFunctionBody] but for a non-`override` private fun. */
    private fun extractPrivateFunctionBody(name: String): String {
        val signature = Regex("private\\s+fun\\s+$name\\s*\\([^)]*\\)[^{]*\\{")
        val match = signature.find(source)
            ?: error("Could not find private fun $name in source")
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
