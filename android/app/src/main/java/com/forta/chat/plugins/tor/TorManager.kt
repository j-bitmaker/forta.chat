package com.forta.chat.plugins.tor

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class TorState { STOPPED, STARTING, RUNNING, STOPPING }

class TorManager(private val config: ConfigurationManager) {

    private val TAG = "TorManager"
    private val lock = ReentrantLock()
    private val state = AtomicReference(TorState.STOPPED)
    private val bootstrapPercent = AtomicInteger(0)
    private var currentMode: TorMode = TorMode.NEVER
    private var currentBridgeType: BridgeType = BridgeType.NONE

    // Taken anew by every start, under [lock]. A Tor process's output and exit count only while
    // its start is the latest: a stopped Tor's last lines or late exit can reach us after the
    // next start began.
    private var startGeneration = 0

    private val torRunner = ProcessRunner(tag = "Tor")
    private val proxyRunner = ProcessRunner(tag = "ReverseProxy")
    private var torThread: Thread? = null
    private var proxyThread: Thread? = null

    var onBootstrapProgress: ((Int) -> Unit)? = null
    var onStateChanged: ((TorState) -> Unit)? = null

    val currentState: TorState get() = state.get()
    val currentBootstrap: Int get() = bootstrapPercent.get()
    val isReady: Boolean get() = state.get() == TorState.RUNNING
    val mode: TorMode get() = currentMode
    val bridgeType: BridgeType get() = currentBridgeType

    init {
        val saved = config.loadSettings()
        currentMode = saved.mode
        currentBridgeType = saved.bridgeType
    }

    /** Persist user settings without starting/stopping the daemon. */
    fun persistSettings(mode: TorMode, bridgeType: BridgeType = currentBridgeType) {
        currentMode = mode
        currentBridgeType = bridgeType
        config.saveSettings(mode, bridgeType)
    }

    fun startTor(
        mode: TorMode = TorMode.ALWAYS,
        bridgeType: BridgeType = BridgeType.NONE,
        customBridges: List<String> = emptyList()
    ) {
        val bootStart = android.os.SystemClock.elapsedRealtime()
        fun elapsed() = android.os.SystemClock.elapsedRealtime() - bootStart

        val generation = lock.withLock {
            if (state.get() != TorState.STOPPED) {
                Log.w(TAG, "Tor already ${state.get()}, ignoring start")
                return
            }
            persistSettings(mode, bridgeType)
            setState(TorState.STARTING)
            bootstrapPercent.set(0)
            ++startGeneration
        }

        config.ensureGeoIPFiles()
        Log.i(TAG, "[BOOT] T+${elapsed()}ms geoip files ready")

        // Pre-check: remove stale lock file from previous crash
        val lockFile = java.io.File(config.torDataDir, "lock")
        if (lockFile.exists()) {
            Log.w(TAG, "[BOOT] Removing stale Tor lock file")
            lockFile.delete()
        }

        // Pre-check: verify SOCKS port is free
        try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("127.0.0.1", config.torDefaultSocksPort), 500)
            socket.close()
            Log.w(TAG, "[BOOT] SOCKS port ${config.torDefaultSocksPort} in use — killing stale process")
            val pidFile = java.io.File(config.torPidPath)
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim().toIntOrNull()
                if (pid != null) {
                    try { Runtime.getRuntime().exec(arrayOf("kill", "-9", pid.toString())) } catch (_: Exception) {}
                }
                pidFile.delete()
            }
            Thread.sleep(1000)
        } catch (_: Exception) {
            // Port is free — good
        }

        val torrc = config.generateTorrc(mode, bridgeType, customBridges)
        File(config.torConfPath).apply {
            parentFile?.mkdirs()
            writeText(torrc)
        }
        Log.i(
            TAG,
            "[BOOT] T+${elapsed()}ms torrc written mode=$mode bridge=$bridgeType " +
                "snowflakeBinExists=${File(config.snowflakePath).exists()}",
        )

        File(config.torPath).setExecutable(true)
        File(config.reverseProxyPath).setExecutable(true)
        if (bridgeType == BridgeType.SNOWFLAKE) {
            File(config.snowflakePath).setExecutable(true)
        }

        val bootstrapListener = object : ProcessRunner.OutputListener {
            override fun onStdOutput(line: String) = handleBootstrapLine(line, generation, ::elapsed)
            override fun onErrOutput(line: String) = handleBootstrapLine(line, generation, ::elapsed)
        }

        // Launch here, not on the waiting thread: the process must exist when startTor
        // returns, or a stop queued right behind this start finds nothing to stop.
        val proc = try {
            torRunner.launch(
                binaryPath = config.torPath,
                args = listOf("-f", config.torConfPath, "--pidfile", config.torPidPath),
                env = mapOf("LD_LIBRARY_PATH" to config.nativeLibPath),
                listener = bootstrapListener,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Tor process", e)
            setState(TorState.STOPPED)
            return
        }

        torThread = Thread({
            val exitCode = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            Log.d(TAG, "Tor process exited with code $exitCode")
            // A stop sets STOPPING before it ends the process, and after a restart the state
            // belongs to the next start.
            lock.withLock {
                val current = state.get()
                if (generation == startGeneration && (current == TorState.STARTING || current == TorState.RUNNING)) {
                    setState(TorState.STOPPED)
                }
            }
        }, "TorThread").also { it.isDaemon = true; it.start() }
    }

    private fun handleBootstrapLine(line: String, generation: Int, elapsed: () -> Long) {
        val pct = ProcessRunner.parseBootstrapPercent(line) ?: return
        Log.i(TAG, "[BOOT] T+${elapsed()}ms Bootstrap $pct%")
        // Under the stop's lock, and only for the start in progress: a line a stopped Tor
        // printed last must not bring RUNNING back or start a proxy nobody stops.
        lock.withLock {
            if (generation != startGeneration || state.get() != TorState.STARTING) return
            bootstrapPercent.set(pct)
            onBootstrapProgress?.invoke(pct)
            if (pct < 100) return
            Log.i(TAG, "[BOOT] T+${elapsed()}ms Tor ready, starting reverse proxy")
            if (!startReverseProxy()) {
                Log.e(TAG, "[BOOT] Reverse proxy did not start, Tor stays STARTING")
                return
            }
            setState(TorState.RUNNING)
        }
    }

    /** Launches the reverse proxy; false when it could not be started. */
    private fun startReverseProxy(): Boolean {
        File(config.reverseProxyPath).setExecutable(true)

        // Launch here, like Tor itself: a stop right after RUNNING must find the proxy to stop.
        val proc = try {
            proxyRunner.launch(
                binaryPath = config.reverseProxyPath,
                args = listOf(
                    "-proxyport", config.reverseProxyDefaultPort.toString(),
                    "-sockport", config.torDefaultSocksPort.toString(),
                    "-pidfile", config.reverseProxyPidPath
                ),
                env = mapOf("LD_LIBRARY_PATH" to config.nativeLibPath)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start the reverse proxy", e)
            return false
        }

        proxyThread = Thread({
            val exitCode = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            Log.d(TAG, "ReverseProxy exited with code $exitCode")
        }, "ReverseProxyThread").also { it.isDaemon = true; it.start() }
        return true
    }

    fun stopTor() {
        lock.withLock {
            if (state.get() == TorState.STOPPED) return
            setState(TorState.STOPPING)
        }

        proxyRunner.stop()
        torRunner.stop()

        File(config.torPidPath).delete()
        File(config.reverseProxyPidPath).delete()

        setState(TorState.STOPPED)
        bootstrapPercent.set(0)
        // Do NOT reset currentMode — user preference is persisted separately.
    }

    fun restartTor(
        mode: TorMode = TorMode.ALWAYS,
        bridgeType: BridgeType = BridgeType.NONE,
        customBridges: List<String> = emptyList()
    ) {
        stopTor()
        startTor(mode, bridgeType, customBridges)
    }

    private fun setState(newState: TorState) {
        state.set(newState)
        lastKnownState = newState
        onStateChanged?.invoke(newState)
        Log.d(TAG, "State → $newState")
    }

    companion object {
        /**
         * The daemon's state for code that holds no plugin instance — the native
         * hangup on a task swipe ([com.forta.chat.plugins.calls.CallHangupSignal])
         * routes itself the way the app routes its Matrix traffic.
         */
        @Volatile
        var lastKnownState: TorState = TorState.STOPPED
            private set
    }
}
