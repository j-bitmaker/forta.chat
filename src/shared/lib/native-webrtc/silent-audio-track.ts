/**
 * Disabled audio tracks that fill the SDK's audio slots on native calls.
 *
 * The call's audio runs in native libwebrtc, but the SDK still expects a local
 * stream from getUserMedia and a remote stream per msid with an audio track in
 * each. Web Audio is the only way to make an audio MediaStreamTrack without a
 * microphone.
 *
 * Every AudioContext holds one of Chromium's 10 audio output streams until it
 * is closed, and stopping a track taken from it closes nothing. A context per
 * placeholder leaked two streams per call. A few calls into a process the
 * limit was reached, the page-awake tone could not play, and the hidden page
 * froze a minute into the next call and never heard the peer hang up
 * (wifion-in2, Samsung, 2026-09-17). One context now serves every placeholder:
 * each caller gets a clone, which it may stop without affecting the others.
 */

interface SilentSource {
  ctx: AudioContext;
  track: MediaStreamTrack;
}

let source: SilentSource | null = null;

function closeContext(ctx: AudioContext): void {
  try {
    ctx.close().catch(() => {});
  } catch {
    // Already closed.
  }
}

function openSource(): SilentSource | null {
  const AudioContextCtor = globalThis.AudioContext;
  if (typeof AudioContextCtor !== "function") return null;

  let ctx: AudioContext;
  try {
    ctx = new AudioContextCtor();
  } catch (e) {
    console.warn("[silent-audio-track] AudioContext unavailable:", e);
    return null;
  }

  try {
    const osc = ctx.createOscillator();
    const dest = ctx.createMediaStreamDestination();
    osc.connect(dest);
    osc.start();
    const track = dest.stream.getAudioTracks()[0];
    if (track) return { ctx, track };
  } catch (e) {
    console.warn("[silent-audio-track] could not create the source track:", e);
  }
  closeContext(ctx);
  return null;
}

/** A new disabled audio track from the shared silent source, or undefined without Web Audio. */
export function createSilentAudioTrack(): MediaStreamTrack | undefined {
  if (source && (source.ctx.state === "closed" || source.track.readyState === "ended")) {
    closeContext(source.ctx);
    source = null;
  }
  source ??= openSource();
  if (!source) return undefined;

  try {
    const track = source.track.clone();
    track.enabled = false;
    return track;
  } catch (e) {
    console.warn("[silent-audio-track] could not clone the source track:", e);
    return undefined;
  }
}

/** Test-only: close the shared source. */
export function __resetSilentAudioTrackForTests(): void {
  if (source) closeContext(source.ctx);
  source = null;
}
