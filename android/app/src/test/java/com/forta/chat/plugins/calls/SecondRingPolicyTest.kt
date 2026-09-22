package com.forta.chat.plugins.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A second call push must not take over a ringer that still rings for a call
 * from another room. Found on the Samsung 2026-09-10 (`rebind2`): the screen
 * repainted to a caller JS had already rejected, «Принять» could connect
 * nobody, and the first call's timer closed it 11 s later. The wiring is pinned
 * in [SecondRingContractTest].
 */
class SecondRingPolicyTest {

    private val roomA = "!a:matrix.pocketnet.app"
    private val roomB = "!b:matrix.pocketnet.app"
    private val callA = "1789069639285hgNn3nMdc8lxRzqJ"
    private val callB = "1789069660105wNd11mr5fMJ9bMkp"

    @Test
    fun nothingRinging_thePushPresentsItsCall() {
        assertTrue(SecondRingPolicy.mayTakeOverRinger(callB, roomB, ringingCallId = null, ringingRoomId = null))
    }

    @Test
    fun aCallFromAnotherRoom_leavesTheScreenToTheRingingCall() {
        assertFalse(SecondRingPolicy.mayTakeOverRinger(callB, roomB, ringingCallId = callA, ringingRoomId = roomA))
    }

    @Test
    fun aRedialFromTheSameRoom_takesTheScreenOver() {
        // No hangup push arrives, so the old ringer may belong to a call the
        // caller already gave up on.
        assertTrue(SecondRingPolicy.mayTakeOverRinger(callB, roomA, ringingCallId = callA, ringingRoomId = roomA))
    }

    @Test
    fun theSameCallAgain_isNotASecondCall() {
        assertTrue(SecondRingPolicy.mayTakeOverRinger(callA, roomA, ringingCallId = callA, ringingRoomId = roomA))
    }

    @Test
    fun anUnknownRingingRoom_keepsTheRepaint() {
        assertTrue(SecondRingPolicy.mayTakeOverRinger(callB, roomB, ringingCallId = callA, ringingRoomId = null))
        assertTrue(SecondRingPolicy.mayTakeOverRinger(callB, roomB, ringingCallId = callA, ringingRoomId = ""))
    }

    @Test
    fun aRingingCallKeyedByEventId_cannotBeComparedAndKeepsTheRepaint() {
        val eventKeyed = "\$iHtOfWv-V39AWQc32T1PKQcsSilJ7_4PlqNdddLPKCE"
        assertTrue(SecondRingPolicy.mayTakeOverRinger(callB, roomB, ringingCallId = eventKeyed, ringingRoomId = roomA))
    }
}
