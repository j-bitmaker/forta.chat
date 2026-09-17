/**
 * Keep an ICE restart alive through a short network outage.
 *
 * matrix-js-sdk ends the call the first time the restart offer fails to send
 * (`gotLocalOffer` → `signalling_timeout`) and gives up on candidates after a
 * few quick retries. ICE itself waits 30 s before the SDK hangs up, so a
 * network change that takes a few seconds to carry traffic (a VPN reconnecting,
 * Wi-Fi handing over to mobile data) ended calls that could have recovered.
 *
 * The wrapper retries `m.call.negotiate` (restart offers and answers) and
 * `m.call.candidates` while the send fails for lack of a connection and the
 * call is still live, for at most the SDK's ICE wait. Offline it waits for the
 * `online` event instead of polling. Server errors and every other call event
 * keep the SDK's behaviour.
 */

const RETRIED_EVENT_TYPES = new Set(["m.call.negotiate", "m.call.candidates"]);
const WRAPPED_FLAG = "__voipSendRetryInstalled";
const FIRST_DELAY_MS = 1_000;
const MAX_DELAY_MS = 8_000;

/** Matches the SDK's ICE_DISCONNECTED_TIMEOUT: past it the call is over anyway. */
export const VOIP_SEND_RETRY_WINDOW_MS = 30_000;

export interface RetryableVoipCall {
  callId: string;
  sendVoipEvent(eventType: string, content: Record<string, unknown>): Promise<void>;
  callHasEnded(): boolean;
}

export interface VoipSendRetryDeps {
  now: () => number;
  sleep: (ms: number) => Promise<void>;
  isOnline: () => boolean;
  /** Resolves on the next `online` event or after `timeoutMs`, whichever comes first. */
  waitForOnline: (timeoutMs: number) => Promise<void>;
}

const defaultDeps: VoipSendRetryDeps = {
  now: () => Date.now(),
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  isOnline: () => typeof navigator === "undefined" || navigator.onLine !== false,
  waitForOnline: (timeoutMs) =>
    new Promise((resolve) => {
      if (typeof window === "undefined") {
        setTimeout(resolve, timeoutMs);
        return;
      }
      const done = (): void => {
        clearTimeout(timer);
        window.removeEventListener("online", done);
        resolve();
      };
      const timer = setTimeout(done, timeoutMs);
      window.addEventListener("online", done);
    }),
};

const isConnectionError = (e: unknown): boolean => e instanceof Error && e.name === "ConnectionError";

export function installVoipSendRetry(call: RetryableVoipCall, deps: Partial<VoipSendRetryDeps> = {}): void {
  const marker = call as unknown as Record<string, unknown>;
  if (marker[WRAPPED_FLAG] || typeof call.sendVoipEvent !== "function") return;
  marker[WRAPPED_FLAG] = true;

  const { now, sleep, isOnline, waitForOnline } = { ...defaultDeps, ...deps };
  const original = call.sendVoipEvent.bind(call);

  call.sendVoipEvent = async (eventType: string, content: Record<string, unknown>): Promise<void> => {
    if (!RETRIED_EVENT_TYPES.has(eventType)) return original(eventType, content);

    const deadline = now() + VOIP_SEND_RETRY_WINDOW_MS;
    let delay = FIRST_DELAY_MS;
    for (;;) {
      try {
        return await original(eventType, content);
      } catch (e) {
        const left = deadline - now();
        if (!isConnectionError(e) || call.callHasEnded() || left <= 0) throw e;
        console.warn(
          `[voip-retry] ${eventType} for ${call.callId} failed without a connection, retrying (${Math.ceil(left / 1000)} s left)`,
        );
        if (isOnline()) {
          await sleep(Math.min(delay, left));
          delay = Math.min(delay * 2, MAX_DELAY_MS);
        } else {
          await waitForOnline(left);
        }
        // One more attempt even at the deadline: the wait above never runs past it.
        if (call.callHasEnded()) throw e;
      }
    }
  };
}
