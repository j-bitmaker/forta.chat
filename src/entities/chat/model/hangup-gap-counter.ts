import { countPeerHangupsAfter, type RawHangupEvent } from "../lib/call-hangup-unread";

/**
 * Counts the peer's call hangups that a gap in the live timeline hides from the unread badge.
 *
 * The sync filter returns at most 4 events per room, so after a burst of events the live
 * timeline restarts from the latest few, and the hangups before them stay in the server's
 * unread count: three missed calls with JS dead took the badge 2 → 9 instead of 2 → 5
 * (hburst1, 2026-09-15). `get` answers from a cache and counts a new gap in the background —
 * pages of `m.call.hangup` backwards from the gap until they pass the read receipt's event
 * or the time this device first saw the push rule — then `onResolved` asks for a recount.
 */
export interface HangupGapQuery {
  roomId: string;
  /** Backward pagination token at the start of the live timeline. */
  fromToken: string;
  /** The event the account's read receipt points at; null counts back to `since`. */
  receiptEventId: string | null;
  myUserId: string;
  since: number;
  /** Since when `m.call.select_answer` is counted too; null or absent when it is not. */
  selectAnswerSince?: number | null;
}

export interface HangupGapPage {
  chunk: readonly RawHangupEvent[];
  end?: string | null;
}

export interface HangupGapDeps {
  fetchHangups(roomId: string, fromToken: string, limit: number): Promise<HangupGapPage | null>;
  fetchEventTs(roomId: string, eventId: string): Promise<number | null>;
  onResolved(): void;
}

export interface HangupGapCounter {
  /** The gap's unread peer hangups, or null while they are being counted. */
  get(query: HangupGapQuery): number | null;
}

const PAGE_SIZE = 50;
const MAX_PAGES = 5;
const MAX_CONCURRENT = 3;
const MAX_CACHED = 500;

export function createHangupGapCounter(
  deps: HangupGapDeps,
  opts: { pageSize?: number; maxPages?: number; maxConcurrent?: number } = {},
): HangupGapCounter {
  const pageSize = opts.pageSize ?? PAGE_SIZE;
  const maxPages = opts.maxPages ?? MAX_PAGES;
  const maxConcurrent = opts.maxConcurrent ?? MAX_CONCURRENT;
  const counted = new Map<string, number>();
  const inFlight = new Set<string>();
  let turnedAway = false;

  const countGap = async (q: HangupGapQuery): Promise<number> => {
    let after = -Infinity;
    if (q.receiptEventId !== null) {
      const ts = await deps.fetchEventTs(q.roomId, q.receiptEventId);
      // Without the receipt's time nothing tells a read hangup from an unread one.
      if (ts === null) return 0;
      after = ts;
    }
    let count = 0;
    let token = q.fromToken;
    const earliest = Math.min(q.since, q.selectAnswerSince ?? Infinity);
    const answeredCallIds = new Set<string>();
    for (let page = 0; page < maxPages; page++) {
      let res: HangupGapPage | null;
      try {
        res = await deps.fetchHangups(q.roomId, token, pageSize);
      } catch (e) {
        console.warn("[hangup-gap-counter] page failed, keeping the count so far:", e);
        break;
      }
      if (!res || res.chunk.length === 0) break;
      count += countPeerHangupsAfter(res.chunk, {
        myUserId: q.myUserId,
        since: q.since,
        selectAnswerSince: q.selectAnswerSince,
        after,
        answeredCallIds,
      });
      // Pages run newest first: one event at or before either bound ends the gap.
      const passedBound = res.chunk.some(
        (e) => typeof e.origin_server_ts === "number" && (e.origin_server_ts <= after || e.origin_server_ts < earliest),
      );
      if (passedBound || !res.end || res.end === token) break;
      token = res.end;
    }
    return count;
  };

  const remember = (key: string, count: number): void => {
    counted.set(key, count);
    if (counted.size <= MAX_CACHED) return;
    const oldest = counted.keys().next().value;
    if (oldest !== undefined) counted.delete(oldest);
  };

  const start = (key: string, q: HangupGapQuery): void => {
    inFlight.add(key);
    void countGap(q)
      .catch((e: unknown) => {
        console.warn("[hangup-gap-counter] count failed:", e);
        return 0;
      })
      .then((count) => {
        inFlight.delete(key);
        remember(key, count);
        const retry = turnedAway;
        turnedAway = false;
        if (count > 0 || retry) deps.onResolved();
      });
  };

  return {
    get(q) {
      const key = [q.myUserId, q.roomId, q.receiptEventId ?? "", q.fromToken, q.since, q.selectAnswerSince ?? ""].join(" ");
      const known = counted.get(key);
      if (known !== undefined) return known;
      if (inFlight.has(key)) return null;
      if (inFlight.size >= maxConcurrent) {
        turnedAway = true;
        return null;
      }
      start(key, q);
      return null;
    },
  };
}
