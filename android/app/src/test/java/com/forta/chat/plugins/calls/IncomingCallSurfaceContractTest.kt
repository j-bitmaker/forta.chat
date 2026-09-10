package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O10/O13: the incoming-call surface tells the user when Android took the
 * full-screen intent away, and its volume rocker drives the ringer stream.
 * Source-level assertions in the spirit of [IncomingCallAcceptGuardTest].
 */
class IncomingCallSurfaceContractTest {

    private fun read(relative: String): String {
        val candidates = listOf("src/main/$relative", "android/app/src/main/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val activity by lazy { read("java/com/forta/chat/plugins/calls/IncomingCallActivity.kt") }
    private val pushPlugin by lazy { read("java/com/forta/chat/plugins/push/PushDataPlugin.kt") }
    private val manifest by lazy { read("AndroidManifest.xml") }
    private val callPlugin by lazy { read("java/com/forta/chat/plugins/calls/CallPlugin.kt") }

    @Test
    fun incomingCallScreen_routesTheVolumeRocker_toTheRingerStream() {
        val onCreate = functionBody(activity, "override\\s+fun\\s+onCreate\\s*\\(")
        assertTrue(
            "onCreate must set volumeControlStream = AudioManager.STREAM_RING:\n$onCreate",
            onCreate.contains("volumeControlStream = AudioManager.STREAM_RING"),
        )
    }

    @Test
    fun fullScreenIntentStatus_asksTheSystem_onAndroid14AndLater() {
        val body = functionBody(pushPlugin, "fun\\s+getFullScreenIntentStatus\\s*\\(")
        assertTrue(body.contains("canUseFullScreenIntent()"))
        assertTrue("the query must be gated on API 34:\n$body", body.contains("Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertTrue("the answer must carry both fields:\n$body", body.contains("put(\"allowed\"") && body.contains("put(\"manageable\""))
    }

    @Test
    fun fullScreenIntentSettings_deepLinkToTheSystemScreen_forThisPackage() {
        val body = functionBody(pushPlugin, "fun\\s+openFullScreenIntentSettings\\s*\\(")
        assertTrue(body.contains("Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT"))
        assertTrue("the intent must name this package:\n$body", body.contains("package:\${context.packageName}"))
        assertTrue("older Android must be refused, not crashed:\n$body", body.contains("call.reject("))
    }

    @Test
    fun manifest_declaresTheFullScreenIntentPermission() {
        assertTrue(manifest.contains("android.permission.USE_FULL_SCREEN_INTENT"))
    }

    @Test
    fun ensureIncomingCallVisible_decidesPerCall_notPerOccupiedSlot() {
        val body = functionBody(callPlugin, "fun\\s+ensureIncomingCallVisible\\s*\\(")
        assertTrue(
            "the skip must go through IncomingSurfacePolicy — reading the slot " +
                "directly is what let one stranded connection silence every later " +
                "call on the /sync route:\n$body",
            body.contains("IncomingSurfacePolicy.isAlreadyVisibleFor("),
        )
        assertTrue(
            "the requested callId must reach the policy, or it cannot tell the " +
                "idempotent re-ask from a different call:\n$body",
            body.contains("requestedCallId = call.getString(\"callId\")"),
        )
        assertTrue(
            "the armed ringer is the discriminator between a real ringer and an " +
                "orphan, so it must be passed in:\n$body",
            body.contains("ringingCallId = IncomingRinger.ringingCallId"),
        )
    }

    @Test
    fun ensureIncomingCallVisible_neverRejectsOrDisconnectsTheSlotItself() {
        // Displacing is onCreateIncomingConnection's job and it already does it
        // under DisplacedConnectionPolicy. A second owner here would be the
        // "two teardown owners" bug that CallTeardown was built to end.
        val body = functionBody(callPlugin, "fun\\s+ensureIncomingCallVisible\\s*\\(")
        assertTrue(
            "ensureIncomingCallVisible must not tear the slot down itself:\n$body",
            !body.contains("onDisconnect()") &&
                !body.contains("onReject()") &&
                !body.contains("CallTeardown."),
        )
    }

    @Test
    fun aDestroyedCallPlugin_stopsAnsweringTelecomCallbacks() {
        // CallConnection.onAnswered/onRejected/onEnded are companion-object
        // statics. A plugin instance that outlives its Bridge keeps receiving
        // native Accept/Decline and pushes them into a torn-down WebView, instead
        // of letting onAnswer's "queued for replay" marker path take over. Newly
        // reachable now that a dead renderer can trigger MainActivity.recreate().
        val body = functionBody(callPlugin, "override\\s+fun\\s+handleOnDestroy\\s*\\(")
        for (name in listOf("onAnswered", "onRejected", "onEnded")) {
            assertTrue(
                "handleOnDestroy must clear CallConnection.$name:\n$body",
                body.contains("CallConnection.$name = null"),
            )
        }
        assertTrue(
            "each clear must be guarded by identity — during a recreate the new " +
                "instance may already have installed its own, and clearing those " +
                "would silence a live plugin:\n$body",
            Regex("===").findAll(body).count() >= 3,
        )
    }

    private fun functionBody(src: String, signaturePattern: String): String {
        val match = Regex("$signaturePattern[^{]*\\{").find(src)
            ?: error("Could not find /$signaturePattern/ in source")
        var depth = 1
        var i = match.range.last + 1
        val start = i
        while (i < src.length && depth > 0) {
            when (src[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return src.substring(start, i - 1)
    }
}
