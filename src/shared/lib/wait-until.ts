import { watch } from "vue";

/**
 * Resolve `true` as soon as `getter()` holds — reactively, so a Pinia ref or
 * a `reactive` field flipping inside a store wakes it — or `false` once
 * `timeoutMs` has passed without that. Either way the watcher and the timer
 * are torn down, so a caller that gave up leaves nothing running behind it.
 *
 * A condition that already holds resolves immediately without creating either.
 * A getter that throws rejects the promise rather than throwing synchronously,
 * so `try { await waitUntil(...) }` at the call site always sees it.
 * Works outside a component scope (services, plain modules).
 */
export function waitUntil(getter: () => boolean, timeoutMs: number): Promise<boolean> {
  try {
    if (getter()) return Promise.resolve(true);
  } catch (e) {
    return Promise.reject(e);
  }
  return new Promise<boolean>((resolve) => {
    let settled = false;
    let stop: (() => void) | null = null;
    const settle = (ok: boolean): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      stop?.();
      resolve(ok);
    };
    const timer = setTimeout(() => settle(false), timeoutMs);
    // `flush: "sync"` so the flip is seen without the Vue scheduler, which
    // may not run between two awaits of a plain async function.
    stop = watch(getter, (ok) => { if (ok) settle(true); }, { flush: "sync" });
  });
}
