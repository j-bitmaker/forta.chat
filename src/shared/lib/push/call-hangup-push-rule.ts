/**
 * The account push rule that makes the homeserver push a caller's `m.call.hangup`.
 *
 * The default rules notify only `m.call.invite`, so while the app was dead nothing
 * told the phone that a caller had hung up, and the incoming call rang out its 30 s.
 * The rule is account-wide: every client on the account gets these pushes, and the
 * server counts each hangup a peer sends as an unread notification. The chat list
 * takes those back out (`entities/chat/lib/call-hangup-unread.ts`); other Matrix
 * clients, the iOS badge set by the push gateway and the account switcher still
 * count them.
 *
 * Never add `m.call.select_answer` here: the native push handler treats it like a
 * hangup and would drop every call the phone answers.
 */
export const CALL_HANGUP_PUSH_RULE_ID = 'com.forta.call.hangup';
export const CALL_HANGUP_PUSH_RULE_KIND = 'underride';

export interface CallHangupPushRuleBody {
  conditions: Array<{ kind: 'event_match'; key: 'type'; pattern: string }>;
  actions: string[];
}

/** Underride, so a muted room (a `room` rule) still wins. Notify only: no sound, no highlight. */
export function buildCallHangupPushRule(): CallHangupPushRuleBody {
  return {
    conditions: [{ kind: 'event_match', key: 'type', pattern: 'm.call.hangup' }],
    actions: ['notify'],
  };
}

interface PushRuleLike {
  rule_id?: string;
  enabled?: boolean;
}

/** The rule among [rules] as `GET /pushrules/` returns them, enabled or not. */
export function findCallHangupPushRule(rules: unknown): PushRuleLike | undefined {
  const underride = (rules as { global?: { underride?: unknown } } | null | undefined)?.global?.underride;
  if (!Array.isArray(underride)) return undefined;
  return (underride as Array<PushRuleLike | null>).find((r) => r?.rule_id === CALL_HANGUP_PUSH_RULE_ID) ?? undefined;
}

type SinceStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

const SINCE_KEY_PREFIX = 'forta.callHangupRuleSince:';

/**
 * When this device first saw the rule on [userId]'s account, in ms since the epoch,
 * or null while the rule is not known to be on.
 *
 * The server counts a hangup only if the rule existed when the hangup was sent. The
 * moment a device first sees the rule is a safe bound: hangups sent before it stay in
 * the badge rather than risk hiding a missed call. [rules] are the SDK's cached push
 * rules (`client.pushRules`); undefined means not loaded yet and changes nothing.
 */
export function callHangupRuleSince(userId: string, rules: unknown, now: number, storage: SinceStorage): number | null {
  if (!userId) return null;
  const key = SINCE_KEY_PREFIX + userId;
  try {
    if (rules == null) return parseSince(storage.getItem(key));
    const rule = findCallHangupPushRule(rules);
    if (!rule || rule.enabled === false) {
      storage.removeItem(key);
      return null;
    }
    const known = parseSince(storage.getItem(key));
    if (known !== null) return known;
    storage.setItem(key, String(now));
    return now;
  } catch {
    // Storage denied (degraded WebView): take nothing back.
    return null;
  }
}

function parseSince(raw: string | null): number | null {
  if (raw === null) return null;
  const since = Number(raw);
  return Number.isFinite(since) ? since : null;
}
