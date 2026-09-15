package com.forta.chat.plugins.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVideoSinksTest {

    private data class Track(val id: String)

    private val ops = mutableListOf<String>()
    private val sinks = RemoteVideoSinks<Track, String>(
        addSink = { track, renderer -> ops += "add ${track.id} -> $renderer" },
        removeSink = { track, renderer -> ops += "remove ${track.id} -> $renderer" },
    )

    @Test
    fun `a renderer attached after the track arrived shows that track, as on an incoming call`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.attach("screen1")

        assertEquals(listOf("add v1 -> screen1"), ops)
        assertEquals(listOf(Track("v1")), sinks.tracks())
    }

    @Test
    fun `a track that arrives after the renderer is attached gets it, as on an outgoing call`() {
        sinks.attach("screen1")
        sinks.keepTrack("pc1", "v1", Track("v1"))

        assertEquals(listOf("add v1 -> screen1"), ops)
    }

    @Test
    fun `a destroyed call screen's renderer comes off, and the next call's track does not get it`() {
        sinks.attach("screen1")
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.forgetAll()
        sinks.detach("screen1")
        ops.clear()

        sinks.keepTrack("pc2", "v2", Track("v2"))
        assertEquals(emptyList<String>(), ops)

        sinks.attach("screen2")
        assertEquals(listOf("add v2 -> screen2"), ops)
    }

    @Test
    fun `detaching the current renderer takes it off every track`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.keepTrack("pc1", "v2", Track("v2"))
        sinks.attach("screen1")
        ops.clear()

        sinks.detach("screen1")
        assertEquals(listOf("remove v1 -> screen1", "remove v2 -> screen1"), ops)

        ops.clear()
        sinks.keepTrack("pc1", "v3", Track("v3"))
        assertEquals(emptyList<String>(), ops)
    }

    @Test
    fun `a new call screen takes the tracks over from the previous one`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.attach("screen1")
        ops.clear()

        sinks.attach("screen2")

        assertEquals(listOf("remove v1 -> screen1", "add v1 -> screen2"), ops)
    }

    @Test
    fun `the previous screen's late destroy leaves the new screen attached`() {
        // A screen torn down after its successor attached must not detach the successor.
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.attach("screen1")
        sinks.attach("screen2")
        ops.clear()

        sinks.detach("screen1")
        assertEquals(emptyList<String>(), ops)

        sinks.keepTrack("pc1", "v2", Track("v2"))
        assertEquals(listOf("add v2 -> screen2"), ops)
    }

    @Test
    fun `attaching the same renderer again adds nothing twice`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.attach("screen1")
        sinks.attach("screen1")

        assertEquals(listOf("add v1 -> screen1"), ops)
    }

    @Test
    fun `a track delivered again under the same id keeps its first sink`() {
        sinks.attach("screen1")
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.keepTrack("pc1", "v1", Track("v1-again"))

        assertEquals(listOf("add v1 -> screen1"), ops)
        assertEquals(listOf(Track("v1")), sinks.tracks())
    }

    @Test
    fun `closing one connection forgets only its tracks and takes the renderer off them`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.keepTrack("pc2", "v2", Track("v2"))
        sinks.attach("screen1")
        ops.clear()

        sinks.forgetPeer("pc1")

        assertEquals(listOf("remove v1 -> screen1"), ops)
        assertEquals(listOf(Track("v2")), sinks.tracks())
    }

    @Test
    fun `closing every connection forgets every track and keeps the screen's renderer until the screen goes`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.attach("screen1")
        ops.clear()

        sinks.forgetAll()
        assertEquals(listOf("remove v1 -> screen1"), ops)
        assertTrue(sinks.tracks().isEmpty())

        sinks.keepTrack("pc2", "v2", Track("v2"))
        assertEquals(listOf("remove v1 -> screen1", "add v2 -> screen1"), ops)
    }

    @Test
    fun `a track the peer removed is forgotten and the renderer comes off it`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.keepTrack("pc1", "v2", Track("v2"))
        sinks.attach("screen1")
        ops.clear()

        sinks.forgetTrack("pc1", "v1")

        assertEquals(listOf("remove v1 -> screen1"), ops)
        assertEquals(listOf(Track("v2")), sinks.tracks())
    }

    @Test
    fun `a removed track that comes back is kept again`() {
        sinks.attach("screen1")
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.forgetTrack("pc1", "v1")
        ops.clear()

        sinks.keepTrack("pc1", "v1", Track("v1"))

        assertEquals(listOf("add v1 -> screen1"), ops)
    }

    @Test
    fun `forgetting a track nobody kept changes nothing`() {
        sinks.keepTrack("pc1", "v1", Track("v1"))
        sinks.attach("screen1")
        ops.clear()

        sinks.forgetTrack("pc1", "v9")
        sinks.forgetTrack("pc2", "v1")

        assertEquals(emptyList<String>(), ops)
        assertEquals(listOf(Track("v1")), sinks.tracks())
    }
}
