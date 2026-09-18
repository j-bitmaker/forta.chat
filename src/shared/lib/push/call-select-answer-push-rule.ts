import { findUnderridePushRule, underrideRuleSince, type SinceStorage } from './call-hangup-push-rule';

/**
 * The account push rule that makes the homeserver push a caller's `m.call.select_answer`.
 *
 * The caller sends it once one of the callee's devices answers. Without a push a phone
 * whose page is frozen in the background never hears it and keeps ringing for 30 s while
 * the user already talks on another device, or in Bastyon on the same phone
 * (forta-bugs#809, `dualb6`). The device that answered gets the push as well; Android
 * tells the two apart by its own Telecom slot (`SelectAnswerPolicy.kt`), since the push
 * gateway passes no `selected_party_id` (`saprobe3`, 2026-09-18).
 *
 * Account-wide like the hangup rule (`call-hangup-push-rule.ts`): the server counts
 * every answered call as one more unread notification, and the chat list takes it back
 * out (`entities/chat/lib/call-hangup-unread.ts`). An Android build older than
 * `SelectAnswerPolicy` on the same account treats this push as a hangup and drops the
 * calls it answers.
 */
export const CALL_SELECT_ANSWER_PUSH_RULE_ID = 'com.forta.call.select_answer';
export const CALL_SELECT_ANSWER_PUSH_RULE_KIND = 'underride';

export interface CallSelectAnswerPushRuleBody {
  conditions: Array<{ kind: 'event_match'; key: 'type'; pattern: string }>;
  actions: string[];
}

/** Underride, so a muted room still wins. Notify only: no sound, no highlight. */
export function buildCallSelectAnswerPushRule(): CallSelectAnswerPushRuleBody {
  return {
    conditions: [{ kind: 'event_match', key: 'type', pattern: 'm.call.select_answer' }],
    actions: ['notify'],
  };
}

export function findCallSelectAnswerPushRule(rules: unknown): { rule_id?: string; enabled?: boolean } | undefined {
  return findUnderridePushRule(rules, CALL_SELECT_ANSWER_PUSH_RULE_ID);
}

const SINCE_KEY_PREFIX = 'forta.callSelectAnswerRuleSince:';

/**
 * When this device first saw the rule on [userId]'s account, or null while it is not
 * known to be on. Dated apart from the hangup rule: an account that has had that one
 * for weeks gets this one later, and a `select_answer` sent before it was never counted.
 */
export function callSelectAnswerRuleSince(
  userId: string,
  rules: unknown,
  now: number,
  storage: SinceStorage,
): number | null {
  return underrideRuleSince(CALL_SELECT_ANSWER_PUSH_RULE_ID, SINCE_KEY_PREFIX, userId, rules, now, storage);
}
