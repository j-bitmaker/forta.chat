package com.forta.chat.plugins.calls

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AudioRouter.ensureCommunicationMode` replaces the two direct
 * `MODE_IN_COMMUNICATION` writes that had no owner (`NativeWebRTCManager`
 * before the first capture, `CallActivity.onResume`). When it runs before
 * `start()` it arms a watchdog; these are that watchdog's pure predicates.
 */
class AudioRouterEnsureModeTest {

    @Test
    fun `VoIP mode for a call that never started routing is force-stopped once the call is gone`() {
        assertTrue(
            AudioRouter.shouldForceStopAfterEnsure(
                isRouterActive = false,
                currentMode = AudioManager.MODE_IN_COMMUNICATION,
                callAlive = false,
            ),
        )
    }

    @Test
    fun `a router that started owns the mode itself`() {
        assertFalse(
            AudioRouter.shouldForceStopAfterEnsure(
                isRouterActive = true,
                currentMode = AudioManager.MODE_IN_COMMUNICATION,
                callAlive = false,
            ),
        )
    }

    @Test
    fun `a live call is left alone`() {
        assertFalse(
            AudioRouter.shouldForceStopAfterEnsure(
                isRouterActive = false,
                currentMode = AudioManager.MODE_IN_COMMUNICATION,
                callAlive = true,
            ),
        )
    }

    @Test
    fun `only MODE_IN_COMMUNICATION is ever reset`() {
        for (mode in listOf(AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, null)) {
            assertFalse(
                "mode=$mode",
                AudioRouter.shouldForceStopAfterEnsure(isRouterActive = false, currentMode = mode, callAlive = false),
            )
        }
    }

    @Test
    fun `the watchdog rearms only while the call is alive and routing has not started`() {
        assertTrue(AudioRouter.shouldRearmEnsureWatchdog(isRouterActive = false, callAlive = true))
        assertFalse(AudioRouter.shouldRearmEnsureWatchdog(isRouterActive = true, callAlive = true))
        assertFalse(AudioRouter.shouldRearmEnsureWatchdog(isRouterActive = false, callAlive = false))
    }
}
