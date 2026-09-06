package com.forta.chat.plugins.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import com.forta.chat.R

class CallConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "CallConnectionService"
        const val INCOMING_CALL_NOTIFICATION_ID = 9999
        // Written from the main thread (Telecom callbacks) and read from
        // Capacitor's plugin thread (reportCallEnded / reportCallConnected) —
        // and since the displacement check now reads `previous.state` through
        // it, a stale reference could tear down the wrong call.
        @Volatile
        var currentConnection: CallConnection? = null

        fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
            val componentName = ComponentName(context, CallConnectionService::class.java)
            return PhoneAccountHandle(componentName, "BastyonChat")
        }

        fun registerPhoneAccount(context: Context) {
            val handle = getPhoneAccountHandle(context)
            // IMPORTANT: Do NOT change the PhoneAccountHandle id "BastyonChat" — it must remain
            // unchanged to avoid orphaning the already-registered phone account on app upgrade.
            // Only the display label "Forta Chat" may be updated.
            val account = PhoneAccount.builder(handle, "Forta Chat")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.registerPhoneAccount(account)
        }

        /**
         * Release a connection that has been RINGING past its deadline.
         *
         * Telecom holds `MODE_RINGTONE` — and with it the device's media
         * volume — for as long as a self-managed connection rings. The
         * per-connection timer is the primary backstop; this sweep is what
         * catches the cases where that timer never got to run (Doze, a wedged
         * main looper, a frozen process). Called when the app returns to the
         * foreground, which is exactly when a user who is staring at a broken
         * volume slider opens it.
         *
         * @return true when a connection was actually released.
         */
        fun releaseStaleRingingConnection(
            nowMs: Long = SystemClock.elapsedRealtime(),
        ): Boolean {
            val connection = currentConnection ?: return false
            val stale = StaleCallPolicy.isStaleRinging(
                isRinging = connection.state == Connection.STATE_RINGING,
                ringingSinceMs = connection.ringingSinceMs,
                nowMs = nowMs,
                timeoutMs = CallConnection.RING_TIMEOUT_MS,
            )
            if (!stale) return false
            Log.w(TAG, "Releasing a connection stuck RINGING past its deadline: ${connection.callId}")
            // onReject (not onDisconnect) so the caller is told we declined
            // and stops ringing on their side too.
            runCatching { connection.onReject() }
                .onFailure { Log.w(TAG, "stale-ring release threw", it) }
            return true
        }

        fun dismissIncomingCallNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras ?: Bundle()
        val callId = extras.getString("callId", "")
        val callerName = extras.getString("callerName", "Unknown")
        val hasVideo = extras.getBoolean("hasVideo", false)
        val roomId = extras.getString("roomId", "")

        Log.d(TAG, "onCreateIncomingConnection: callId=$callId, caller=$callerName, roomId=$roomId")

        // A conversation in progress keeps the single slot. Evicting it — what
        // this did unconditionally — left it alive but unreachable: every
        // consumer reads the slot with no callId check (reportCallEnded,
        // reportCallConnected, the ringer's own decline), so that call could
        // never be ended again and Telecom would hold the device in a call
        // audio mode until reboot. While one slot is all there is, refusing the
        // second call is the honest answer: the new caller gets BUSY rather
        // than ringing into a void, and the live call stays endable.
        currentConnection?.let { previous ->
            if (!DisplacedConnectionPolicy.mayRelease(previous.state)) {
                Log.w(TAG, "Incoming call while a call is established — reporting busy")
                val busy = Connection.createFailedConnection(
                    DisconnectCause(DisconnectCause.BUSY, "already-in-call")
                )
                runCatching { busy.destroy() }
                return busy
            }
        }

        // WEE-31: Telecom contract requires us to return a Connection here.
        // Any throw used to bubble up into the system_server bound IPC and
        // crash the callee process. Catch anything that can throw inside
        // the Connection bootstrap and return a failed Connection — the
        // caller's MatrixCall will get a reject and the user will still see
        // the push-side IncomingCallActivity ringer that was posted by FCM.
        return try {
            val connection = CallConnection(applicationContext, callId, roomId)
            connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
            connection.setAddress(
                Uri.fromParts("sip", callerName, null),
                TelecomManager.PRESENTATION_ALLOWED
            )
            connection.setInitializing()
            connection.setRinging()

            // Releasing whatever sat here before is not optional: a connection
            // displaced from this single slot is unreachable by onReject and
            // onDisconnect forever, and Telecom keeps holding the device in a
            // call audio mode on its behalf until reboot. The established case
            // never reaches here — it returned BUSY above.
            currentConnection?.let { previous ->
                if (previous !== connection) {
                    Log.w(TAG, "Displacing a stale connection — disconnecting it first")
                    runCatching { previous.onDisconnect() }
                        .onFailure { Log.w(TAG, "displaced connection teardown threw", it) }
                }
            }
            currentConnection = connection
            connection.armRingTimeout()

            // Session 41: Telecom is about to post its own FSI ringer notification
            // (CHANNEL_INCOMING_CALLS, id 9999). The FCM service already posted
            // one on the push path (CallNotificationConfig.INCOMING_CALL_CHANNEL_ID,
            // "call_$roomId".hashCode()) — dismiss it now so we don't ring twice
            // from two different channels.
            if (roomId.isNotEmpty()) {
                runCatching {
                    com.forta.chat.FortaFirebaseMessagingService
                        .dismissPushCallNotification(applicationContext, roomId)
                }
            }

            // Show native incoming call UI
            runCatching { showIncomingCallUI(callId, callerName, hasVideo) }
                .onFailure { Log.e(TAG, "[callee-crash-guard] showIncomingCallUI failed", it) }

            connection
        } catch (t: Throwable) {
            Log.e(TAG, "[callee-crash-guard] onCreateIncomingConnection failed", t)
            // The Telecom framework requires a non-null Connection return,
            // but it will NOT auto-destroy a failed connection. Without
            // .destroy() the call slot stays occupied on some OEM Telecom
            // stacks, blocking subsequent calls (MIUI, EMUI). Release it
            // immediately — the framework still gets a Connection ref it
            // can route the disconnect cause through.
            val failed = Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "incoming-connection-init-failed")
            )
            runCatching { failed.destroy() }
            failed
        }
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras ?: Bundle()
        val callId = extras.getString("callId", "")
        val callerName = extras.getString("callerName", "")

        Log.d(TAG, "onCreateOutgoingConnection: callId=$callId, callee=$callerName")

        val connection = CallConnection(applicationContext, callId)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setAddress(
            request?.address ?: Uri.fromParts("sip", callerName, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setDialing()

        // Same displacement rule as the incoming path: a connection pushed out
        // of this single slot is unreachable by onReject and onDisconnect
        // forever, and Telecom keeps holding the device in a call audio mode on
        // its behalf. Placing a call while a previous one is still stuck in the
        // slot used to orphan it — the incoming side was fixed and this one was
        // not.
        //
        // Deliberately NOT gated on DisplacedConnectionPolicy, unlike the
        // incoming path: `startCall` in JS already refuses to dial while
        // `hasLiveCall`, so a connection still reading as established here is a
        // previous call whose teardown has not landed yet — the back-to-back
        // "hang up and immediately redial" race. Sparing it would restore
        // exactly the orphan this guard exists to close.
        currentConnection?.let { previous ->
            if (previous !== connection) {
                Log.w(TAG, "Displacing a connection on dial — disconnecting it first")
                runCatching { previous.onDisconnect() }
                    .onFailure { Log.w(TAG, "displaced connection teardown threw", it) }
            }
        }
        currentConnection = connection
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed")
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
    }

    private fun showIncomingCallUI(callId: String, callerName: String, hasVideo: Boolean) {
        val fullScreenIntent = Intent(applicationContext, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("hasVideo", hasVideo)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            applicationContext, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept action
        val acceptIntent = Intent(applicationContext, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("action", "accept")
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            applicationContext, 1, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline action
        val declineIntent = Intent(applicationContext, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("callId", callId)
            putExtra("callerName", callerName)
            putExtra("action", "decline")
        }
        val declinePendingIntent = PendingIntent.getActivity(
            applicationContext, 2, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create notification channel
        val channelId = "incoming_calls"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId, applicationContext.getString(R.string.channel_incoming_calls),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = applicationContext.getString(R.string.channel_incoming_calls_desc)
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(channel)

        // FSI permission check for Android 14+ (USE_FULL_SCREEN_INTENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!notificationManager.canUseFullScreenIntent()) {
                Log.w(TAG, "USE_FULL_SCREEN_INTENT not granted, FSI will be heads-up only")
                try {
                    applicationContext.startActivity(fullScreenIntent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Direct activity start also failed, falling back to notification", e)
                }
            }
        }

        val caller = androidx.core.app.Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setOngoing(true)
            .setAutoCancel(false)

        // Use CallStyle on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller, declinePendingIntent, acceptPendingIntent
                )
            )
        } else {
            builder.setContentTitle(applicationContext.getString(R.string.push_incoming_call))
            builder.setContentText(callerName)
        }

        // WEE-31: notify() throws SecurityException on Android 13+ if the
        // user revoked POST_NOTIFICATIONS between channel creation and the
        // FCM-triggered ring. Don't crash — the direct startActivity below
        // is still attempted, and the FCM service path also posted its own
        // notification on the push-side channel.
        try {
            notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, builder.build())
        } catch (e: Throwable) {
            Log.e(TAG, "[callee-crash-guard] notificationManager.notify failed", e)
        }

        // Start activity directly for foreground case
        try {
            applicationContext.startActivity(fullScreenIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not start IncomingCallActivity directly", e)
        }
    }
}

class CallConnection(
    private val context: Context,
    val callId: String,
    private val roomId: String = ""
) : Connection() {

    companion object {
        var onAnswered: ((String) -> Unit)? = null
        var onRejected: ((String) -> Unit)? = null
        var onEnded: ((String) -> Unit)? = null

        /**
         * Queued answer callId — set when user taps "Answer" before JS listener
         * is wired. JS side checks and replays this on wire().
         *
         * Note: the push payload from pocketnet's Matrix homeserver does
         * NOT include the Matrix `content.call_id`, so the value stored
         * here is really the push `event_id` — different from the call
         * id the JS-side SDK will later see on the MatrixCall object.
         * Consumers should also match by room (pendingAnswerRoomId
         * below) to reliably correlate.
         */
        var pendingAnswerCallId: String? = null

        /**
         * Matrix room id the user tapped "Answer" on — set ONLY inside
         * CallConnection.onAnswer() so Decline and other paths never
         * trigger the JS-side fast-path auto-answer. JS consumer treats
         * the presence of this marker as "user already accepted a call
         * in room R, next incoming MatrixCall for R is that one".
         */
        var pendingAnswerRoomId: String? = null

        /**
         * Set when user taps Decline before JS is running. Symmetric to
         * pendingAnswerCallId/RoomId. When JS boots it reads both via
         * NativeCall.getPendingReject, and when Matrix finally delivers
         * the invite for this room the handler calls `matrixCall.reject()`
         * so the caller actually gets our rejection signal — otherwise
         * the caller keeps ringing until their own timeout.
         */
        var pendingRejectCallId: String? = null
        var pendingRejectRoomId: String? = null

        /**
         * Backstop for a connection nobody ever resolves. Longer than the 30 s
         * countdown in [IncomingCallActivity] so that, when the activity does
         * run, its own auto-reject still wins and the user keeps the UI they
         * are looking at. Shorter than the SDK's 60 s invite lifetime, so the
         * device is never left ringing for a call the caller has given up on.
         */
        const val RING_TIMEOUT_MS = 45_000L
    }

    /**
     * When this connection started ringing, on the monotonic clock. Read by
     * [StaleCallPolicy] on app resume as the second net behind
     * [armRingTimeout] — see that method for why one timer is not enough.
     */
    @Volatile
    var ringingSinceMs: Long = SystemClock.elapsedRealtime()
        private set

    private val ringTimeoutHandler = Handler(Looper.getMainLooper())
    private val ringTimeoutRunnable = Runnable {
        Log.w("CallConnection", "Ring timeout — no answer or decline reached us, rejecting $callId")
        // Nothing catches a throw out of a main-looper Runnable: it kills the
        // process. onReject is guarded against a double teardown, but Telecom
        // can still throw from a state it did not expect, and this timer fires
        // unattended — the user is not even holding the phone.
        runCatching { onReject() }
            .onFailure { Log.w("CallConnection", "ring timeout reject threw", it) }
    }

    /**
     * Latched once the connection has been disconnected, so teardown runs once.
     *
     * Atomic because the writers are on different threads: the ring backstop
     * fires on the main looper, while the stale-ring sweep reaches
     * [releaseStaleRingingConnection] from Capacitor's plugin thread. Both are
     * keyed to the same 45 s deadline, so them landing together is routine, not
     * exotic — and a plain check-then-set would let both pass the guard and
     * transition an already-destroyed connection, which Telecom answers with a
     * throw.
     */
    private val released = AtomicBoolean(false)

    /**
     * Start the no-answer backstop.
     *
     * Telecom holds the device in MODE_RINGTONE for as long as this connection
     * lives, and the only timer that used to end it lived inside
     * [IncomingCallActivity]. That activity does not always run — a blocked
     * full-screen intent never starts it — and when it does run, a back press
     * or a swipe from Recents destroys it, and its cleanup *cancels* the
     * auto-reject without replacing it. Either way the connection stayed
     * RINGING and the phone's media volume stayed broken until reboot. This
     * timer lives with the connection instead, so it survives both.
     */
    fun armRingTimeout() {
        ringingSinceMs = SystemClock.elapsedRealtime()
        ringTimeoutHandler.removeCallbacks(ringTimeoutRunnable)
        ringTimeoutHandler.postDelayed(ringTimeoutRunnable, RING_TIMEOUT_MS)
    }

    private fun cancelRingTimeout() {
        ringTimeoutHandler.removeCallbacks(ringTimeoutRunnable)
    }

    override fun onAnswer() {
        Log.d("CallConnection", "onAnswer: callId=$callId, roomId=$roomId")
        cancelRingTimeout()
        // Symmetric to onReject/onDisconnect: Telecom throws when a destroyed
        // connection is transitioned again. The ring backstop makes that
        // reachable by a hair's breadth — it fires on the same main looper the
        // Accept button posts to, so a tap landing just after the deadline used
        // to take the process down with it.
        if (released.get()) {
            Log.w("CallConnection", "onAnswer: connection already released, ignoring")
            return
        }
        setActive()
        // Every answer route reaches this method — the activity's own Accept
        // button calls it, and so does Telecom when it answers on its own from a
        // Bluetooth headset, Android Auto or the system call UI. Silencing here
        // is what covers the Telecom routes, which never touch the activity and
        // used to leave its looping ringtone playing over the connected call
        // until the 30 s auto-reject hung it up. Idempotent for the Accept path,
        // which has already run cleanup().
        IncomingCallActivity.stopRingerIfShowing()
        CallConnectionService.dismissIncomingCallNotification(context)
        // Populate accept-only markers here, never in onCreateIncoming-
        // Connection — otherwise Decline and a plain push delivery
        // would also set them and JS would fast-path into an in-call
        // screen the user never asked for.
        pendingAnswerCallId = callId
        if (roomId.isNotEmpty()) {
            pendingAnswerRoomId = roomId
        }
        if (onAnswered != null) {
            onAnswered?.invoke(callId)
        } else {
            Log.w("CallConnection", "onAnswer: JS listener not wired, queued for replay")
        }
    }

    override fun onReject() {
        Log.d("CallConnection", "onReject: callId=$callId, roomId=$roomId")
        cancelRingTimeout()
        // Telecom throws if a destroyed connection is disconnected again, and
        // there are now several routes here — the button, the shade action, the
        // activity's countdown and this connection's own backstop.
        if (!released.compareAndSet(false, true)) {
            Log.d("CallConnection", "onReject: already released, skipping teardown")
            return
        }
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallConnectionService.dismissIncomingCallNotification(context)
        // Wipe any stale accept markers so a late-arriving MatrixCall
        // for this room can't trigger the JS fast-path to auto-answer.
        clearPendingFor(callId, roomId)
        // Queue the reject so that when the JS app eventually boots
        // (or is already running) it can send m.call.reject to Matrix
        // and the caller stops ringing.
        // Vacate the single global slot. Nothing used to clear it, so after
        // any call ended `currentConnection` still pointed at a destroyed
        // Connection — and every later reader (the stale-ring sweep, the
        // displacement check above it) was inspecting a corpse. Identity-
        // guarded so a connection that was already displaced by a newer one
        // cannot blank its successor's slot.
        if (CallConnectionService.currentConnection === this) {
            CallConnectionService.currentConnection = null
        }
        pendingRejectCallId = callId
        if (roomId.isNotEmpty()) pendingRejectRoomId = roomId
        onRejected?.invoke(callId)
    }

    override fun onDisconnect() {
        Log.d("CallConnection", "onDisconnect: $callId")
        cancelRingTimeout()
        if (!released.compareAndSet(false, true)) {
            Log.d("CallConnection", "onDisconnect: already released, skipping teardown")
            return
        }
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallConnectionService.dismissIncomingCallNotification(context)
        // Vacate the single global slot. Nothing used to clear it, so after
        // any call ended `currentConnection` still pointed at a destroyed
        // Connection — and every later reader (the stale-ring sweep, the
        // displacement check above it) was inspecting a corpse. Identity-
        // guarded so a connection that was already displaced by a newer one
        // cannot blank its successor's slot.
        if (CallConnectionService.currentConnection === this) {
            CallConnectionService.currentConnection = null
        }
        clearPendingFor(callId, roomId)
        onEnded?.invoke(callId)
    }

    private fun clearPendingFor(cid: String, rid: String) {
        if (pendingAnswerCallId == cid) pendingAnswerCallId = null
        if (rid.isNotEmpty() && pendingAnswerRoomId == rid) pendingAnswerRoomId = null
    }
}
