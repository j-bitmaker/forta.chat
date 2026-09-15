package com.forta.chat.plugins.webrtc

/**
 * The remote video tracks of the live connections and the renderer that shows them.
 *
 * Tracks are kept as onAddTrack delivered them, and the renderer is added to and
 * taken off those same objects. Listing a connection's transceivers or receivers
 * disposes the wrappers the previous listing returned, and a disposed VideoTrack
 * drops every sink added through it: the call screen attached its renderer through
 * one listing and the next listing took it off again, so an incoming video call,
 * whose tracks arrive before the screen opens, showed no remote picture.
 *
 * A renderer is detached only while it is the current one, so a screen torn down
 * after another screen attached cannot take that screen's renderer off. Generic over
 * the track and renderer types so these rules run without a device.
 */
class RemoteVideoSinks<T : Any, R : Any>(
    private val addSink: (T, R) -> Unit,
    private val removeSink: (T, R) -> Unit,
) {
    private val tracksByPeer = LinkedHashMap<String, LinkedHashMap<String, T>>()
    private var renderer: R? = null

    /** Keeps a remote video track; a track id already kept for the peer is ignored. */
    @Synchronized
    fun keepTrack(peerId: String, trackId: String, track: T) {
        val tracks = tracksByPeer.getOrPut(peerId) { LinkedHashMap() }
        if (tracks.containsKey(trackId)) return
        tracks[trackId] = track
        renderer?.let { addSink(track, it) }
    }

    /** Forgets a track the peer removed and takes the renderer off it. */
    @Synchronized
    fun forgetTrack(peerId: String, trackId: String) {
        val tracks = tracksByPeer[peerId] ?: return
        val track = tracks.remove(trackId) ?: return
        if (tracks.isEmpty()) tracksByPeer.remove(peerId)
        renderer?.let { removeSink(track, it) }
    }

    @Synchronized
    fun attach(renderer: R) {
        if (this.renderer === renderer) return
        this.renderer?.let { previous -> eachTrack { removeSink(it, previous) } }
        this.renderer = renderer
        eachTrack { addSink(it, renderer) }
    }

    @Synchronized
    fun detach(renderer: R) {
        if (this.renderer !== renderer) return
        eachTrack { removeSink(it, renderer) }
        this.renderer = null
    }

    /** Forgets a closed connection's tracks and takes the renderer off them. */
    @Synchronized
    fun forgetPeer(peerId: String) {
        val tracks = tracksByPeer.remove(peerId) ?: return
        renderer?.let { current -> tracks.values.forEach { removeSink(it, current) } }
    }

    /** Forgets every track; the renderer stays until its screen detaches it. */
    @Synchronized
    fun forgetAll() {
        renderer?.let { current -> eachTrack { removeSink(it, current) } }
        tracksByPeer.clear()
    }

    @Synchronized
    fun tracks(): List<T> = tracksByPeer.values.flatMap { it.values }

    private fun eachTrack(action: (T) -> Unit) {
        tracksByPeer.values.forEach { tracks -> tracks.values.forEach(action) }
    }
}
