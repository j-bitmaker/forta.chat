import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import {
  holdPageAwake,
  releasePageAwake,
  __resetPageAwakeToneForTests,
} from "./page-awake-tone";

// Chromium freezes a hidden page one minute after it stops being audible
// (PageSchedulerImpl::UpdateFrozenState). The audio service calls an output
// stream audible only while its power stays at or above this level
// (kSilenceThresholdDBFS in services/audio/output_stream.cc).
const CHROMIUM_SILENCE_DBFS = -72.24719896;

class FakeParam {
  value = 0;
}

class FakeNode {
  connected: unknown[] = [];
  connect = vi.fn((node: unknown) => {
    this.connected.push(node);
    return node;
  });
  disconnect = vi.fn(() => {
    this.connected = [];
  });
}

class FakeOscillator extends FakeNode {
  type: OscillatorType = "square";
  frequency = new FakeParam();
  start = vi.fn();
  stop = vi.fn();
}

class FakeGain extends FakeNode {
  gain = new FakeParam();
}

class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  static initialState: AudioContextState = "running";
  state: AudioContextState = FakeAudioContext.initialState;
  destination = { kind: "destination" };
  oscillators: FakeOscillator[] = [];
  gains: FakeGain[] = [];
  constructor() {
    FakeAudioContext.instances.push(this);
  }
  createOscillator(): FakeOscillator {
    const osc = new FakeOscillator();
    this.oscillators.push(osc);
    return osc;
  }
  createGain(): FakeGain {
    const gain = new FakeGain();
    this.gains.push(gain);
    return gain;
  }
  resume = vi.fn(async () => {
    this.state = "running";
  });
  close = vi.fn(async () => {
    this.state = "closed";
  });
}

describe("page-awake tone", () => {
  beforeEach(() => {
    __resetPageAwakeToneForTests();
    FakeAudioContext.instances = [];
    FakeAudioContext.initialState = "running";
    vi.stubGlobal("AudioContext", FakeAudioContext);
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  afterEach(() => {
    __resetPageAwakeToneForTests();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("plays a subsonic sine just loud enough for Chromium to count the page audible", () => {
    holdPageAwake("call-a");

    expect(FakeAudioContext.instances).toHaveLength(1);
    const [ctx] = FakeAudioContext.instances;
    const [osc] = ctx.oscillators;
    const [gain] = ctx.gains;
    expect(osc.type).toBe("sine");
    expect(osc.frequency.value).toBeGreaterThan(0);
    expect(osc.frequency.value).toBeLessThanOrEqual(25);
    expect(osc.start).toHaveBeenCalledOnce();
    expect(osc.connected).toContain(gain);
    expect(gain.connected).toContain(ctx.destination);

    // A sine of amplitude A carries a mean power of A²/2 — what the audio
    // service measures. Keep a margin above the silence threshold, and stay
    // far below anything a phone speaker or an earbud could make heard.
    const dbfs = 10 * Math.log10(gain.gain.value ** 2 / 2);
    expect(dbfs).toBeGreaterThan(CHROMIUM_SILENCE_DBFS + 6);
    expect(dbfs).toBeLessThan(-50);
  });

  it("shares one tone between calls and stops it only when the last call lets go", () => {
    holdPageAwake("call-a");
    holdPageAwake("call-b");

    expect(FakeAudioContext.instances).toHaveLength(1);
    const [ctx] = FakeAudioContext.instances;
    const [osc] = ctx.oscillators;

    // Finalizing call A must not silence the page while call B still runs.
    releasePageAwake("call-a");
    expect(osc.stop).not.toHaveBeenCalled();
    expect(ctx.close).not.toHaveBeenCalled();

    releasePageAwake("call-b");
    expect(osc.stop).toHaveBeenCalledOnce();
    expect(ctx.gains[0].disconnect).toHaveBeenCalled();
    expect(ctx.close).toHaveBeenCalledOnce();
  });

  it("counts a call once however many times its call screen was launched", () => {
    // A pre-accepted call launches the call screen on the fast path and again
    // from answerCall; finalize releases it once.
    holdPageAwake("call-a");
    holdPageAwake("call-a");
    expect(FakeAudioContext.instances).toHaveLength(1);

    releasePageAwake("call-a");
    expect(FakeAudioContext.instances[0].close).toHaveBeenCalledOnce();
  });

  it("ignores a release from a call that never held the tone", () => {
    // finalizeCall runs for calls that never reached a call screen too.
    expect(() => releasePageAwake("call-never-held")).not.toThrow();

    holdPageAwake("call-a");
    releasePageAwake("call-other");
    releasePageAwake("call-other");

    const [ctx] = FakeAudioContext.instances;
    expect(ctx.oscillators[0].stop).not.toHaveBeenCalled();
    expect(ctx.close).not.toHaveBeenCalled();
  });

  it("ignores a hold from a call that has already ended", () => {
    // answerCall launches the call screen only after `await call.answer()`.
    // A caller who hangs up in between is finalized first; a late hold would
    // keep the tone playing, and the page awake, for the rest of the process.
    releasePageAwake("call-a");
    holdPageAwake("call-a");
    expect(FakeAudioContext.instances).toHaveLength(0);

    holdPageAwake("call-b");
    releasePageAwake("call-b");
    holdPageAwake("call-b");
    expect(FakeAudioContext.instances).toHaveLength(1);
    expect(FakeAudioContext.instances[0].close).toHaveBeenCalledOnce();
  });

  it("remembers only recently ended calls", () => {
    releasePageAwake("call-old");
    for (let i = 0; i < 1000; i++) releasePageAwake(`call-${i}`);

    holdPageAwake("call-old");

    expect(FakeAudioContext.instances).toHaveLength(1);
  });

  it("starts a fresh tone for the next call after the last one stopped", () => {
    holdPageAwake("call-a");
    releasePageAwake("call-a");
    holdPageAwake("call-b");

    expect(FakeAudioContext.instances).toHaveLength(2);
    expect(FakeAudioContext.instances[1].oscillators[0].start).toHaveBeenCalledOnce();
  });

  it("resumes a context the WebView created suspended", () => {
    FakeAudioContext.initialState = "suspended";
    holdPageAwake("call-a");

    expect(FakeAudioContext.instances[0].resume).toHaveBeenCalled();
  });

  it("resumes a suspended tone when another call holds it", () => {
    holdPageAwake("call-a");
    const [ctx] = FakeAudioContext.instances;
    ctx.state = "suspended";
    ctx.resume.mockClear();

    holdPageAwake("call-b");

    expect(ctx.resume).toHaveBeenCalledOnce();
    expect(FakeAudioContext.instances).toHaveLength(1);
  });

  it("does nothing when the WebView has no Web Audio", () => {
    vi.stubGlobal("AudioContext", undefined);

    expect(() => holdPageAwake("call-a")).not.toThrow();
    expect(() => releasePageAwake("call-a")).not.toThrow();
  });

  it("survives a context that cannot be created and tries again for the next hold", () => {
    class ThrowingAudioContext {
      constructor() {
        throw new Error("NotSupportedError");
      }
    }
    vi.stubGlobal("AudioContext", ThrowingAudioContext);
    expect(() => holdPageAwake("call-a")).not.toThrow();

    vi.stubGlobal("AudioContext", FakeAudioContext);
    holdPageAwake("call-b");

    expect(FakeAudioContext.instances).toHaveLength(1);
    expect(FakeAudioContext.instances[0].oscillators[0].start).toHaveBeenCalledOnce();
  });

  it("closes the context when the tone cannot be built on it", () => {
    class BrokenAudioContext extends FakeAudioContext {
      createOscillator(): FakeOscillator {
        throw new Error("InvalidStateError");
      }
    }
    vi.stubGlobal("AudioContext", BrokenAudioContext);

    expect(() => holdPageAwake("call-a")).not.toThrow();
    expect(FakeAudioContext.instances).toHaveLength(1);
    expect(FakeAudioContext.instances[0].close).toHaveBeenCalledOnce();
  });
});
