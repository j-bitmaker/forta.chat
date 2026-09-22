package com.forta.chat.plugins.calls

import android.content.Context
import android.util.Log
import com.forta.chat.plugins.tor.ConfigurationManager
import com.forta.chat.plugins.tor.TorManager
import com.forta.chat.plugins.tor.TorRouteDecider
import com.forta.chat.plugins.tor.TorState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends `m.call.hangup` from native code when JS cannot: a swipe from Recents
 * mid-call destroys the WebView before the SDK hangs up, and the peer used to
 * sit in a silent call until its connection watchdog gave up (29 s on the
 * Samsung ↔ Pixel bench, 2026-09-17).
 *
 * JS answers [captureScript] with what the SDK itself would use — room, call,
 * this device as `party_id`, the homeserver the client talks to and its access
 * token. Whether the request goes through the Tor proxy is decided here, from
 * the same persisted mode and daemon state the app's own routing uses: the JS
 * side only knows about the proxy it configured at login, which is stale as soon
 * as Tor is switched on mid-session (`tor2`, the hangup went out direct). It is read with
 * evaluateJavascript rather than passed as plugin call data because Capacitor
 * logs call data in debug builds. The token lives in memory only, for the one
 * call, and never reaches a log line.
 *
 * Call events in these rooms are not encrypted (the SDK never sees a
 * `m.room.encryption` state event with an empty state key), so a plain send
 * matches what the SDK puts on the wire.
 */
object CallHangupSignal {
    private const val TAG = "CallHangupSignal"
    private const val VOIP_VERSION = "1"
    private const val REASON = "user_hangup"
    /** The app's own Tor path: a reverse proxy that takes the target URL in its path (`service-worker.js`). */
    private const val TOR_PROXY = "http://127.0.0.1:8181/"
    private const val TIMEOUT_MS = 15_000

    class Target(
        val callId: String,
        val roomId: String,
        val partyId: String,
        val baseUrl: String,
        val accessToken: String,
    ) {
        override fun toString(): String = "Target(callId=$callId, roomId=$roomId)"
    }

    class Request(val url: String, val body: String, val headers: Map<String, String>)

    @Volatile
    private var remembered: Target? = null

    private val inFlight = AtomicInteger(0)

    /** Whether a hangup is still on its way; the idle process exit waits for it. */
    val isSending: Boolean get() = inFlight.get() > 0

    /** Asks the page for the hangup context of [callId]; see `call-hangup-context.ts`. */
    fun captureScript(callId: String): String =
        "(function(){try{var f=window.__fortaCallHangupContext;" +
            "return typeof f==='function'?f(${jsString(callId)}):null}catch(e){return null}})()"

    /**
     * Reads what evaluateJavascript returned for [captureScript]: the JSON form of
     * a URL-encoded string, whose characters never need JSON escaping.
     */
    fun parse(evaluateResult: String?): Target? {
        val raw = evaluateResult?.trim() ?: return null
        if (raw.length < 2 || !raw.startsWith('"') || !raw.endsWith('"')) return null
        val fields = raw.substring(1, raw.length - 1)
            .split('&')
            .mapNotNull { pair ->
                val at = pair.indexOf('=')
                if (at <= 0) null else decode(pair.substring(0, at)) to decode(pair.substring(at + 1))
            }
            .toMap()
        val callId = fields["callId"].orEmpty()
        val roomId = fields["roomId"].orEmpty()
        val partyId = fields["partyId"].orEmpty()
        val baseUrl = fields["baseUrl"].orEmpty().trimEnd('/')
        val accessToken = fields["accessToken"].orEmpty()
        if (listOf(callId, roomId, partyId, baseUrl, accessToken).any { it.isEmpty() }) return null
        if (!isAllowedBaseUrl(baseUrl)) return null
        return Target(callId, roomId, partyId, baseUrl, accessToken)
    }

    /**
     * The request carries the bearer token, so plain HTTP is accepted only for
     * a loopback dev homeserver; the Tor proxy is a separate loopback URL
     * built in [torUrl], not a base URL.
     */
    fun isAllowedBaseUrl(baseUrl: String): Boolean {
        if (baseUrl.startsWith("https://")) return true
        if (!baseUrl.startsWith("http://")) return false
        val host = baseUrl.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2"
    }

    fun request(target: Target, txnId: String): Request = Request(
        url = "${target.baseUrl}/_matrix/client/v3/rooms/${pathSegment(target.roomId)}" +
            "/send/m.call.hangup/${pathSegment(txnId)}",
        body = "{\"call_id\":${jsString(target.callId)},\"party_id\":${jsString(target.partyId)}," +
            "\"version\":\"$VOIP_VERSION\",\"reason\":\"$REASON\"}",
        headers = mapOf(
            "Authorization" to "Bearer ${target.accessToken}",
            "Content-Type" to "application/json",
        ),
    )

    /** Recently ended calls: a capture answered after its call ended must not bring the token back. */
    private val ended = ArrayDeque<String>()
    private const val ENDED_KEPT = 16

    @Volatile
    private var appContext: Context? = null

    /** The app context the send needs for the Tor route; set once, from the plugin. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun remember(target: Target) = synchronized(this) {
        if (target.callId !in ended) remembered = target
    }

    /** Drops the target of a call that ended on any path; another call's target stays. */
    fun forget(callId: String?) {
        if (callId.isNullOrEmpty()) return
        synchronized(this) {
            ended.addLast(callId)
            while (ended.size > ENDED_KEPT) ended.removeFirst()
            if (remembered?.callId == callId) remembered = null
        }
    }

    /**
     * Hands out the target of the call in [slotCallId] once. A slot a push created
     * can be keyed by the invite's event id or nothing at all; [CallSlotPolicy]
     * decides whether it is the call JS reported.
     */
    fun take(slotCallId: String): Target? = synchronized(this) {
        remembered?.takeIf { CallSlotPolicy.owns(slotCallId, it.callId) }?.also { remembered = null }
    }

    fun sendAsync(target: Target) {
        val context = appContext
        inFlight.incrementAndGet()
        Thread({
            try {
                sendWithRetry(target, viaTorProxy = context != null && routeThroughTor(context, target.baseUrl))
            } finally {
                inFlight.decrementAndGet()
            }
        }, "call-hangup-signal").start()
    }

    /** The app's own rule for this homeserver: persisted mode plus the daemon's state. */
    private fun routeThroughTor(context: Context, baseUrl: String): Boolean = runCatching {
        val mode = ConfigurationManager(context).loadSettings().mode
        val ready = TorManager.lastKnownState == TorState.RUNNING
        TorRouteDecider().isUseWithTor(baseUrl, mode, ready)
    }.getOrElse {
        Log.w(TAG, "could not read the Tor route, sending direct", it)
        false
    }

    private fun sendWithRetry(target: Target, viaTorProxy: Boolean) {
        // One transaction id for both attempts: the homeserver drops a repeat of
        // a send that did arrive, so a retry after a lost response is harmless.
        val request = request(target, txnId = "fortahangup${System.currentTimeMillis()}")
        for (attempt in 1..2) {
            try {
                val code = send(request, viaTorProxy)
                Log.i(TAG, "m.call.hangup for ${target.callId} → HTTP $code (attempt $attempt, tor=$viaTorProxy)")
                if (code in 200..299 || code in 400..499) return
            } catch (e: IOException) {
                Log.w(TAG, "m.call.hangup for ${target.callId} failed (attempt $attempt): ${e.javaClass.simpleName}")
            }
            if (attempt == 1) Thread.sleep(1_000)
        }
    }

    private fun send(request: Request, viaTorProxy: Boolean): Int {
        val url = URL(if (viaTorProxy) torUrl(request.url) else request.url)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** `http://127.0.0.1:8181/{encodeURIComponent(url)}`, the form the app's proxy answers. */
    fun torUrl(targetUrl: String): String = TOR_PROXY + encodeUriComponent(targetUrl)

    /** `encodeURIComponent`: the proxy unescapes its path the way the service worker escapes it. */
    private fun encodeUriComponent(value: String): String {
        val keep = "-_.!~*'()"
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && b.toInt() in 0..127 || keep.indexOf(c) >= 0) out.append(c)
            else out.append('%').append("%02X".format(b.toInt() and 0xFF))
        }
        return out.toString()
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")

    private fun pathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun jsString(value: String): String {
        val out = StringBuilder("\"")
        for (c in value) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
