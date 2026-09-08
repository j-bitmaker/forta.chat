package com.forta.chat.plugins.webrtc

/**
 * Decides which peer connections a local track has to be added to.
 *
 * Every path that produces or reuses a local track — `createPeerConnection`
 * attaching what already exists, `startLocalAudio`/`startLocalVideo` on a
 * fresh or a surviving track — used to carry its own copy of the rule, and
 * the copies disagreed: a surviving track with an empty `peerId` was added to
 * nothing, a named `peerId` was added to without looking, which throws once
 * the connection already carries the track. One rule, keyed by track id:
 *
 * - empty `peerId` means "every connection that does not have it yet";
 * - a named `peerId` means "that connection, if it does not have it yet";
 * - a connection that already carries the track is never touched.
 *
 * Pure: the caller snapshots `sendersByPc` (connection id → ids of the tracks
 * its senders hold) and applies the result. Iteration order of the map is
 * kept so logs and tests read in creation order.
 */
object TrackAttachPolicy {

    fun targets(peerId: String, trackId: String, sendersByPc: Map<String, Set<String>>): List<String> {
        if (peerId.isNotEmpty()) {
            val held = sendersByPc[peerId] ?: return emptyList()
            return if (trackId in held) emptyList() else listOf(peerId)
        }
        return sendersByPc.entries
            .filter { (_, held) -> trackId !in held }
            .map { (id, _) -> id }
    }
}
