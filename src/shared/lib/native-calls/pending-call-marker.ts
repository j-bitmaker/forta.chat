/**
 * Matching policy for the native "the user already answered / declined this
 * call" markers.
 *
 * The markers are written by the native ringer (Kotlin `CallConnection.
 * onAnswer` / `onReject`, plus `IncomingCallActivity`'s belt-and-braces
 * writes) and consumed by `handleIncomingCall` once Matrix finally delivers
 * the invite. Matching has to fall back to `roomId`, because this
 * homeserver's push payload carries `room_id` but its `call_id` is really an
 * event_id and never equals the Matrix SDK's `call.callId` — on the
 * cold-start-from-push path the callId simply is not comparable.
 *
 * That fallback is also how a marker nobody consumed can reject an entirely
 * different, later call from the same room. Observed on the bench 2026-09-09:
 * the ringer's 30 s auto-reject wrote a marker, nothing consumed it, and the
 * next call from the same peer — a fresh callId, 5.5 minutes later — was
 * rejected before it ever rang (no `onCreateIncomingConnection`, no ringer;
 * the caller saw "declined"). So the room-scoped branch holds only while the
 * marker is younger than one invite lifetime: past that, no live invite could
 * legitimately belong to it, because the SDK has already expired the invite
 * itself.
 */
import { DEFAULT_INVITE_LIFETIME_MS } from "./invite-ttl";

/**
 * How long a marker may still be matched by roomId alone. Tied to the invite
 * lifetime on purpose: the two must move together, since a marker that
 * outlives the invite it belongs to can only ever match the wrong call.
 *
 * A single constant is enough because `invite-ttl` only reads a longer
 * `content.lifetime` (up to MAX_INVITE_LIFETIME_MS) off the invite event,
 * and this homeserver's invites carry the SDK default. The two ages are
 * measured from different origins anyway — this one from the user's tap,
 * that one from `origin_server_ts` — so they are neighbours, not the same
 * number.
 */
export const PENDING_MARKER_ROOM_TTL_MS = DEFAULT_INVITE_LIFETIME_MS;

export interface PendingCallMarker {
  callId: string | null;
  roomId: string | null;
  /**
   * Wall-clock ms when the native side wrote the marker; 0 when there is no
   * marker. `null`/absent means the platform cannot report a write time — the
   * iOS adapter derives markers from live CallKit state rather than a stored
   * field, and keeps the pre-TTL behaviour.
   */
  atMs?: number | null;
}

/**
 * Builds a marker, or `null` when there is nothing to match on.
 *
 * The only way to make one: the callId and the roomId name the same call, so
 * they are always assigned together. Writing them field by field is what let
 * a new call's id sit beside a previous call's room — and, once a write time
 * joined them, let that stale room be presented as freshly marked. Mirrors
 * `PendingCallMarker.of` on the Kotlin side.
 */
export function pendingCallMarkerOf(
  callId: string | null | undefined,
  roomId: string | null | undefined,
  atMs: number | null,
): PendingCallMarker | null {
  const call = callId || null;
  const room = roomId || null;
  if (!call && !room) return null;
  return { callId: call, roomId: room, atMs };
}

export interface PendingCallMarkerTarget {
  /** `matrixCall.callId` of the invite being handled. */
  callId: string;
  /** `matrixCall.roomId`, when known. */
  roomId?: string;
  /** Override "now" — only used in tests. Production callers omit. */
  now?: number;
}

/**
 * True when `marker` refers to the call described by `target`.
 */
export function matchesPendingCallMarker(
  marker: PendingCallMarker,
  target: PendingCallMarkerTarget,
): boolean {
  // An exact callId names the very call the user acted on. Never ambiguous,
  // so it matches whatever its age.
  if (marker.callId && marker.callId === target.callId) return true;

  if (!marker.roomId || !target.roomId || marker.roomId !== target.roomId) {
    return false;
  }

  const { atMs } = marker;
  // Platform cannot report a write time (iOS): behave as before.
  if (atMs === null || atMs === undefined) return true;
  // No marker, or a corrupt stamp — never widen a match on a bad timestamp.
  if (atMs <= 0) return false;

  // Native stamps with System.currentTimeMillis() and we read Date.now();
  // both are wall clocks, so an NTP resync or a manual clock change between
  // the two makes the age negative. Fail closed: a negative age means the
  // stamp cannot be trusted, and trusting it would restore exactly the
  // unbounded matching this guard exists to prevent.
  const age = (target.now ?? Date.now()) - atMs;
  return age >= 0 && age < PENDING_MARKER_ROOM_TTL_MS;
}
