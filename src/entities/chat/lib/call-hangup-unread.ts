/**
 * Unread counts without the peer's call hangups.
 *
 * With the account's `m.call.hangup` push rule (`shared/lib/push/call-hangup-push-rule.ts`)
 * the server counts every hangup a peer sends as an unread notification: a missed call
 * raised the chat-list badge by 2 instead of 1 (`hcount-rule`, 2026-09-15). The badge
 * takes back the hangups it can see — the peer's, unread, sent since this device saw the
 * rule. The live timeline holds only the latest events, so an older unread hangup stays
 * counted.
 */
export interface HangupTimelineEvent {
  getId(): string | null | undefined;
  getType(): string;
  getSender(): string | null | undefined;
  getTs(): number;
}

export function unreadPeerHangupCount(
  events: readonly HangupTimelineEvent[],
  opts: { myUserId: string; since: number; hasRead: (eventId: string) => boolean },
): number {
  let count = 0;
  for (const event of events) {
    if (event.getType() !== "m.call.hangup") continue;
    const id = event.getId();
    if (!id || event.getSender() === opts.myUserId || event.getTs() < opts.since) continue;
    if (!opts.hasRead(id)) count++;
  }
  return count;
}

export function unreadCountWithoutHangups(total: number, hangups: number): number {
  return Math.max(0, total - hangups);
}
