/**
 * Unread counts without the peer's call hangups.
 *
 * With the account's `m.call.hangup` push rule (`shared/lib/push/call-hangup-push-rule.ts`)
 * the server counts every hangup a peer sends as an unread notification: a missed call
 * raised the chat-list badge by 2 instead of 1 (`hcount-rule`, 2026-09-15). The badge
 * takes back the hangups it can see — the peer's, unread, sent since this device saw the
 * rule. The live timeline holds only the latest events; the hangups a gap hides from it
 * are counted from `/messages` (`../model/hangup-gap-counter.ts`).
 *
 * The `m.call.select_answer` rule (`shared/lib/push/call-select-answer-push-rule.ts`) adds
 * one more per answered call; it is taken back the same way, dated by its own rule.
 *
 * The invite of a call this account answered — here or on another device — is taken back
 * as well: the default call rule counts it, the read receipt rarely moves during a call,
 * and a call answered in Bastyon left «1» on the chat (`ownerb4`, 2026-09-18). A missed
 * call keeps its invite: that one is the badge the user should see.
 */

/** Since when the server counts a peer's [type], or null when it does not. */
function countedSince(type: unknown, opts: { since: number; selectAnswerSince?: number | null }): number | null {
  if (type === "m.call.hangup") return opts.since;
  if (type === "m.call.select_answer") return opts.selectAnswerSince ?? null;
  return null;
}

export interface HangupTimelineEvent {
  getId(): string | null | undefined;
  getType(): string;
  getSender(): string | null | undefined;
  getTs(): number;
  getContent?(): unknown;
}

function callIdOf(content: unknown): string | null {
  const id = (content as { call_id?: unknown } | null | undefined)?.call_id;
  return typeof id === "string" && id !== "" ? id : null;
}

export function unreadPeerHangupCount(
  events: readonly HangupTimelineEvent[],
  opts: { myUserId: string; since: number; selectAnswerSince?: number | null; hasRead: (eventId: string) => boolean },
): number {
  const answered = new Set<string>();
  for (const event of events) {
    if (event.getType() !== "m.call.answer" || event.getSender() !== opts.myUserId) continue;
    const callId = callIdOf(event.getContent?.());
    if (callId) answered.add(callId);
  }
  let count = 0;
  for (const event of events) {
    const type = event.getType();
    let since = countedSince(type, opts);
    if (type === "m.call.invite") {
      const callId = callIdOf(event.getContent?.());
      since = callId !== null && answered.has(callId) ? -Infinity : null;
    }
    if (since === null) continue;
    const id = event.getId();
    if (!id || event.getSender() === opts.myUserId || event.getTs() < since) continue;
    if (!opts.hasRead(id)) count++;
  }
  return count;
}

/** Whether any event is call signalling: only such a room can hide hangups behind a gap. */
export function hasCallEvent(events: readonly Pick<HangupTimelineEvent, "getType">[]): boolean {
  return events.some((event) => event.getType().startsWith("m.call."));
}

/** A raw event from `/messages`: nothing about its shape is trusted. */
export interface RawHangupEvent {
  content?: unknown;
  type?: unknown;
  event_id?: unknown;
  sender?: unknown;
  origin_server_ts?: unknown;
}

/**
 * The peer's hangups in raw events, sent after the read receipt's event (`after`, its
 * timestamp) and since this device saw the rule — the ones the server counts as unread.
 */
export function countPeerHangupsAfter(
  events: readonly RawHangupEvent[],
  opts: {
    myUserId: string;
    since: number;
    selectAnswerSince?: number | null;
    after: number;
    /** Calls this account answered, kept across pages: pages run newest first, so an answer is met before its invite. */
    answeredCallIds?: Set<string>;
  },
): number {
  const answered = opts.answeredCallIds ?? new Set<string>();
  let count = 0;
  for (const event of events) {
    if (event.type === "m.call.answer" && event.sender === opts.myUserId) {
      const callId = callIdOf(event.content);
      if (callId) answered.add(callId);
      continue;
    }
    let since = countedSince(event.type, opts);
    if (event.type === "m.call.invite") {
      const callId = callIdOf(event.content);
      since = callId !== null && answered.has(callId) ? -Infinity : null;
    }
    if (since === null) continue;
    if (typeof event.event_id !== "string" || event.event_id === "" || event.sender === opts.myUserId) continue;
    const ts = event.origin_server_ts;
    if (typeof ts === "number" && ts > opts.after && ts >= since) count++;
  }
  return count;
}

export function unreadCountWithoutHangups(total: number, hangups: number): number {
  return Math.max(0, total - hangups);
}
