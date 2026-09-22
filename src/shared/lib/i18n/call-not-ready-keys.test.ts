import { describe, it, expect } from "vitest";
import { en } from "./locales/en";
import { ru } from "./locales/ru";

/**
 * A call placed right after a cold start used to vanish silently because the
 * Matrix client was not ready yet. The dial now waits and, failing that, tells
 * the user — these keys are what it says, in both locales.
 */
describe("i18n keys for a call placed before Matrix is ready", () => {
  const keys = ["call.info.waitingForServer", "call.error.matrixNotReady"] as const;

  it.each(keys)("defines %s in both locales with distinct texts", (key) => {
    const enText = (en as Record<string, string>)[key];
    const ruText = (ru as Record<string, string>)[key];
    expect(typeof enText).toBe("string");
    expect(enText.length).toBeGreaterThan(0);
    expect(typeof ruText).toBe("string");
    expect(ruText.length).toBeGreaterThan(0);
    expect(enText).not.toBe(ruText);
  });
});
