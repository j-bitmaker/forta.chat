package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the wiring of the O08 fix: a refused route is reported, not swallowed,
 * all the way to JS, and device changes go through [AudioRoutePolicy].
 * Source-level assertions in the spirit of [CallTeardownContractTest].
 */
class AudioRoutePinContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        val resolved = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
        return resolved.readText()
    }

    private val router by lazy { source("com/forta/chat/plugins/calls/AudioRouter.kt") }
    private val plugin by lazy { source("com/forta/chat/plugins/calls/CallPlugin.kt") }

    @Test
    fun setDevice_refusesWhileInactive_andNeverStartsTheRouter() {
        val body = functionBody(router, "fun\\s+setDevice\\s*\\(")
        assertTrue("setDevice must return a Boolean", Regex("fun\\s+setDevice\\s*\\([^)]*\\)\\s*:\\s*Boolean").containsMatchIn(router))
        val guard = body.indexOf("if (!isActive)")
        val refuse = body.indexOf("return false")
        assertTrue("the inactive branch must return false:\n$body", guard in 0 until refuse)
        assertFalse("setDevice must not start the router or set the mode:\n$body", body.contains("isActive = true") || body.contains("MODE_IN_COMMUNICATION"))
        assertTrue("a refusal must land in the timeline:\n$body", body.substring(guard, refuse).contains("timeline.record("))
        assertTrue("an applied route is a pin:\n$body", body.contains("pinnedDevice = device"))
    }

    @Test
    fun plugin_rejectsTheCall_whenTheRouterRefuses() {
        val body = functionBody(plugin, "fun\\s+setAudioDevice\\s*\\(")
        assertTrue("setAudioDevice must reject on refusal:\n$body", body.contains("call.reject(") && body.contains("router_inactive"))
        assertTrue("setAudioDevice must resolve only on success:\n$body", body.contains("setDevice(device) == true"))
    }

    @Test
    fun devicesChanged_decidesThroughThePolicy_andDropsAStalePin() {
        val body = functionBody(router, "private\\s+fun\\s+handleDevicesChanged\\s*\\(")
        assertTrue(body.contains("AudioRoutePolicy.onDevicesChanged("))
        assertTrue("a vanished pinned device must clear the pin:\n$body", body.contains("if (!decision.keepPin) pinnedDevice = null"))
        assertFalse("no hard-coded BT auto-switch outside the policy:\n$body", body.contains("setDeviceInternal(Device.BLUETOOTH)"))
        val lock = body.indexOf("synchronized(routeLock)")
        val decide = body.indexOf("AudioRoutePolicy.onDevicesChanged(")
        assertTrue("read-decide-clear-route must sit under routeLock:\n$body", lock in 0 until decide)
    }

    @Test
    fun pin_isWrittenUnderTheSameLock_asTheDeviceChangeDecision() {
        val body = functionBody(router, "fun\\s+setDevice\\s*\\(")
        val lock = body.indexOf("synchronized(routeLock)")
        val pin = body.indexOf("pinnedDevice = device")
        assertTrue("the pin write must be under routeLock:\n$body", lock in 0 until pin)
    }

    @Test
    fun start_autoSelectsBluetooth_withoutPinningIt() {
        // A headset present at call start is the router's choice, not the
        // user's; pinning it would misreport intent in the logs and timeline.
        val body = functionBody(router, "fun\\s+start\\s*\\(")
        assertFalse("start() must not go through the pinning setDevice():\n$body", body.contains("setDevice(Device.BLUETOOTH)"))
    }

    @Test
    fun start_clearsThePin_soItNeverOutlivesItsCall() {
        val body = functionBody(router, "fun\\s+start\\s*\\(")
        assertTrue("start() must reset pinnedDevice:\n$body", body.contains("pinnedDevice = null"))
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
