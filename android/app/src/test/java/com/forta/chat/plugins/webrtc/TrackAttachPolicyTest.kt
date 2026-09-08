package com.forta.chat.plugins.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAttachPolicyTest {

    private val audio = "audio0"

    @Test
    fun `no peer connections means nothing to attach to`() {
        assertEquals(emptyList<String>(), TrackAttachPolicy.targets("", audio, emptyMap()))
        assertEquals(emptyList<String>(), TrackAttachPolicy.targets("pc_1", audio, emptyMap()))
    }

    @Test
    fun `empty peerId attaches to every connection that lacks the track, in creation order`() {
        val pcs = linkedMapOf("pc_1" to emptySet<String>(), "pc_2" to emptySet())
        assertEquals(listOf("pc_1", "pc_2"), TrackAttachPolicy.targets("", audio, pcs))
    }

    @Test
    fun `empty peerId skips the connection that already carries the track`() {
        // The surviving-track case: pc_1 was auto-attached at creation, pc_2
        // came later and never got it.
        val pcs = linkedMapOf("pc_1" to setOf(audio), "pc_2" to emptySet())
        assertEquals(listOf("pc_2"), TrackAttachPolicy.targets("", audio, pcs))
    }

    @Test
    fun `empty peerId with every connection already carrying the track is a no-op`() {
        val pcs = linkedMapOf("pc_1" to setOf(audio), "pc_2" to setOf(audio, "video0"))
        assertEquals(emptyList<String>(), TrackAttachPolicy.targets("", audio, pcs))
    }

    @Test
    fun `named peerId attaches to that connection only`() {
        val pcs = linkedMapOf("pc_1" to emptySet<String>(), "pc_2" to emptySet())
        assertEquals(listOf("pc_2"), TrackAttachPolicy.targets("pc_2", audio, pcs))
    }

    @Test
    fun `named peerId never adds a track the connection already has`() {
        // addTrack on a connection that already holds the track throws in
        // libwebrtc; the policy is the guard.
        val pcs = linkedMapOf("pc_1" to setOf(audio))
        assertEquals(emptyList<String>(), TrackAttachPolicy.targets("pc_1", audio, pcs))
    }

    @Test
    fun `unknown peerId attaches to nothing`() {
        val pcs = linkedMapOf("pc_1" to emptySet<String>())
        assertEquals(emptyList<String>(), TrackAttachPolicy.targets("pc_9", audio, pcs))
    }

    @Test
    fun `the check is by track id, another track of the same kind does not count`() {
        // A connection holding a different audio track still needs this one;
        // the single-capturer lock upstream is what keeps that from being a
        // second m-line in practice.
        val pcs = linkedMapOf("pc_1" to setOf("audio_old"))
        assertEquals(listOf("pc_1"), TrackAttachPolicy.targets("", audio, pcs))
    }
}
