package com.forta.chat.plugins.calls

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Ends this process once a call is over and it has nothing left to present.
 *
 * Swiping a task from Recents while Telecom is bound to our ConnectionService
 * (a call ringing, or answered with JS dead) cannot kill the process on the
 * spot, so ActivityManager marks it "waiting to kill" instead. Nothing clears
 * that mark: it fires on the process's next scheduling-group change. On the
 * Samsung bench (2026-09-10) that was the `startActivity` of the next call
 * push, 3–14 ms after `Call invite:` and before Telecom or the ringer were
 * reached, so the call never rang. A process that ends itself when the call is
 * over takes the mark with it, and the next push starts a fresh process — the
 * way push calls arrive anyway. The rule is [IdleProcessExitPolicy].
 *
 * Every call-resource release schedules a check after a grace period. The
 * process ends only when no task has activities, Telecom holds no connection,
 * nothing rings, no service of this process is started or in the foreground
 * and no call push arrived in the last [IdleProcessExitPolicy.CALL_PUSH_HOLD_MS].
 * SharedPreferences are flushed first, so a marker the teardown
 * wrote with apply() still reaches disk.
 */
object IdleProcessExit {
    private const val TAG = "IdleProcessExit"

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastCallPushAtMs: Long? = null

    // Main thread only.
    private var attempt = 0
    private var reason = ""

    private val check = Runnable {
        // Nothing catches a throw out of a main-looper Runnable.
        runCatching { runCheck() }.onFailure { Log.w(TAG, "idle check threw", it) }
    }

    /** A call resource was released: look again after [IdleProcessExitPolicy.GRACE_MS]. Any thread. */
    fun schedule(context: Context, why: String) {
        appContext = context.applicationContext
        handler.post {
            reason = why
            attempt = 0
            handler.removeCallbacks(check)
            handler.postDelayed(check, IdleProcessExitPolicy.GRACE_MS)
        }
    }

    /** A call push is being handled; Telecom creates its connection only afterwards. Any thread. */
    fun noteCallPush() {
        lastCallPushAtMs = SystemClock.elapsedRealtime()
    }

    private fun runCheck() {
        val context = appContext ?: return
        act(context, snapshot(context))
    }

    private fun act(context: Context, snapshot: IdleProcessExitPolicy.Snapshot) {
        when (IdleProcessExitPolicy.decide(snapshot, attempt)) {
            IdleProcessExitPolicy.Decision.STAY ->
                Log.d(TAG, "staying after $reason (attempt $attempt): $snapshot")
            IdleProcessExitPolicy.Decision.RETRY -> {
                attempt++
                // A schedule() during the flush may have queued one already.
                handler.removeCallbacks(check)
                handler.postDelayed(check, IdleProcessExitPolicy.RETRY_MS)
            }
            IdleProcessExitPolicy.Decision.EXIT -> flushThenExit(context)
        }
    }

    private fun flushThenExit(context: Context) {
        // commit() waits for the disk; keep it off the main thread.
        Thread({
            flushPreferences(context)
            handler.post {
                runCatching { killIfStillIdle(context) }.onFailure { Log.w(TAG, "exit threw", it) }
            }
        }, "forta-idle-exit").start()
    }

    private fun killIfStillIdle(context: Context) {
        // A push or the user may have arrived while the flush ran.
        val snapshot = snapshot(context)
        if (IdleProcessExitPolicy.decide(snapshot, attempt) != IdleProcessExitPolicy.Decision.EXIT) {
            act(context, snapshot)
            return
        }
        Log.i(TAG, "Call over and nothing left to present — ending the process ($reason)")
        Process.killProcess(Process.myPid())
    }

    private fun snapshot(context: Context): IdleProcessExitPolicy.Snapshot {
        return IdleProcessExitPolicy.Snapshot(
            uiTaskRunning = hasRunningTask(context),
            hasConnection = CallConnectionService.currentConnection != null,
            ringerArmed = IncomingRinger.ringingCallId != null,
            busyServices = busyServices(context),
            recentCallPush = IdleProcessExitPolicy.isRecentCallPush(lastCallPushAtMs, SystemClock.elapsedRealtime()),
        )
    }

    /** Whether any task of this app still has an activity; when unsure, it does. */
    private fun hasRunningTask(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return true
        return runCatching {
            am.appTasks.any { task ->
                // taskInfo throws for a task removed between listing and reading.
                runCatching {
                    val info = task.taskInfo
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isRunning else info.numActivities > 0
                }.getOrDefault(false)
            }
        }.getOrDefault(true)
    }

    /** Services of this process that are started or in the foreground; when unsure, one. */
    @Suppress("DEPRECATION")
    private fun busyServices(context: Context): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 1
        val pid = Process.myPid()
        // Since Android 8 this lists only the caller's own services, all we need.
        return runCatching {
            am.getRunningServices(Int.MAX_VALUE).count { it.pid == pid && (it.started || it.foreground) }
        }.getOrDefault(1)
    }

    /**
     * Waits for every apply() still queued: a commit() on the same file queues
     * behind them and returns once they are on disk.
     */
    private fun flushPreferences(context: Context) {
        val names = File(context.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.map { it.name }
            ?.filter { it.endsWith(".xml") }
            ?.map { it.removeSuffix(".xml") }
            .orEmpty()
        names.forEach { name ->
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit() }
                .onFailure { Log.w(TAG, "flush of $name threw", it) }
        }
    }
}
