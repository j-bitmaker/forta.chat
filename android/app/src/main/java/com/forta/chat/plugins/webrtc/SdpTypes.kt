package com.forta.chat.plugins.webrtc

import org.webrtc.SessionDescription

/** Maps the JS description type to libwebrtc's. "rollback" undoes our own offer when the peer's offer wins a glare. */
internal object SdpTypes {
    fun parse(type: String?, default: SessionDescription.Type): SessionDescription.Type = when (type) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> default
    }
}
