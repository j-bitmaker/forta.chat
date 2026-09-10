package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call service's record of the one mic mute its focus listener owns.
 *
 * Pure JVM, like [IncomingRingerLedgerTest]; the wiring into the focus
 * listener and Telecom's outgoing callback is pinned by
 * [FocusLossMuteContractTest].
 */
class FocusLossMuteTest {

    @Test
    fun telecomsLossBeforeItsConnection_mutes_thenTelecomTakingTheCallUnmutes() {
        // The measured outgoing sequence: Telecom takes focus for the call being
        // dialled, and the listener hears that loss before the Connection exists.
        val mute = FocusLossMute()
        assertTrue("no connection yet, so the loss mutes", mute.onTransientLoss(telecomOwnsCall = false))
        assertTrue("onCreateOutgoingConnection releases it", mute.release())
        assertFalse("and only once", mute.release())
    }

    @Test
    fun lossWhileOurTelecomCallIsUp_leavesTheMicOpen() {
        val mute = FocusLossMute()
        assertFalse(mute.onTransientLoss(telecomOwnsCall = true))
        assertFalse("nothing to undo afterwards either", mute.release())
    }

    @Test
    fun regainWithoutAFocusLossMute_neverUnmutes() {
        // A mute the user chose, or JS asked for, must survive focus coming back.
        assertFalse(FocusLossMute().release())
    }

    @Test
    fun repeatedLosses_areUndoneByOneRelease() {
        val mute = FocusLossMute()
        assertTrue(mute.onTransientLoss(telecomOwnsCall = false))
        assertTrue(mute.onTransientLoss(telecomOwnsCall = false))
        assertTrue(mute.release())
        assertFalse(mute.release())
    }

    @Test
    fun clear_keepsACallsMuteFromReachingTheNextCall() {
        val mute = FocusLossMute()
        mute.onTransientLoss(telecomOwnsCall = false)
        mute.clear()
        assertFalse(mute.release())
    }
}
