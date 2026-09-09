import { describe, it, expect } from "vitest";
import {
  matchesPendingCallMarker,
  pendingCallMarkerOf,
  PENDING_MARKER_ROOM_TTL_MS,
} from "./pending-call-marker";

const NOW = 1_788_955_981_000;
const ROOM = "!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app";

describe("matchesPendingCallMarker — exact callId", () => {
  it("matches the call the marker names", () => {
    const match = matchesPendingCallMarker(
      { callId: "call-a", roomId: null, atMs: NOW },
      { callId: "call-a", now: NOW },
    );

    expect(match).toBe(true);
  });

  it("matches however old the marker is — a callId is never ambiguous", () => {
    const match = matchesPendingCallMarker(
      { callId: "call-a", roomId: ROOM, atMs: NOW - 24 * 60 * 60_000 },
      { callId: "call-a", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(true);
  });

  it("does not match a different callId when there is no room to fall back on", () => {
    const match = matchesPendingCallMarker(
      { callId: "call-a", roomId: null, atMs: NOW },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });
});

describe("matchesPendingCallMarker — roomId fallback", () => {
  it("matches a fresh marker by room when the callId is not comparable", () => {
    // The cold-start-from-push case this fallback exists for: the push
    // payload's call_id is an event_id, so only the room correlates.
    const match = matchesPendingCallMarker(
      { callId: "$event-id-from-push", roomId: ROOM, atMs: NOW - 5_000 },
      { callId: "1788955980269-matrix-id", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(true);
  });

  it("still matches one millisecond inside the TTL", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: NOW - (PENDING_MARKER_ROOM_TTL_MS - 1) },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(true);
  });

  it("stops matching exactly at the TTL", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: NOW - PENDING_MARKER_ROOM_TTL_MS },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });

  it("does not match another room", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: NOW },
      { callId: "call-b", roomId: "!other:matrix.pocketnet.app", now: NOW },
    );

    expect(match).toBe(false);
  });

  it("does not match when the target has no room", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: NOW },
      { callId: "call-b", now: NOW },
    );

    expect(match).toBe(false);
  });
});

describe("matchesPendingCallMarker — regression: the bench incident", () => {
  it("a stale auto-reject marker no longer kills a later call from the same room", () => {
    // 2026-09-09 on the Samsung bench: the ringer auto-rejected at 15:07:24
    // and left this marker; the next call arrived 5m37s later with a fresh
    // callId and was pre-rejected without ever ringing.
    const autoRejectedAt = NOW - (5 * 60_000 + 37_000);

    const match = matchesPendingCallMarker(
      { callId: "1788955612273O7S736BrqFIxqwQS", roomId: ROOM, atMs: autoRejectedAt },
      { callId: "17889559802696wWSVJYT0cFnIWQt", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });

  it("but the call the user actually declined is still rejected back to Matrix", () => {
    const declinedAt = NOW - (5 * 60_000 + 37_000);

    const match = matchesPendingCallMarker(
      { callId: "1788955612273O7S736BrqFIxqwQS", roomId: ROOM, atMs: declinedAt },
      { callId: "1788955612273O7S736BrqFIxqwQS", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(true);
  });
});

describe("matchesPendingCallMarker — degenerate markers", () => {
  it("an empty marker matches nothing", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: null, atMs: 0 },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });

  it("refuses the room fallback on a zero stamp", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: 0 },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });

  it("refuses the room fallback on a negative stamp", () => {
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: -1 },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });

  it("refuses the room fallback when the clock has moved backwards", () => {
    // Native stamps with System.currentTimeMillis(), we read Date.now(); an
    // NTP resync between the two makes the age negative, which would sail
    // through a bare `age < TTL` and restore the unbounded matching.
    const match = matchesPendingCallMarker(
      { callId: null, roomId: ROOM, atMs: NOW + 60 * 60_000 },
      { callId: "call-b", roomId: ROOM, now: NOW },
    );

    expect(match).toBe(false);
  });

  it("keeps the room fallback when the platform reports no write time (iOS)", () => {
    expect(
      matchesPendingCallMarker(
        { callId: null, roomId: ROOM },
        { callId: "call-b", roomId: ROOM, now: NOW },
      ),
    ).toBe(true);

    expect(
      matchesPendingCallMarker(
        { callId: null, roomId: ROOM, atMs: null },
        { callId: "call-b", roomId: ROOM, now: NOW },
      ),
    ).toBe(true);
  });
});

describe("pendingCallMarkerOf", () => {
  it("keeps both keys and the write time", () => {
    expect(pendingCallMarkerOf("call-a", ROOM, NOW)).toEqual({
      callId: "call-a",
      roomId: ROOM,
      atMs: NOW,
    });
  });

  it("collapses empty strings to null", () => {
    expect(pendingCallMarkerOf("call-a", "", NOW)).toEqual({
      callId: "call-a",
      roomId: null,
      atMs: NOW,
    });
  });

  it("is null when there is nothing to match on", () => {
    expect(pendingCallMarkerOf("", "", NOW)).toBeNull();
    expect(pendingCallMarkerOf(null, undefined, NOW)).toBeNull();
  });

  it("never pairs a new callId with a previous room", () => {
    // The write-field-by-field shape this replaces: the callId moved on while
    // the room stayed behind, and the stamp then made the stale room fresh.
    const next = pendingCallMarkerOf("call-b", null, NOW);

    expect(next?.callId).toBe("call-b");
    expect(next?.roomId).toBeNull();
    expect(matchesPendingCallMarker(next!, { callId: "call-c", roomId: ROOM, now: NOW })).toBe(
      false,
    );
  });
});
