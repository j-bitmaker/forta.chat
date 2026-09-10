package com.forta.chat.plugins.calls

import android.telecom.Connection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingSurfacePolicyTest {

    private val otherCall = "1788994481551mCLgX9uZdaj9oo66"
    private val newCall = "1788994532677G1ONyGGHydJBlzyO"

    @Test
    fun anOrphanedRingingConnection_doesNotSilenceTheNextCall() {
        // The Samsung repro: swiped away mid-ring, so the activity is gone and
        // the ringer was stopped, but Telecom still holds the RINGING slot.
        assertFalse(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = otherCall,
                slotState = Connection.STATE_RINGING,
                ringingCallId = null,
            ),
        )
    }

    @Test
    fun aRingerActuallyArmedForAnotherCall_stillWins() {
        // Notification-only ringer (revoked full-screen intent) — audible, so
        // it is a real ringer and one at a time remains the rule.
        assertTrue(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = otherCall,
                slotState = Connection.STATE_RINGING,
                ringingCallId = otherCall,
            ),
        )
    }

    @Test
    fun theIncomingActivityOnScreen_alwaysWins() {
        assertTrue(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = true,
                slotCallId = otherCall,
                slotState = Connection.STATE_RINGING,
                ringingCallId = null,
            ),
        )
    }

    @Test
    fun theSameCallAskedTwice_isAbsorbed() {
        assertTrue(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = newCall,
                slotState = Connection.STATE_RINGING,
                ringingCallId = null,
            ),
        )
    }

    @Test
    fun anEstablishedCall_keepsTheSlotAndTheSecondCallerGetsBusy() {
        assertTrue(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = otherCall,
                slotState = Connection.STATE_ACTIVE,
                ringingCallId = null,
            ),
        )
    }

    @Test
    fun anOutgoingCallBeingDialled_isNotDisplacedByAnIncomingOne() {
        // Glare: the user dialled, the peer dialled back. DisplacedConnectionPolicy
        // would admit DIALING; this must not, or the user's own outgoing call
        // dies the moment the incoming invite lands.
        assertTrue(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = otherCall,
                slotState = Connection.STATE_DIALING,
                ringingCallId = null,
            ),
        )
    }

    @Test
    fun anEmptySlotWithNothingOnScreen_showsTheRinger() {
        assertFalse(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = null,
                slotState = null,
                ringingCallId = null,
            ),
        )
    }

    @Test
    fun aPushKeyedSlotIsTreatedAsOwning_asEverywhereElse() {
        // CallSlotPolicy: an event-id slot is unkeyed, not unreachable. Widening
        // that here would let a push-created ringer be displaced by its own
        // /sync twin and ring twice.
        assertTrue(
            IncomingSurfacePolicy.isAlreadyVisibleFor(
                requestedCallId = newCall,
                activityUp = false,
                slotCallId = "\$abcdef123",
                slotState = Connection.STATE_RINGING,
                ringingCallId = null,
            ),
        )
    }
}
