import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createSilentAudioTrack, __resetSilentAudioTrackForTests } from "./silent-audio-track";

interface FakeTrack {
  kind: string;
  id: string;
  enabled: boolean;
  readyState: MediaStreamTrackState;
  clone: () => FakeTrack;
}

let trackSeq = 0;

function fakeTrack(): FakeTrack {
  const track: FakeTrack = {
    kind: "audio",
    id: `silent-${trackSeq++}`,
    enabled: true,
    readyState: "live",
    clone: () => fakeTrack(),
  };
  return track;
}

class FakeAudioContext {
  static instances: FakeAudioContext[] = [];
  state: AudioContextState = "running";
  readonly track = fakeTrack();
  readonly close = vi.fn(async () => {
    this.state = "closed";
  });

  constructor() {
    FakeAudioContext.instances.push(this);
  }

  createOscillator() {
    return { connect: () => {}, start: () => {} };
  }

  createMediaStreamDestination() {
    return { stream: { getAudioTracks: () => [this.track] } };
  }
}

describe("createSilentAudioTrack", () => {
  beforeEach(() => {
    FakeAudioContext.instances = [];
    vi.stubGlobal("AudioContext", FakeAudioContext);
  });

  afterEach(() => {
    __resetSilentAudioTrackForTests();
    vi.unstubAllGlobals();
  });

  // Each AudioContext holds one of Chromium's 10 audio output streams for as
  // long as it lives, and a track's stop() does not close it. A context per
  // placeholder used them all up after a few native calls, and the page-awake
  // tone could no longer play (wifion-in2, 2026-09-17).
  it("opens one AudioContext however many tracks are handed out", () => {
    const tracks = Array.from({ length: 6 }, () => createSilentAudioTrack());

    expect(FakeAudioContext.instances).toHaveLength(1);
    expect(new Set(tracks).size).toBe(6);
    for (const track of tracks) {
      expect(track?.kind).toBe("audio");
      expect(track?.enabled).toBe(false);
    }
  });

  it("hands out a new track when an earlier one was stopped", () => {
    const first = createSilentAudioTrack() as unknown as FakeTrack;
    first.readyState = "ended";

    const second = createSilentAudioTrack() as unknown as FakeTrack;

    expect(second).not.toBe(first);
    expect(second.readyState).toBe("live");
    expect(FakeAudioContext.instances).toHaveLength(1);
  });

  it("replaces a context that was closed", () => {
    createSilentAudioTrack();
    FakeAudioContext.instances[0].state = "closed";

    const track = createSilentAudioTrack();

    expect(FakeAudioContext.instances).toHaveLength(2);
    expect(track?.enabled).toBe(false);
  });

  it("replaces a source whose track ended and closes its context", () => {
    createSilentAudioTrack();
    const [old] = FakeAudioContext.instances;
    old.track.readyState = "ended";

    createSilentAudioTrack();

    expect(FakeAudioContext.instances).toHaveLength(2);
    expect(old.close).toHaveBeenCalledOnce();
  });

  it("returns undefined without Web Audio", () => {
    vi.stubGlobal("AudioContext", undefined);

    expect(createSilentAudioTrack()).toBeUndefined();
  });

  it("returns undefined when the context cannot be created, and tries again next time", () => {
    vi.stubGlobal(
      "AudioContext",
      class {
        constructor() {
          throw new Error("NotSupportedError");
        }
      },
    );
    expect(createSilentAudioTrack()).toBeUndefined();

    vi.stubGlobal("AudioContext", FakeAudioContext);
    expect(createSilentAudioTrack()?.kind).toBe("audio");
  });
});
