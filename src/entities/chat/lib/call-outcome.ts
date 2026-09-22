/**
 * Whether a call's hangup record reads as missed.
 *
 * The hangup alone cannot tell. The caller's SDK sends `user_hangup` both when they give up
 * while the call still rings and when they end a call that was talking, so only
 * `invite_timeout` names an unanswered call outright. The rest comes from the room's other
 * call events: who sent the invite, and whether anyone answered it. A call the caller ended
 * before any answer reads as missed, as messengers show a call the other side cancelled. The
 * callee's own hangup before an answer is a decline, not a miss. When the invite is out of
 * view the record keeps the old reading, which knew only the timeout.
 */

type RawEvent = Record<string, unknown>;

export interface CallEventIndex {
  /** call_id → sender of its `m.call.invite`. */
  readonly callers: ReadonlyMap<string, string>;
  /** call_ids with an `m.call.answer`, or a caller's `m.call.select_answer` that implies one. */
  readonly answered: ReadonlySet<string>;
}

const ANSWER_TYPES = new Set(["m.call.answer", "m.call.select_answer"]);

function callIdOf(raw: RawEvent): string | undefined {
  const content = raw.content as RawEvent | undefined;
  const callId = content?.call_id;
  return typeof callId === "string" && callId ? callId : undefined;
}

export function indexCallEvents(rawEvents: Iterable<RawEvent | null | undefined>): CallEventIndex {
  const callers = new Map<string, string>();
  const answered = new Set<string>();
  for (const raw of rawEvents) {
    if (!raw || typeof raw.type !== "string") continue;
    const callId = callIdOf(raw);
    if (!callId) continue;
    if (raw.type === "m.call.invite" && typeof raw.sender === "string") {
      if (!callers.has(callId)) callers.set(callId, raw.sender);
    } else if (ANSWER_TYPES.has(raw.type)) {
      answered.add(callId);
    }
  }
  return { callers, answered };
}

export function isMissedCallHangup(hangup: RawEvent, index: CallEventIndex): boolean {
  const content = hangup.content as RawEvent | undefined;
  if (content?.reason === "invite_timeout") return true;
  const callId = callIdOf(hangup);
  if (!callId) return false;
  const caller = index.callers.get(callId);
  if (!caller || index.answered.has(callId)) return false;
  return hangup.sender === caller;
}
