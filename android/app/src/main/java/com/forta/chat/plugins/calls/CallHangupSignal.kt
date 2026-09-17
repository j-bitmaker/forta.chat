package com.forta.chat.plugins.calls

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
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
 * this device as `party_id`, the homeserver the client talks to, its access
 * token and whether it goes through the Tor proxy. It is read with
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
    private const val TOR_PROXY_HOST = "127.0.0.1"
    private const val TOR_PROXY_PORT = 8181
    private const val TIMEOUT_MS = 15_000

    class Target(
        val callId: String,
        val roomId: String,
        val partyId: String,
        val baseUrl: String,
        val accessToken: String,
        val viaTorProxy: Boolean,
    ) {
        override fun toString(): String = "Target(callId=$callId, roomId=$roomId, viaTorProxy=$viaTorProxy)"
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
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) return null
        return Target(callId, roomId, partyId, baseUrl, accessToken, fields["viaTorProxy"] == "1")
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
        inFlight.incrementAndGet()
        Thread({
            try {
                sendWithRetry(target)
            } finally {
                inFlight.decrementAndGet()
            }
        }, "call-hangup-signal").start()
    }

    private fun sendWithRetry(target: Target) {
        // One transaction id for both attempts: the homeserver drops a repeat of
        // a send that did arrive, so a retry after a lost response is harmless.
        val request = request(target, txnId = "fortahangup${System.currentTimeMillis()}")
        for (attempt in 1..2) {
            try {
                val code = send(request, target.viaTorProxy)
                Log.i(TAG, "m.call.hangup for ${target.callId} → HTTP $code (attempt $attempt)")
                if (code in 200..299 || code in 400..499) return
            } catch (e: IOException) {
                Log.w(TAG, "m.call.hangup for ${target.callId} failed (attempt $attempt): ${e.javaClass.simpleName}")
            }
            if (attempt == 1) Thread.sleep(1_000)
        }
    }

    private fun send(request: Request, viaTorProxy: Boolean): Int {
        val url = URL(request.url)
        val connection = (if (viaTorProxy) {
            url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(TOR_PROXY_HOST, TOR_PROXY_PORT)))
        } else {
            url.openConnection()
        }) as HttpURLConnection
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
