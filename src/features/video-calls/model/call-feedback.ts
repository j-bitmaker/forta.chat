import { useLocalStorage } from "@/shared/lib/browser";

/**
 * Post-call feedback prompt.
 *
 * Reports today arrive minutes or hours after the call, written from memory
 * ("не было слышно") and by then AudioRouter has been torn down, so the
 * diagnostics attached describe an idle device. Asking right after the call
 * catches the audio timeline while it is still in the ring buffer and turns
 * free-form prose into a symptom we can group.
 *
 * The prompt has to stay rare or people stop reading it: a failed or
 * suspiciously short call is always worth asking about, an ordinary one at
 * most once a day.
 */
export type CallProblem =
  | "peer_not_heard"
  | "not_heard_by_peer"
  | "audio_dropped"
  | "echo"
  | "never_connected"
  | "other";

export const CALL_PROBLEMS: readonly CallProblem[] = [
  "peer_not_heard",
  "not_heard_by_peer",
  "audio_dropped",
  "echo",
  "never_connected",
  "other",
] as const;

export const FEEDBACK_LAST_PROMPT_LS_KEY = "call_feedback_last_prompt";

/**
 * A call this short either failed to connect or was abandoned immediately —
 * both are worth asking about even if we just asked.
 */
export const SUSPICIOUS_CALL_DURATION_S = 10;

/** Ordinary calls: at most one prompt per day. */
export const FEEDBACK_COOLDOWN_MS = 24 * 60 * 60 * 1000;

export interface FeedbackPromptInput {
  /** Terminal status of the call that just ended. */
  status: "answered" | "missed" | "declined" | "failed";
  /** Connected duration in seconds. */
  durationS: number;
  /** Epoch ms of the last prompt, or null if never shown. */
  lastPromptAtMs: number | null;
  nowMs: number;
}

/**
 * Whether to show the prompt for the call that just ended.
 *
 * Never asks about a call the user did not take part in (missed, declined) —
 * there is nothing to rate, and asking reads as blaming them for hanging up.
 */
export function shouldPromptForFeedback(input: FeedbackPromptInput): boolean {
  if (input.status === "missed" || input.status === "declined") return false;

  const suspicious =
    input.status === "failed" || input.durationS < SUSPICIOUS_CALL_DURATION_S;
  if (suspicious) return true;

  if (input.lastPromptAtMs === null) return true;
  return input.nowMs - input.lastPromptAtMs >= FEEDBACK_COOLDOWN_MS;
}

export function getLastFeedbackPromptAt(): number | null {
  try {
    const { value } = useLocalStorage<number>(FEEDBACK_LAST_PROMPT_LS_KEY);
    return typeof value === "number" && Number.isFinite(value) ? value : null;
  } catch {
    return null;
  }
}

export function markFeedbackPromptShown(atMs: number): void {
  try {
    const { setLSValue } = useLocalStorage<number>(FEEDBACK_LAST_PROMPT_LS_KEY);
    setLSValue(atMs);
  } catch {
    // Storage unavailable — worst case the prompt appears again sooner.
  }
}

/**
 * Seed text for the bug report opened from a thumbs-down, so triage gets a
 * consistent phrase to cluster on instead of prose. The user can edit it.
 */
export function buildFeedbackDescription(
  problem: CallProblem,
  durationS: number,
  label: string,
): string {
  return `${label}\n\n(call feedback: ${problem}, duration ${Math.round(durationS)}s)`;
}
