package com.forta.chat.plugins.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfViewMirrorTest {

    @Test
    fun `front camera preview is mirrored`() {
        assertTrue(SelfViewMirror.isMirrored(true))
    }

    @Test
    fun `back camera preview is shown as it is, so text in frame reads normally`() {
        assertFalse(SelfViewMirror.isMirrored(false))
    }

    @Test
    fun `before a camera opens the preview is mirrored, as for the front camera a call starts with`() {
        assertTrue(SelfViewMirror.isMirrored(null))
    }
}
