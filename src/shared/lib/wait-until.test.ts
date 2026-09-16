import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { nextTick, ref } from "vue";
import { waitUntil } from "./wait-until";

describe("waitUntil", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("resolves true at once when the condition already holds, without a timer", async () => {
    await expect(waitUntil(() => true, 1000)).resolves.toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("resolves true when the reactive condition flips before the timeout, and clears the timer", async () => {
    const flag = ref(false);
    const pending = waitUntil(() => flag.value, 10_000);

    await vi.advanceTimersByTimeAsync(2000);
    flag.value = true;

    await expect(pending).resolves.toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("resolves false exactly at the timeout when the condition never holds", async () => {
    const settled = vi.fn();
    const flag = ref(false);
    void waitUntil(() => flag.value, 10_000).then(settled);

    await vi.advanceTimersByTimeAsync(9_999);
    expect(settled).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(settled).toHaveBeenCalledWith(false);
  });

  it("rejects instead of throwing when the getter throws", async () => {
    const getter = (): boolean => {
      throw new Error("boom");
    };
    let promise: Promise<boolean> | undefined;
    expect(() => { promise = waitUntil(getter, 1000); }).not.toThrow();
    await expect(promise).rejects.toThrow("boom");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("stops watching after the timeout", async () => {
    const flag = ref(false);
    const getter = vi.fn(() => flag.value);
    void waitUntil(getter, 1000);

    await vi.advanceTimersByTimeAsync(1000);
    const callsAfterTimeout = getter.mock.calls.length;

    flag.value = true;
    await nextTick();
    expect(getter.mock.calls.length).toBe(callsAfterTimeout);
  });
});
