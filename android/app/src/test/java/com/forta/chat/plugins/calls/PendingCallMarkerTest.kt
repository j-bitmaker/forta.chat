package com.forta.chat.plugins.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The states that used to be reachable when the marker was three loose fields.
 */
class PendingCallMarkerTest {

    private val now = 1_788_955_981_000L
    private val room = "!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app"

    @Test
    fun of_keepsBothKeysAndTheWriteTime() {
        val marker = PendingCallMarker.of("call-a", room, now)

        assertEquals("call-a", marker.callId)
        assertEquals(room, marker.roomId)
        assertEquals(now, marker.atMs)
        assertFalse(marker.isEmpty)
    }

    @Test
    fun of_collapsesEmptyStringsToNull() {
        val marker = PendingCallMarker.of("call-a", "", now)

        assertEquals("call-a", marker.callId)
        assertEquals(null, marker.roomId)
    }

    @Test
    fun of_withNothingToMatchOn_isNoneRatherThanATimestampedBlank() {
        // A blank marker carrying "now" would read as a fresh marker to the
        // JS matcher, which is how a stale room used to swallow a new call.
        assertSame(PendingCallMarker.NONE, PendingCallMarker.of("", "", now))
        assertSame(PendingCallMarker.NONE, PendingCallMarker.of(null, null, now))
        assertEquals(0L, PendingCallMarker.of(null, null, now).atMs)
    }

    @Test
    fun none_isEmptyAndUnstamped() {
        assertTrue(PendingCallMarker.NONE.isEmpty)
        assertEquals(0L, PendingCallMarker.NONE.atMs)
    }

    @Test
    fun of_neverPairsANewCallIdWithAPreviousRoom() {
        // The onAnswer/onReject path used to write the callId unconditionally
        // and the roomId only when non-empty, so a call arriving with no room
        // inherited the previous call's room — and then got stamped fresh.
        val previous = PendingCallMarker.of("call-a", room, now - 120_000)
        val next = PendingCallMarker.of("call-b", "", now)

        assertEquals("call-b", next.callId)
        assertEquals(
            "a new marker must not inherit the previous room",
            null,
            next.roomId,
        )
        assertEquals(room, previous.roomId)
    }

    @Test
    fun clearedFor_dropsTheWholeMarkerWhenTheCallIdMatches() {
        val marker = PendingCallMarker.of("call-a", room, now)

        assertSame(PendingCallMarker.NONE, marker.clearedFor("call-a", "!other:server"))
    }

    @Test
    fun clearedFor_dropsTheWholeMarkerWhenTheRoomMatches() {
        val marker = PendingCallMarker.of("call-a", room, now)

        // The old code cleared only the matching field here, leaving "call-a"
        // behind — and re-stamped it to now on the way out.
        assertSame(PendingCallMarker.NONE, marker.clearedFor("call-z", room))
    }

    @Test
    fun clearedFor_leavesAMarkerForAnotherCallAlone() {
        val marker = PendingCallMarker.of("call-a", room, now)

        val kept = marker.clearedFor("call-z", "!other:server")

        assertSame(marker, kept)
        assertEquals("the untouched marker keeps its original write time", now, kept.atMs)
    }

    @Test
    fun clearedFor_ignoresEmptyKeys() {
        val marker = PendingCallMarker.of("call-a", room, now)

        assertSame(marker, marker.clearedFor("", ""))
        assertSame(marker, marker.clearedFor(null, null))
    }

    @Test
    fun clearedFor_onAnEmptyMarkerStaysEmpty() {
        assertSame(PendingCallMarker.NONE, PendingCallMarker.NONE.clearedFor("call-a", room))
    }
}
