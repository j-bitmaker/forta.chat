import type { Message } from "../model/types";

/**
 * Collapses the duplicate call records a single call can leave in a room.
 *
 * Matrix records one `m.call.hangup` per participant who ends the call, so when
 * both sides hang up — which happens constantly, since the other person taps
 * the red button the moment you do — the room carries two events with different
 * event ids describing the same call. The timeline then shows the call twice.
 * Users report this as "I called twice and got four call entries" (#1027).
 *
 * Keeps the first record for each `callId` in the order given, so the surviving
 * entry is the earliest one and nothing else in the timeline shifts. Records
 * from before `callId` was stored have no id to group on and are all kept —
 * dropping them on some other heuristic would silently rewrite old history.
 */
export function dedupeCallEvents<T extends { callInfo?: Message["callInfo"] }>(
  messages: readonly T[],
): T[] {
  const seen = new Set<string>();
  const out: T[] = [];

  for (const message of messages) {
    const callId = message.callInfo?.callId;
    if (!callId) {
      out.push(message);
      continue;
    }
    if (seen.has(callId)) continue;
    seen.add(callId);
    out.push(message);
  }

  return out;
}

type CallRecord = Pick<Message, "id" | "_key" | "callInfo">;

/**
 * Maps every id {@link dedupeCallEvents} drops onto the id that survived it.
 *
 * Anything holding a message id across the collapse needs this. The read
 * watermark is the case that bites: the second hangup is the newest event in
 * the room, so it is exactly what "last read" tends to name — and once that
 * record is collapsed away, a lookup for its id finds nothing at all.
 */
export function collapsedCallEventIds(
  messages: readonly CallRecord[],
): Map<string, string> {
  const survivors = new Map<string, CallRecord>();
  const collapsed = new Map<string, string>();

  for (const message of messages) {
    const callId = message.callInfo?.callId;
    if (!callId) continue;

    const survivor = survivors.get(callId);
    if (!survivor) {
      survivors.set(callId, message);
      continue;
    }

    // Callers match on either id, so either one resolves the anchor.
    const survivorId = survivor._key ?? survivor.id;
    if (message.id) collapsed.set(message.id, survivorId);
    if (message._key) collapsed.set(message._key, survivorId);
  }

  return collapsed;
}
