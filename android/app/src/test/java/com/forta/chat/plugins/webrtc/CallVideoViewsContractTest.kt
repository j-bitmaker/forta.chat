package com.forta.chat.plugins.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins how the native call screen's video views are driven. Source-level, in
 * the spirit of [TrackAttachContractTest]: the camera, the renderers and their
 * GL context all need a device, and the defects these guard were seen only on
 * one (the Samsung bench, 2026-09-12).
 */
class CallVideoViewsContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        val resolved = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
        return resolved.readText()
    }

    private val manager by lazy { source("com/forta/chat/plugins/webrtc/NativeWebRTCManager.kt") }
    private val callActivity by lazy { source("com/forta/chat/plugins/calls/CallActivity.kt") }

    // -- The self-view mirror follows the camera ------------------------------

    @Test
    fun onlyTheManagerMirrorsTheSelfView() {
        // CallActivity mirrored the preview once at init and never again, so
        // after a flip the back camera's picture read backwards.
        assertFalse(
            "CallActivity must not mirror the self-view itself",
            callActivity.contains("localVideoView.setMirror("),
        )
        val helper = functionBody(manager, "private\\s+fun\\s+applySelfViewMirror\\s*\\(")
        assertTrue("the helper must ask the policy:\n$helper", helper.contains("SelfViewMirror.isMirrored(cameraFrontFacing)"))
        val rest = manager.replace(helper, "")
        assertFalse(
            "every other mirror write must go through applySelfViewMirror:\n" +
                rest.lines().filter { it.contains(".setMirror(") }.joinToString("\n"),
            rest.contains(".setMirror("),
        )
    }

    @Test
    fun cameraSwitch_recordsTheFacing_thenRemirrors() {
        val body = functionBody(manager, "override\\s+fun\\s+onCameraSwitchDone\\s*\\(")
        val record = body.indexOf("cameraFrontFacing = isFrontFacing")
        val apply = body.indexOf("applySelfViewMirror(")
        assertTrue("the switch must record the new facing:\n$body", record >= 0)
        assertTrue("the switch must re-mirror after recording it:\n$body", apply > record)
    }

    @Test
    fun freshCamera_recordsItsFacing_beforeTheCameraIsBound() {
        val body = functionBody(manager, "private\\s+fun\\s+startLocalVideoLocked\\s*\\(")
        val record = body.indexOf("cameraFrontFacing = enumerator.isFrontFacing(cameraName)")
        val bind = body.indexOf("renderer ?: localRenderer")
        val apply = body.indexOf("applySelfViewMirror(sink)")
        assertTrue("the fresh path must record the opened camera's facing:\n$body", record >= 0)
        assertTrue("the facing must be known before the preview is bound:\n$body", record < bind)
        assertTrue("the bound preview must be mirrored for that camera:\n$body", apply > bind)
    }

    @Test
    fun everyBoundPreview_isMirroredForTheOpenCamera() {
        val attach = functionBody(manager, "fun\\s+attachLocalRenderer\\s*\\(")
        val write = attach.indexOf("localRenderer = renderer")
        val apply = attach.indexOf("applySelfViewMirror(renderer)")
        assertTrue("attachLocalRenderer must mirror the renderer it binds:\n$attach", write >= 0 && apply > write)

        val start = functionBody(manager, "private\\s+fun\\s+startLocalVideoLocked\\s*\\(")
        val reuse = start.substring(0, start.indexOf("Camera2Enumerator("))
        assertTrue(
            "the reuse branch must mirror a renderer it newly binds:\n$reuse",
            reuse.contains("applySelfViewMirror(renderer)"),
        )
    }

    @Test
    fun releasedCamera_forgetsItsFacing() {
        val body = functionBody(manager, "private\\s+fun\\s+stopLocalMediaLocked\\s*\\(")
        assertTrue("stopping local media must forget the facing:\n$body", body.contains("cameraFrontFacing = null"))
        assertTrue(
            "the facing is written by the camera thread and read by others",
            manager.contains("@Volatile private var cameraFrontFacing"),
        )
    }

    // -- The call screen gets its GL context before the factory exists --------

    @Test
    fun glContext_isCreatedOnFirstUse_inOnePlace() {
        // On a cold process CallActivity opens before the plugin thread builds
        // the factory. It found no context, skipped its renderer setup for the
        // whole call and left the self-view black (rearcam2).
        assertTrue(
            "getEglBase must create the context under eglLock",
            Regex("fun\\s+getEglBase\\s*\\(\\s*\\)\\s*:\\s*EglBase\\?\\s*=\\s*synchronized\\(eglLock\\)")
                .containsMatchIn(manager),
        )
        val body = functionBody(manager, "fun\\s+getEglBase\\s*\\(")
        assertTrue("getEglBase must create a missing context:\n$body", body.contains("EglBase.create("))
        assertEquals("the context is created in one place", 1, Regex("EglBase\\.create\\(").findAll(manager).count())
        assertTrue("the context is written and read across threads", manager.contains("@Volatile private var eglBase"))
    }

    @Test
    fun factory_sharesTheCallScreensContext_andKeepsItWhenItFails() {
        val body = functionBody(manager, "fun\\s+initialize\\s*\\(")
        assertTrue("initialize must take the context from getEglBase:\n$body", body.contains("getEglBase()"))
        val catchStart = body.indexOf("catch (t: Throwable)")
        assertTrue("initialize must keep its crash guard:\n$body", catchStart >= 0)
        val catchBody = braceBody(body, body.indexOf('{', catchStart) + 1)
        assertFalse(
            "a failed factory must not release a context the call screen may already render with:\n$catchBody",
            catchBody.contains("eglBase?.release()"),
        )
    }

    // -- The remote renderer stays on the remote video tracks ---------------

    private val plugin by lazy { source("com/forta/chat/plugins/webrtc/WebRTCPlugin.kt") }

    @Test
    fun remoteVideo_neverListsTransceiversOrReceivers() {
        // Listing a connection's transceivers or receivers disposes the
        // wrappers the previous listing returned, and a disposed VideoTrack
        // drops every sink added through it. The call screen attached its
        // renderer through one listing and hasRemoteVideoTracks disposed it
        // with the next, so an incoming video call showed no remote frames at
        // all (vin1, vin2, 2026-09-15).
        val listing = Regex("""\.transceivers\b|\.receivers\b|getTransceivers\(|getReceivers\(""")
        for ((name, src) in listOf("NativeWebRTCManager" to manager, "WebRTCPlugin" to plugin)) {
            val hits = codeLines(src).filter { listing.containsMatchIn(it) }
            assertTrue("$name must not list transceivers or receivers:\n${hits.joinToString("\n")}", hits.isEmpty())
        }
    }

    @Test
    fun remoteVideoTracks_areKeptInOnAddTrack_beforeThePluginShowsTheView() {
        val body = functionBody(manager, "override\\s+fun\\s+onAddTrack\\s*\\(")
        val keep = body.indexOf("remoteVideo.keepTrack(")
        val forward = body.indexOf("listener.onAddTrack(")
        assertTrue("onAddTrack must keep a remote video track:\n$body", keep >= 0)
        assertTrue("the track must have the renderer before the plugin shows the view:\n$body", forward > keep)
        assertFalse("the plugin must not attach remote sinks on its own", plugin.contains("addRemoteTrackSink("))
    }

    @Test
    fun remoteRenderer_isAttachedAndCheckedThroughTheKeptTracks() {
        val attach = functionBody(manager, "fun\\s+attachRemoteRenderer\\s*\\(")
        assertTrue("attachRemoteRenderer must hand the renderer to the kept tracks:\n$attach", attach.contains("remoteVideo.attach(renderer)"))
        val detach = functionBody(manager, "fun\\s+detachRemoteRenderer\\s*\\(")
        assertTrue("detachRemoteRenderer must take the renderer off the kept tracks:\n$detach", detach.contains("remoteVideo.detach(renderer)"))
        val has = functionBody(manager, "fun\\s+hasRemoteVideoTracks\\s*\\(")
        assertTrue("hasRemoteVideoTracks must read the kept tracks:\n$has", has.contains("remoteVideo.tracks()"))
    }

    @Test
    fun callScreen_detachesItsRemoteRenderer_beforeReleasingIt() {
        // The manager kept the destroyed screen's renderer, so the next
        // incoming call's track fed a released view (vin2: "Dropping frame -
        // Not initialized or already released" for the whole call).
        val body = functionBody(callActivity, "override\\s+fun\\s+onDestroy\\s*\\(")
        val detach = body.indexOf("detachRemoteRenderer(remoteVideoView)")
        val release = body.indexOf("remoteVideoView.release()")
        assertTrue("onDestroy must detach the remote renderer:\n$body", detach >= 0)
        assertTrue("the renderer must come off the tracks before it is released:\n$body", detach < release)
    }

    @Test
    fun closedConnections_forgetTheirRemoteTracks() {
        val close = functionBody(manager, "fun\\s+closePeerConnection\\s*\\(")
        assertTrue("closePeerConnection must forget its tracks:\n$close", close.contains("remoteVideo.forgetPeer(peerId)"))
        val closeAll = functionBody(manager, "private\\s+fun\\s+closeAllPeerConnectionsLocked\\s*\\(")
        assertTrue("closing every connection must forget every track:\n$closeAll", closeAll.contains("remoteVideo.forgetAll()"))
        val create = functionBody(manager, "fun\\s+createPeerConnection\\s*\\(")
        val replaced = create.substring(0, create.indexOf("RTCConfiguration("))
        assertTrue("a replaced connection must forget its tracks:\n$replaced", replaced.contains("remoteVideo.forgetPeer(peerId)"))
    }

    @Test
    fun removedRemoteTracks_areForgotten() {
        // The kept tracks are a cache, unlike the old listing, so a track the
        // peer removes must leave it or the call screen keeps counting it.
        val body = functionBody(manager, "override\\s+fun\\s+onRemoveTrack\\s*\\(")
        assertTrue("onRemoveTrack must forget a removed remote video track:\n$body", body.contains("remoteVideo.forgetTrack(peerId,"))
    }

    private fun codeLines(src: String): List<String> = src.lines().filterNot {
        val line = it.trimStart()
        line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
    }

    // -- helpers --------------------------------------------------------------

    private fun functionBody(src: String, signaturePattern: String): String {
        val match = Regex("$signaturePattern[^{]*\\{").find(src)
            ?: error("Could not find /$signaturePattern/ in source")
        return braceBody(src, match.range.last + 1)
    }

    /** Brace-counted body starting just after an opening brace. */
    private fun braceBody(src: String, start: Int): String {
        var depth = 1
        var i = start
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
