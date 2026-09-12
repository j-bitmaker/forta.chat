package com.forta.chat.plugins.webrtc

/**
 * Whether the self-view is drawn mirrored.
 *
 * A front camera preview is mirrored, so it moves the way a mirror does; a
 * back camera preview shows the scene as it is, or text in frame reads
 * backwards. Before a camera is open the answer is "mirrored": a call opens
 * the front camera first, and the real facing replaces the guess as soon as
 * the capturer starts. Only the local preview is affected — the frames sent
 * to the other side are never mirrored.
 */
object SelfViewMirror {

    fun isMirrored(frontFacing: Boolean?): Boolean = frontFacing != false
}
