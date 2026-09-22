package com.forta.chat.plugins.calls

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ringer started from the app's card in Recents must not ring: the task
 * keeps the intent of a call that ended (`ownerb3`, 2026-09-18).
 */
class RingerRelaunchPolicyTest {

    @Test
    fun `a start from Recents is told by the history flag`() {
        // The flags Android logged for the relaunch in ownerb3.
        assertTrue(RingerRelaunchPolicy.isFromRecents(0x34100000))
        assertTrue(RingerRelaunchPolicy.isFromRecents(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
    }

    @Test
    fun `the starts made for a call carry no history flag`() {
        // FCM's own start and the full-screen intent's, as logged on the Samsung.
        assertFalse(RingerRelaunchPolicy.isFromRecents(0x14000000))
        assertFalse(RingerRelaunchPolicy.isFromRecents(0x34000000))
        assertFalse(RingerRelaunchPolicy.isFromRecents(0))
    }

    @Test
    fun `the ringer checks it before anything rings or is dispatched`() {
        val relative = "com/forta/chat/plugins/calls/IncomingCallActivity.kt"
        val source = listOf("src/main/java/$relative", "android/app/src/main/java/$relative")
            .map { File(it) }.firstOrNull { it.exists() }?.readText() ?: error("$relative not found")
        val onCreate = source.substring(source.indexOf("override fun onCreate("))
        val check = onCreate.indexOf("RingerRelaunchPolicy.isFromRecents(intent.flags)")
        assertTrue("onCreate must check the relaunch", check >= 0)
        listOf("currentInstance = this", "intent.getStringExtra(\"action\")", "IncomingRinger.arm(").forEach {
            val at = onCreate.indexOf(it)
            assertTrue("$it must come after the relaunch check", at < 0 || check < at)
        }
    }
}
