package com.forta.chat.plugins.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The one owner of the incoming-call ringtone, vibration and the 30-second
 * no-answer deadline, keyed by callId — see [IncomingRingerLedger] for why it
 * is not the activity.
 *
 * Every answer route stops it: the activity's Accept button, Telecom's
 * `onAnswer` (headset, Android Auto, the system call UI), the JS-side
 * `reportCallConnected`, and [CallTeardown] for a call that ended. A stop
 * keyed to another call is a no-op, so the second incoming call that
 * displaced the first cannot be silenced by the first one's teardown.
 */
object IncomingRinger {
    private const val TAG = "IncomingRinger"
    const val AUTO_REJECT_TIMEOUT_MS = 30_000L

    private val ledger = IncomingRingerLedger()
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** The call this process rings for right now, or null. */
    val ringingCallId: String?
        get() = ledger.armedCallId

    /**
     * Ring for [callId]; a previous ring is replaced. [onTimeout] runs on
     * the main thread after [AUTO_REJECT_TIMEOUT_MS] unless [stop] or another
     * [arm] intervened — an answered call never sees the auto-reject.
     */
    fun arm(context: Context, callId: String, onTimeout: () -> Unit) {
        val app = context.applicationContext
        val token = synchronized(this) {
            stopHardware()
            val t = ledger.arm(callId)
            runCatching { startRingtone(app) }.onFailure { Log.w(TAG, "ringtone failed", it) }
            runCatching { startVibration(app) }.onFailure { Log.w(TAG, "vibration failed", it) }
            t
        }
        handler.postDelayed({
            if (!ledger.mayFire(token)) return@postDelayed
            Log.w(TAG, "no answer in ${AUTO_REJECT_TIMEOUT_MS / 1000}s for $callId — auto-rejecting")
            // Nothing catches a throw out of a main-looper Runnable.
            runCatching(onTimeout).onFailure { Log.e(TAG, "auto-reject threw", it) }
        }, AUTO_REJECT_TIMEOUT_MS)
        Log.d(TAG, "arm callId=$callId")
    }

    /** Stop the ring for [callId]; returns whether it was ringing for that call. */
    fun stop(callId: String?): Boolean = synchronized(this) {
        val wasRinging = ledger.stop(callId)
        if (wasRinging) {
            stopHardware()
            Log.d(TAG, "stop callId=$callId")
        }
        wasRinging
    }

    /** Stop whatever rings: the call was answered or connected, whichever id it carried. */
    fun stopAll(): Boolean = stop(null)

    fun isRingingFor(callId: String): Boolean = ledger.isArmedFor(callId)

    private fun stopHardware() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun startRingtone(app: Context) {
        ensureRingerAudible(app)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            isLooping = true
            play()
        }
    }

    private fun startVibration(app: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
    }

    /**
     * WEE-54 / forta-bugs#862: bump STREAM_RING when an OEM (MIUI / HyperOS)
     * has left it muted while the phone is in normal ringer mode, otherwise
     * the system ringtone plays inaudibly and only the vibration is felt.
     * Silent / vibrate ringer modes are respected (no-op) — see
     * [CallNotificationConfig.ringVolumeToForce]. Best-effort: any failure
     * (locked stream on hardened ROMs) is swallowed; vibration still fires.
     */
    private fun ensureRingerAudible(app: Context) {
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val target = CallNotificationConfig.ringVolumeToForce(
                ringerMode = am.ringerMode,
                currentVolume = am.getStreamVolume(AudioManager.STREAM_RING),
                maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_RING),
            ) ?: return
            am.setStreamVolume(AudioManager.STREAM_RING, target, 0)
        } catch (e: Exception) {
            Log.w(TAG, "ensureRingerAudible failed", e)
        }
    }
}
