package com.forta.chat.plugins.calls

import android.media.AudioManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NativeWebRTCManager.startLocalAudio` sets `MODE_IN_COMMUNICATION` directly,
 * before `AudioRouter.start()` runs, because several OEM firmwares mute the
 * microphone unless VoIP mode is established before the first capture. Nothing
 * owned the reset of that write: when call setup failed afterwards (or the JS
 * side never reached `startAudioRouting`) the router stayed inactive, and
 * `stop()` returned on its `isActive` guard with the device still in VoIP mode
 * — media volume broken until the next app resume woke the watchdog.
 */
class AudioRouterStrandedModeTest {

    @Test
    fun `an inactive router adopts a stranded VoIP mode`() {
        assertTrue(
            AudioRouter.shouldAdoptStrandedMode(
                isActive = false,
                currentMode = AudioManager.MODE_IN_COMMUNICATION,
            ),
        )
    }

    @Test
    fun `an inactive router leaves a normal mode alone`() {
        assertFalse(
            AudioRouter.shouldAdoptStrandedMode(
                isActive = false,
                currentMode = AudioManager.MODE_NORMAL,
            ),
        )
    }

    @Test
    fun `an inactive router never touches a cellular call`() {
        // MODE_IN_CALL belongs to the telephony stack. Resetting it would cut
        // the audio of a real phone call the user is on.
        assertFalse(
            AudioRouter.shouldAdoptStrandedMode(
                isActive = false,
                currentMode = AudioManager.MODE_IN_CALL,
            ),
        )
    }

    @Test
    fun `an inactive router never silences the system ringer`() {
        // MODE_RINGTONE is held by Telecom while something rings — possibly a
        // cellular call. Releasing it is CallConnectionService's job, and only
        // for our own connection.
        assertFalse(
            AudioRouter.shouldAdoptStrandedMode(
                isActive = false,
                currentMode = AudioManager.MODE_RINGTONE,
            ),
        )
    }

    @Test
    fun `an active router takes the normal stop path instead`() {
        // isActive means start() ran: the full teardown below the guard owns
        // the reset, and short-circuiting to forceStop would skip it.
        assertFalse(
            AudioRouter.shouldAdoptStrandedMode(
                isActive = true,
                currentMode = AudioManager.MODE_IN_COMMUNICATION,
            ),
        )
    }
}
