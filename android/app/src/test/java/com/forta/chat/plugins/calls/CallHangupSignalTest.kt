package com.forta.chat.plugins.calls

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `m.call.hangup` native code sends itself when the user swipes the app
 * from Recents mid-call.
 *
 * Found on the Samsung ↔ Pixel bench 2026-09-17 (`pamode2`): the swipe
 * destroys the WebView before JS can hang up, so the peer sat in a silent
 * call for 29 s until its connection watchdog gave up. The wiring is pinned in
 * [CallHangupSignalContractTest].
 */
class CallHangupSignalTest {

    private val encoded =
        "callId=1789657899728BymZ0Pa7tsogWJF2" +
            "&roomId=%21YJTutyRoEKqcowPZRk%3Amatrix.pocketnet.app" +
            "&partyId=ABCDEFGHIJ" +
            "&baseUrl=https%3A%2F%2Fmatrix.pocketnet.app" +
            "&accessToken=syt_secret_token"

    @After
    fun clear() {
        CallHangupSignal.take("1789657899728BymZ0Pa7tsogWJF2")
    }

    @Test
    fun parse_readsTheQuotedStringEvaluateJavascriptReturns() {
        val target = CallHangupSignal.parse("\"$encoded\"")
        assertNotNull(target)
        target!!
        assertEquals("1789657899728BymZ0Pa7tsogWJF2", target.callId)
        assertEquals("!YJTutyRoEKqcowPZRk:matrix.pocketnet.app", target.roomId)
        assertEquals("ABCDEFGHIJ", target.partyId)
        assertEquals("https://matrix.pocketnet.app", target.baseUrl)
        assertEquals("syt_secret_token", target.accessToken)
    }

    @Test
    fun parse_rejectsAMissingProviderOrIncompleteContext() {
        assertNull(CallHangupSignal.parse(null))
        assertNull(CallHangupSignal.parse("null"))
        assertNull(CallHangupSignal.parse("\"\""))
        assertNull(CallHangupSignal.parse("\"${encoded.replace("&partyId=ABCDEFGHIJ", "")}\""))
        assertNull(CallHangupSignal.parse("\"${encoded.replace("accessToken=syt_secret_token", "accessToken=")}\""))
    }

    @Test
    fun parse_rejectsABaseUrlThatIsNotHttp() {
        val file = encoded.replace("https%3A%2F%2Fmatrix.pocketnet.app", "file%3A%2F%2F%2Fdata")
        assertNull(CallHangupSignal.parse("\"$file\""))
    }

    @Test
    fun parse_rejectsPlainHttpForAnythingButALoopbackDevHost() {
        // The request carries the bearer token.
        val http = encoded.replace("https%3A%2F%2Fmatrix.pocketnet.app", "http%3A%2F%2Fmatrix.pocketnet.app")
        assertNull(CallHangupSignal.parse("\"$http\""))
        val lookalike = encoded.replace("https%3A%2F%2Fmatrix.pocketnet.app", "http%3A%2F%2F127.0.0.1.evil.example")
        assertNull(CallHangupSignal.parse("\"$lookalike\""))
        val local = encoded.replace("https%3A%2F%2Fmatrix.pocketnet.app", "http%3A%2F%2F127.0.0.1%3A8008")
        assertEquals("http://127.0.0.1:8008", CallHangupSignal.parse("\"$local\"")?.baseUrl)
        assertTrue(CallHangupSignal.isAllowedBaseUrl("http://localhost:8008"))
        assertTrue(CallHangupSignal.isAllowedBaseUrl("http://10.0.2.2:8008/"))
        assertTrue(CallHangupSignal.isAllowedBaseUrl("https://matrix.pocketnet.app"))
    }

    @Test
    fun target_neverPrintsTheToken() {
        val target = CallHangupSignal.parse("\"$encoded\"")!!
        assertFalse(target.toString().contains("syt_secret_token"))
        assertTrue(target.toString().contains("1789657899728BymZ0Pa7tsogWJF2"))
    }

    @Test
    fun request_isTheSdkHangupForThisCallAndDevice() {
        val target = CallHangupSignal.parse("\"$encoded\"")!!
        val request = CallHangupSignal.request(target, txnId = "native-hangup-1")

        assertEquals(
            "https://matrix.pocketnet.app/_matrix/client/v3/rooms/%21YJTutyRoEKqcowPZRk%3Amatrix.pocketnet.app" +
                "/send/m.call.hangup/native-hangup-1",
            request.url,
        )
        assertEquals("Bearer syt_secret_token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        // party_id is what the peer matches a hangup of a connected call against
        // (matrix-js-sdk MatrixCall.partyIdMatches); version 1 as the SDK sends it.
        assertEquals(
            "{\"call_id\":\"1789657899728BymZ0Pa7tsogWJF2\",\"party_id\":\"ABCDEFGHIJ\"," +
                "\"version\":\"1\",\"reason\":\"user_hangup\"}",
            request.body,
        )
    }

    @Test
    fun request_keepsATrailingSlashOutOfThePath() {
        val slash = CallHangupSignal.parse(
            "\"${encoded.replace("https%3A%2F%2Fmatrix.pocketnet.app", "https%3A%2F%2Fmatrix.pocketnet.app%2F")}\"",
        )!!
        assertTrue(CallHangupSignal.request(slash, "t").url.startsWith("https://matrix.pocketnet.app/_matrix/"))
    }

    @Test
    fun request_escapesJsonStrings() {
        val odd = CallHangupSignal.parse("\"${encoded.replace("partyId=ABCDEFGHIJ", "partyId=a%22b%5Cc")}\"")!!
        assertTrue(CallHangupSignal.request(odd, "t").body.contains("\"party_id\":\"a\\\"b\\\\c\""))
    }

    @Test
    fun torUrl_isTheReverseProxyFormTheAppUses() {
        // The local proxy takes the target in its path (public/service-worker.js);
        // as an ordinary HTTP proxy it refuses the request (`tor3`, two IOExceptions).
        assertEquals(
            "http://127.0.0.1:8181/https%3A%2F%2Fmatrix.pocketnet.app%2F_matrix%2Fclient%2Fv3%2Frooms%2F" +
                "%2521room%2Fsend%2Fm.call.hangup%2Ft1",
            CallHangupSignal.torUrl(
                "https://matrix.pocketnet.app/_matrix/client/v3/rooms/%21room/send/m.call.hangup/t1",
            ),
        )
    }

    @Test
    fun captureScript_passesTheCallIdAsAJsString() {
        val script = CallHangupSignal.captureScript("abc\"def\\")
        assertTrue(script.contains("__fortaCallHangupContext"))
        assertTrue(script.contains("\"abc\\\"def\\\\\""))
    }

    @Test
    fun take_handsTheTargetOutOnce() {
        val target = CallHangupSignal.parse("\"$encoded\"")!!
        CallHangupSignal.remember(target)
        assertSame(target, CallHangupSignal.take("1789657899728BymZ0Pa7tsogWJF2"))
        assertNull(CallHangupSignal.take("1789657899728BymZ0Pa7tsogWJF2"))
    }

    @Test
    fun take_ignoresAnotherCall() {
        CallHangupSignal.remember(CallHangupSignal.parse("\"$encoded\"")!!)
        assertNull(CallHangupSignal.take("someOtherCall"))
        assertNotNull(CallHangupSignal.take("1789657899728BymZ0Pa7tsogWJF2"))
    }

    @Test
    fun take_acceptsASlotKeyedByTheInviteEventId() {
        // A push without call_id keys the Telecom slot by the invite's event id.
        CallHangupSignal.remember(CallHangupSignal.parse("\"$encoded\"")!!)
        assertNotNull(CallHangupSignal.take("\$eventIdOfTheInvite"))
    }

    @Test
    fun forget_dropsOnlyTheEndedCall() {
        val other = encoded.replace("callId=1789657899728BymZ0Pa7tsogWJF2", "callId=callForgetTest")
        CallHangupSignal.remember(CallHangupSignal.parse("\"$other\"")!!)
        CallHangupSignal.forget("someOtherCall")
        CallHangupSignal.forget(null)
        assertNotNull(CallHangupSignal.take("callForgetTest"))

        CallHangupSignal.remember(CallHangupSignal.parse("\"$other\"")!!)
        CallHangupSignal.forget("callForgetTest")
        assertNull(CallHangupSignal.take("callForgetTest"))
    }

    @Test
    fun remember_ignoresACaptureThatArrivesAfterItsCallEnded() {
        // The capture is answered asynchronously; a call cancelled at once ends first.
        val late = encoded.replace("callId=1789657899728BymZ0Pa7tsogWJF2", "callId=callEndedFirst")
        CallHangupSignal.forget("callEndedFirst")
        CallHangupSignal.remember(CallHangupSignal.parse("\"$late\"")!!)
        assertNull(CallHangupSignal.take("callEndedFirst"))
    }
}
