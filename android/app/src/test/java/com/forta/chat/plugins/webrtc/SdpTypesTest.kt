package com.forta.chat.plugins.webrtc

import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.SessionDescription

/**
 * The proxy rolls its own offer back when the peer's offer arrives mid-exchange (the polite side of a glare), as a
 * browser does implicitly. Before, "rollback" fell through to OFFER with an empty SDP and failed, so the offer
 * that came next failed with "Called in wrong state: have-local-offer" (Samsung `glare-in1`, 2026-09-17).
 */
class SdpTypesTest {

    @Test
    fun `a rollback reaches libwebrtc as a rollback`() {
        assertEquals(SessionDescription.Type.ROLLBACK, SdpTypes.parse("rollback", SessionDescription.Type.OFFER))
    }

    @Test
    fun `offer, answer and pranswer map as before`() {
        assertEquals(SessionDescription.Type.OFFER, SdpTypes.parse("offer", SessionDescription.Type.ANSWER))
        assertEquals(SessionDescription.Type.ANSWER, SdpTypes.parse("answer", SessionDescription.Type.OFFER))
        assertEquals(SessionDescription.Type.PRANSWER, SdpTypes.parse("pranswer", SessionDescription.Type.OFFER))
    }

    @Test
    fun `an unknown type keeps the caller's default`() {
        assertEquals(SessionDescription.Type.OFFER, SdpTypes.parse("bogus", SessionDescription.Type.OFFER))
        assertEquals(SessionDescription.Type.ANSWER, SdpTypes.parse(null, SessionDescription.Type.ANSWER))
    }
}
