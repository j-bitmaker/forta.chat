package com.forta.chat.plugins.calls

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [CallTeardown] against the real AudioManager on a connected device or
 * emulator (`./gradlew :app:connectedSideloadDebugAndroidTest`). Complements
 * [CallCleanupTest]: that suite proves stop()/forceStop() restore MODE_NORMAL;
 * this one proves the end-of-call hooks decide correctly when to call them.
 */
@RunWith(AndroidJUnit4::class)
class CallTeardownInstrumentedTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private var savedMode: Int = AudioManager.MODE_NORMAL

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedMode = audioManager.mode
        AudioRouter.resetForTests()
        AudioRouter.getSharedInstance(context).forceStop("test setup")
        CallConnectionService.currentConnection = null
    }

    @After
    fun teardown() {
        try { AudioRouter.getSharedInstance(context).forceStop("test teardown") } catch (_: Exception) {}
        try { audioManager.mode = savedMode } catch (_: Exception) {}
        AudioRouter.resetForTests()
    }

    @Test
    fun ensureCommunicationMode_setsTheModeAndOpensTheMarker_andStopAdoptsIt() {
        val router = AudioRouter.getSharedInstance(context)
        router.ensureCommunicationMode("test")
        Assert.assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        Assert.assertTrue(AudioRouter.hasOpenSessionMarker(context))
        Assert.assertFalse("ensure must not start routing", router.isRoutingActive())

        // The JS-side stopAudioRouting after a failed call setup.
        router.stop()
        Assert.assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        Assert.assertFalse(AudioRouter.hasOpenSessionMarker(context))
    }

    @Test
    fun endCall_forceStopsARouterTheJsFinalizeNeverReached() {
        val router = AudioRouter.getSharedInstance(context)
        router.start("voice")
        Assert.assertTrue(AudioRouter.hasOpenSessionMarker(context))

        CallTeardown.endCall(context, CallTeardownPolicy.Reason.DISCONNECT, "call-1")
        Assert.assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        Assert.assertFalse(router.isRoutingActive())
        Assert.assertFalse(AudioRouter.hasOpenSessionMarker(context))
    }

    @Test
    fun endCall_leavesAVoipModeThatIsNotOursAlone() {
        val router = AudioRouter.getSharedInstance(context)
        router.start("voice")
        router.stop()
        // Another app's call: same global mode, but our marker is closed.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        CallTeardown.endCall(context, CallTeardownPolicy.Reason.COLD_START, null)
        Assert.assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
    }

    @Test
    fun coldStart_closesAStaleMarker_evenWhenThereIsNothingToReset() {
        // ensureCommunicationMode() opened the marker, then the OS reset the
        // mode on its own before any stop() ran. The next process must not
        // carry that marker into somebody else's call.
        AudioRouter.getSharedInstance(context).ensureCommunicationMode("test")
        AudioRouter.resetForTests()
        audioManager.mode = AudioManager.MODE_NORMAL
        Assert.assertTrue(AudioRouter.hasOpenSessionMarker(context))

        CallTeardown.endCall(context, CallTeardownPolicy.Reason.COLD_START, null)
        Assert.assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        Assert.assertFalse(AudioRouter.hasOpenSessionMarker(context))
    }

    @Test
    fun stop_onAnInactiveRouter_closesTheMarkerTheEnsureLeftBehind() {
        val router = AudioRouter.getSharedInstance(context)
        router.ensureCommunicationMode("test")
        audioManager.mode = AudioManager.MODE_NORMAL // the OS reset it first
        Assert.assertTrue(AudioRouter.hasOpenSessionMarker(context))

        router.stop()
        Assert.assertFalse(AudioRouter.hasOpenSessionMarker(context))
    }

    @Test
    fun coldStart_recoversTheModeAProcessDeathLeftBehind() {
        AudioRouter.getSharedInstance(context).start("voice")
        // A fresh process: new router instance, inactive, the marker on disk
        // and the mode still set.
        AudioRouter.resetForTests()
        Assert.assertTrue(AudioRouter.hasOpenSessionMarker(context))
        Assert.assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)

        CallTeardown.endCall(context, CallTeardownPolicy.Reason.COLD_START, null)
        Assert.assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        Assert.assertFalse(AudioRouter.hasOpenSessionMarker(context))
    }
}
