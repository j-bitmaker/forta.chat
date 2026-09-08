package com.forta.chat.plugins.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the wiring of [TrackAttachPolicy] into [NativeWebRTCManager] and the
 * lock around local video. Source-level assertions in the spirit of
 * [com.forta.chat.plugins.calls.CallTeardownContractTest]: the manager needs
 * a PeerConnectionFactory and a camera to run, and the race this guards is a
 * main-thread-versus-plugin-thread timing that no JVM test can replay.
 */
class TrackAttachContractTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
        val resolved = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("$relative not found. Tried: $candidates from ${File(".").absolutePath}")
        return resolved.readText()
    }

    private val manager by lazy { source("com/forta/chat/plugins/webrtc/NativeWebRTCManager.kt") }
    private val callActivity by lazy { source("com/forta/chat/plugins/calls/CallActivity.kt") }

    // -- One rule, one place ---------------------------------------------------

    @Test
    fun onlyTheAttachHelperAddsLocalTracks() {
        val helper = functionBody(manager, "private\\s+fun\\s+attachLocalTrackLocked\\s*\\(")
        assertTrue("the helper must add the track:\n$helper", helper.contains(".addTrack("))
        assertTrue("the helper must ask the policy:\n$helper", helper.contains("TrackAttachPolicy.targets("))
        assertTrue("the helper must read what the senders already carry:\n$helper", helper.contains(".senders"))

        // Screen share swaps the camera track on an existing sender and only
        // adds when there is none; it is a different track on a different
        // stream and stays outside the rule.
        val screenShare = functionBody(manager, "fun\\s+startScreenCapture\\s*\\(")
        val rest = manager.replace(helper, "").replace(screenShare, "")
        assertFalse(
            "every other addTrack must go through attachLocalTrackLocked:\n" +
                rest.lines().filter { it.contains(".addTrack(") }.joinToString("\n"),
            rest.contains(".addTrack("),
        )
    }

    @Test
    fun createPeerConnection_attachesExistingTracks_throughTheRule() {
        val body = functionBody(manager, "fun\\s+createPeerConnection\\s*\\(")
        assertTrue(body.contains("attachLocalTrackLocked(it, \"audio\", peerId"))
        assertTrue(body.contains("attachLocalTrackLocked(it, \"video\", peerId"))
    }

    // -- Surviving tracks reach the connections that lack them ---------------

    @Test
    fun startLocalAudio_reuseBranch_attachesInsteadOfReturning() {
        val body = functionBody(manager, "private\\s+fun\\s+startLocalAudioLocked\\s*\\(")
        val reuse = body.indexOf("attachLocalTrackLocked(track, \"audio\", peerId, \"startLocalAudio(reuse)\")")
        val fresh = body.indexOf("createAudioSource(")
        assertTrue("the reuse branch must attach through the rule:\n$body", reuse >= 0)
        assertTrue("the reuse branch comes before the fresh path:\n$body", reuse < fresh)
        assertFalse("no peerId gate is left in front of the attach:\n$body", body.contains("if (peerId.isNotEmpty())"))
    }

    @Test
    fun startLocalVideo_reuseBranch_attachesInsteadOfReturning() {
        val body = functionBody(manager, "private\\s+fun\\s+startLocalVideoLocked\\s*\\(")
        val reuse = body.indexOf("attachLocalTrackLocked(track, \"video\", peerId, \"startLocalVideo(reuse)\")")
        val fresh = body.indexOf("Camera2Enumerator(")
        assertTrue("the reuse branch must attach through the rule:\n$body", reuse >= 0)
        assertTrue("the reuse branch comes before the fresh path:\n$body", reuse < fresh)
        assertFalse("no peerId gate is left in front of the attach:\n$body", body.contains("if (peerId.isNotEmpty())"))
    }

    // -- One camera ----------------------------------------------------------

    @Test
    fun startLocalVideo_runsUnderTheMediaLock() {
        // CallActivity (main thread) and startLocalMedia (plugin thread) both
        // call this for one outgoing video call; unlocked, both saw a null
        // track and each opened the camera.
        val signature = Regex("fun\\s+startLocalVideo\\s*\\([^)]*\\)\\s*=\\s*synchronized\\(mediaLock\\)")
        assertTrue("startLocalVideo must be synchronized(mediaLock)", signature.containsMatchIn(manager))
        val body = functionBody(manager, "fun\\s+startLocalVideo\\s*\\(")
        assertTrue(body.contains("startLocalVideoLocked(peerId, renderer)"))
    }

    @Test
    fun callActivity_bindsThePreview_withoutOpeningTheCamera() {
        // launchCallUI precedes placeVideoCall; the Activity's onCreate must
        // not be the second thread to open the camera, and must not wait on
        // mediaLock behind the media-release worker either.
        val body = functionBody(callActivity, "private\\s+fun\\s+initVideoRenderers\\s*\\(")
        assertTrue("initVideoRenderers must bind the renderer:\n$body", body.contains("attachLocalRenderer(localVideoView)"))
        assertFalse("initVideoRenderers must not start the camera:\n$body", body.contains("startLocalVideo("))
    }

    @Test
    fun attachLocalRenderer_isLockFree_andTheFreshTrackPicksTheRendererUp() {
        val bind = functionBody(manager, "fun\\s+attachLocalRenderer\\s*\\(")
        assertFalse("attachLocalRenderer must not take mediaLock:\n$bind", bind.contains("mediaLock"))
        val bindOrder = bind.indexOf("localRenderer = renderer") to bind.indexOf("localVideoTrack")
        assertTrue("bind must write the renderer before reading the track:\n$bind", bindOrder.first in 0 until bindOrder.second)

        val fresh = functionBody(manager, "private\\s+fun\\s+startLocalVideoLocked\\s*\\(")
        val publish = fresh.indexOf("localVideoTrack = track")
        val read = fresh.indexOf("renderer ?: localRenderer")
        assertTrue("the fresh path must fall back to the bound renderer:\n$fresh", read >= 0)
        assertTrue("the fresh path must publish the track before reading the renderer:\n$fresh", publish in 0 until read)
        assertTrue("localRenderer must be volatile", manager.contains("@Volatile private var localRenderer"))
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
