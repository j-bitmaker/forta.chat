package com.forta.chat.plugins.calls

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the wiring around [PendingCallMarker].
 *
 * The marker's own behaviour is covered by [PendingCallMarkerTest]; what a
 * unit test cannot reach is the wiring, because [CallConnection] extends
 * android.telecom.Connection and will not load off-device. So this pins by
 * source text — as [CallSlotContractTest] does for the slot rule — that the
 * marker really is held atomically, that every write goes through it, and that
 * the bridge hands its write time to JS.
 */
class PendingMarkerStampContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        return candidates.map { File(it) }.firstOrNull { it.exists() }?.readText()
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
    }

    private val service by lazy { source("com/forta/chat/plugins/calls/CallConnectionService.kt") }
    private val plugin by lazy { source("com/forta/chat/plugins/calls/CallPlugin.kt") }
    private val activity by lazy { source("com/forta/chat/plugins/calls/IncomingCallActivity.kt") }

    @Test
    fun markersAreHeldAtomically() {
        for (kind in listOf("Answer", "Reject")) {
            assertTrue(
                "pending$kind must live behind an AtomicReference — it is written from " +
                    "Telecom callbacks and read from Capacitor's plugin thread:\n$service",
                service.contains("private val pending${kind}Ref = AtomicReference(PendingCallMarker.NONE)"),
            )
            assertTrue(
                "takePending$kind must read and clear in one step, or a concurrent write is lost:\n$service",
                service.contains("pending${kind}Ref.getAndSet(PendingCallMarker.NONE)"),
            )
        }
    }

    @Test
    fun noLooseMarkerFieldsSurvive() {
        // Three separate vars are what let a call's id sit next to another
        // call's room; nothing may reintroduce them.
        for (src in listOf(service, plugin, activity)) {
            for (field in listOf(
                "pendingAnswerCallId", "pendingAnswerRoomId",
                "pendingRejectCallId", "pendingRejectRoomId",
            )) {
                assertTrue(
                    "$field must not come back as a separate field",
                    !src.contains("var $field") && !src.contains("$field ="),
                )
            }
        }
    }

    @Test
    fun everyWriteGoesThroughTheMarkerAndCarriesAWriteTime() {
        for (fn in listOf("onAnswer", "onReject")) {
            val body = functionBody(service, "override\\s+fun\\s+$fn\\s*\\(")
            assertTrue(
                "$fn must write the marker whole, so the id and the room always name one call:\n$body",
                body.contains("PendingCallMarker.of(callId, roomId, System.currentTimeMillis())"),
            )
        }
        val clear = functionBody(service, "fun\\s+clearPendingFor\\s*\\(")
        assertTrue(
            "clearPendingFor must drop the marker whole, not one field:\n$clear",
            clear.contains("it.clearedFor(cid, rid)"),
        )
        for (seed in listOf("seedPendingAnswerIfEmpty", "seedPendingRejectIfEmpty")) {
            assertTrue(
                "$seed must be the only belt-and-braces write, stamped:\n$activity",
                activity.contains("CallConnection.$seed("),
            )
        }
        assertTrue(
            "the belt-and-braces writes must stamp the clock:\n$activity",
            activity.contains("PendingCallMarker.of(callId, roomIdForPending, System.currentTimeMillis())"),
        )
    }

    @Test
    fun bothGettersHandTheWriteTimeToJs() {
        for (kind in listOf("Answer", "Reject")) {
            val body = functionBody(plugin, "fun\\s+getPending$kind\\s*\\(")
            assertTrue(
                "getPending$kind must take the marker atomically:\n$body",
                body.contains("CallConnection.takePending$kind()"),
            )
            assertTrue(
                "getPending$kind must hand the write time to JS:\n$body",
                body.contains("ret.put(\"atMs\", marker.atMs)"),
            )
        }
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
