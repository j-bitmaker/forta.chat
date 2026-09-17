/**
 * Regression: an ICE restart could not outlive a short network outage. The SDK ends the call as soon as
 * one send of the restart offer fails (`gotLocalOffer` → `signalling_timeout`) and gives up on candidates
 * after a few quick retries, while ICE itself waits 30 s. Samsung `wifioff-vpn2`: Wi-Fi went off, the
 * restart offer went out at once, the VPN needed longer to carry traffic, `PUT m.call.negotiate` failed
 * after 5 s and the call ended.
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { installVoipSendRetry, VOIP_SEND_RETRY_WINDOW_MS } from "./voip-send-retry";

class ConnectionError extends Error {
  get name(): string {
    return "ConnectionError";
  }
}

function fakeCall(results: Array<"ok" | Error>) {
  let ended = false;
  const send = vi.fn(async (_type: string, _content: Record<string, unknown>) => {
    const next = results.shift() ?? "ok";
    if (next !== "ok") throw next;
  });
  const call = {
    callId: "c1",
    sendVoipEvent: send,
    callHasEnded: () => ended,
  };
  return { call, send, end: () => { ended = true; } };
}

function deps(overrides: Partial<Parameters<typeof installVoipSendRetry>[1]> = {}) {
  let now = 1_000;
  const sleeps: number[] = [];
  return {
    sleeps,
    advance: (ms: number) => { now += ms; },
    value: {
      now: () => now,
      sleep: vi.fn(async (ms: number) => { sleeps.push(ms); now += ms; }),
      isOnline: () => true,
      waitForOnline: vi.fn(async () => {}),
      ...overrides,
    },
  };
}

describe("installVoipSendRetry", () => {
  beforeEach(() => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  it("retries a restart offer that failed for lack of network until it goes out", async () => {
    const { call, send } = fakeCall([new ConnectionError("fetch failed"), new ConnectionError("fetch failed"), "ok"]);
    const d = deps();
    installVoipSendRetry(call, d.value);

    await expect(call.sendVoipEvent("m.call.negotiate", { description: {} })).resolves.toBeUndefined();

    expect(send).toHaveBeenCalledTimes(3);
    expect(send.mock.calls.every(([type]) => type === "m.call.negotiate")).toBe(true);
    expect(d.sleeps.length).toBe(2);
  });

  it("retries candidates the same way", async () => {
    const { call, send } = fakeCall([new ConnectionError("fetch failed"), "ok"]);
    installVoipSendRetry(call, deps().value);

    await call.sendVoipEvent("m.call.candidates", { candidates: [] });

    expect(send).toHaveBeenCalledTimes(2);
  });

  it("leaves other call events and server errors to the SDK", async () => {
    const hangup = fakeCall([new ConnectionError("fetch failed")]);
    installVoipSendRetry(hangup.call, deps().value);
    await expect(hangup.call.sendVoipEvent("m.call.hangup", {})).rejects.toThrow("fetch failed");
    expect(hangup.send).toHaveBeenCalledTimes(1);

    const forbidden = fakeCall([Object.assign(new Error("M_FORBIDDEN"), { name: "M_FORBIDDEN" })]);
    installVoipSendRetry(forbidden.call, deps().value);
    await expect(forbidden.call.sendVoipEvent("m.call.negotiate", {})).rejects.toThrow("M_FORBIDDEN");
    expect(forbidden.send).toHaveBeenCalledTimes(1);
  });

  it("gives up after the retry window, as the SDK would after 30 s of broken ICE", async () => {
    const failures = Array.from({ length: 100 }, () => new ConnectionError("fetch failed"));
    const { call, send } = fakeCall(failures);
    const d = deps();
    installVoipSendRetry(call, d.value);

    await expect(call.sendVoipEvent("m.call.negotiate", {})).rejects.toThrow("fetch failed");

    const waited = d.sleeps.reduce((a, b) => a + b, 0);
    expect(waited).toBeLessThanOrEqual(VOIP_SEND_RETRY_WINDOW_MS);
    expect(send.mock.calls.length).toBeGreaterThan(2);
    expect(send.mock.calls.length).toBeLessThan(20);
  });

  it("stops retrying once the call has ended", async () => {
    const { call, send, end } = fakeCall([new ConnectionError("fetch failed"), new ConnectionError("fetch failed"), "ok"]);
    const d = deps({ sleep: vi.fn(async () => { end(); }) });
    installVoipSendRetry(call, d.value);

    await expect(call.sendVoipEvent("m.call.negotiate", {})).rejects.toThrow("fetch failed");
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("waits for the device to come online instead of polling while offline", async () => {
    let online = false;
    const { call, send } = fakeCall([new ConnectionError("fetch failed"), "ok"]);
    const d = deps({
      isOnline: () => online,
      waitForOnline: vi.fn(async () => { online = true; }),
    });
    installVoipSendRetry(call, d.value);

    await call.sendVoipEvent("m.call.negotiate", {});

    expect(d.value.waitForOnline).toHaveBeenCalledOnce();
    expect(d.value.sleep).not.toHaveBeenCalled();
    expect(send).toHaveBeenCalledTimes(2);
  });

  it("wraps a call only once", async () => {
    const { call, send } = fakeCall([new ConnectionError("fetch failed"), "ok"]);
    installVoipSendRetry(call, deps().value);
    installVoipSendRetry(call, deps().value);

    await call.sendVoipEvent("m.call.negotiate", {});

    expect(send).toHaveBeenCalledTimes(2);
  });
});
