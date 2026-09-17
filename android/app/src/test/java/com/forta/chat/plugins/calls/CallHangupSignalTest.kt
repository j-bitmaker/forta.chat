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
            "&accessToken=syt_secret_token" +
            "&viaTorProxy=0"

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
        assertFalse(target.viaTorProxy)
    }

    @Test
    fun parse_readsTheTorFlag() {
        val target = CallHangupSignal.parse("\"${encoded.replace("viaTorProxy=0", "viaTorProxy=1")}\"")
        assertTrue(target!!.viaTorProxy)
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
