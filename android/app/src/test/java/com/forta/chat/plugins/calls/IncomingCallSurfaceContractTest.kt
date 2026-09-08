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
