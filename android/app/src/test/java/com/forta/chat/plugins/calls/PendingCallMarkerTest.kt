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

    private val matrixCallId = "1788970536304tw7II5UU5wJ1O0sv"

    // ---------------------------------------------------------------------
    // seeded(): which marker survives the belt-and-braces write
    // ---------------------------------------------------------------------

    @Test
    fun seeded_writesIntoAnEmptySlot() {
        val fresh = PendingCallMarker.of("call-a", room, now)

        assertSame(fresh, PendingCallMarker.seeded(PendingCallMarker.NONE, fresh))
    }

    @Test
    fun seeded_keepsTheAuthoritativeMarkerForTheSameCall() {
        // The connection's own write ran first and carries the room Telecom
        // knows; the activity's copy must not restamp it.
        val authoritative = PendingCallMarker.of("call-a", room, now)
        val fromTheActivity = PendingCallMarker.of("call-a", room, now + 40)

        assertSame(authoritative, PendingCallMarker.seeded(authoritative, fromTheActivity))
    }

    @Test
    fun seeded_replacesAMarkerLeftBehindByAnotherCall() {
        // This is the window the guard used to lose. A marker is only cleared
        // by the call it belongs to; while that call is still live in Telecom
        // its marker stands, and the next call the user acts on natively —
        // the one Telecom refused, which is why the seed runs at all — found
        // the slot occupied and wrote nothing. The decision the user just made
        // was dropped in favour of one made for a different call.
        val leftBehind = PendingCallMarker.of("call-a", room, now - 5_000)
        val whatTheUserJustDid = PendingCallMarker.of("call-b", "!second:server", now)

        assertSame(
            whatTheUserJustDid,
            PendingCallMarker.seeded(leftBehind, whatTheUserJustDid),
        )
    }

    @Test
    fun seeded_treatsADifferentIdInTheSameRoomAsADifferentCall() {
        // A redial into the same room is the common shape of the stale marker,
        // and the ids are what separate them. Both writes for one call come
        // from the same source (the push's call_id, or the Matrix callId), so
        // an id mismatch here is a different call, not the same call keyed two
        // ways.
        val previousCallInThisRoom = PendingCallMarker.of("call-a", room, now - 5_000)
        val theRedial = PendingCallMarker.of("call-b", room, now)

        assertSame(theRedial, PendingCallMarker.seeded(previousCallInThisRoom, theRedial))
    }

    @Test
    fun seeded_fallsBackToTheRoomWhenOneSideHasNoId() {
        // A path that never learned the call_id still names the call by room,
        // and must not overwrite the marker that does have the id.
        val withId = PendingCallMarker.of("call-a", room, now)
        val roomOnly = PendingCallMarker.of(null, room, now + 40)

        assertSame(withId, PendingCallMarker.seeded(withId, roomOnly))
        assertSame(roomOnly, PendingCallMarker.seeded(roomOnly, PendingCallMarker.of(null, room, now + 80)))
    }

    @Test
    fun seeded_neverWipesAStandingMarkerWithNothingToSay() {
        // `of("", null, now)` collapses to NONE, and seeding that used to be
        // impossible only because the old guard refused to write at all.
        val standing = PendingCallMarker.of("call-a", room, now)

        assertSame(standing, PendingCallMarker.seeded(standing, PendingCallMarker.of("", null, now)))
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

    @Test
    fun clearedFor_retiresAPushMarkerByRoomWhenTheIdsCannotMatch() {
        // What retirePendingMarkersForCall relies on. A connection created
        // from a push is keyed by the push's call_id, which this homeserver
        // fills with the event_id, so the marker holds `$...` while a finalize
        // arrives with the Matrix callId. The two never match and the room is
        // the only shared key — without it the retire is inert on the push
        // path, which is the primary ringer surface.
        val fromPush = PendingCallMarker.of("\$ZM8kQ5-push-event-id", room, now)

        assertSame(PendingCallMarker.NONE, fromPush.clearedFor(matrixCallId, room))
        assertSame(fromPush, fromPush.clearedFor(matrixCallId, "!elsewhere:server"))
    }

    @Test
    fun clearedFor_withoutARoomFallsBackToTheCallId() {
        // JS withholds the room whenever another call it knows about is still
        // live there, so native must then clear on an exact id or not at all.
        val marker = PendingCallMarker.of("call-a", room, now)

        assertSame(marker, marker.clearedFor(matrixCallId, null))
        assertSame(PendingCallMarker.NONE, marker.clearedFor("call-a", null))
    }
}
