/**
 * Keeps the app's page audible to Chromium while a native call screen covers it.
 *
 * CallActivity hides MainActivity, so the WebView page turns hidden, and the
 * call's own audio plays in the native WebRTC engine rather than in the page.
 * Chromium freezes a page that has been hidden and silent for a minute
 * (`kStopInBackground`, PageSchedulerImpl::UpdateFrozenState): timers stop and
 * plugin replies stay queued, so JS never processes the peer's hangup and the
 * call screen stays open after the other side has left. 4 of 4 calls longer
 * than a minute froze on a Samsung with WebView 151, 2026-09-15.
 *
 * Chromium does not freeze a hidden page while it is audible. The audio
 * service counts an output stream as audible at or above -72.25 dBFS, so a
 * 20 Hz sine at about -57 dBFS keeps the page awake with nothing reaching the
 * ear: phone speakers cannot reproduce 20 Hz, and at this level it sits far
 * below the threshold of hearing even in earbuds.
 *
 * Held per callId, so finalizing one call cannot silence the page while
 * another call is still live, and a call that has ended cannot hold it again.
 *
 * Chromium suspends a context whose audio device fails
 * (AudioContext::HandleRenderError) and never resumes it by itself. On a
 * Samsung the tone's stream opened just as the call audio moved to AirPods,
 * the tone stayed suspended and the page froze a minute later with the call
 * screen open (airpods4, 2026-09-15). A dropped tone is resumed, or replaced
 * when it will not resume.
 */

const TONE_HZ = 20;
// Mean power of a sine is A²/2: 10·log10(0.002² / 2) ≈ -57 dBFS, 15 dB above
// Chromium's silence threshold.
const TONE_GAIN = 0.002;
// answerCall launches the call screen only after `await call.answer()`, so a
// caller who hangs up in between is released before that late hold arrives.
// Remembering recent releases keeps it from playing for the rest of the process.
const MAX_ENDED_CALLS = 32;
// While the call audio is still moving between devices the resumed stream can
// fail again, so a dropped tone waits a moment and a burst of drops resumes it
// once. Chromium freezes the page only a minute after the tone goes quiet.
const RESUME_DELAY_MS = 1000;

interface Tone {
  ctx: AudioContext;
  osc: OscillatorNode;
  gain: GainNode;
}

const holders = new Set<string>();
const endedCalls = new Set<string>();
let tone: Tone | null = null;
let resumeTimer: ReturnType<typeof setTimeout> | null = null;

function createTone(): Tone | null {
  const AudioContextCtor = globalThis.AudioContext;
  if (typeof AudioContextCtor !== "function") return null;

  let ctx: AudioContext;
  try {
    ctx = new AudioContextCtor();
  } catch (e) {
    console.warn("[page-awake-tone] AudioContext unavailable:", e);
    return null;
  }

  try {
    const osc = ctx.createOscillator();
    osc.type = "sine";
    osc.frequency.value = TONE_HZ;
    const gain = ctx.createGain();
    gain.gain.value = TONE_GAIN;
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    const created: Tone = { ctx, osc, gain };
    ctx.addEventListener("statechange", () => onToneStateChange(created));
    return created;
  } catch (e) {
    console.warn("[page-awake-tone] could not start the tone:", e);
    closeContext(ctx);
    return null;
  }
}

function onToneStateChange(changed: Tone): void {
  if (tone !== changed || changed.ctx.state !== "suspended" || resumeTimer) return;
  console.warn("[page-awake-tone] the tone was suspended, resuming it");
  resumeTimer = setTimeout(resumeDroppedTone, RESUME_DELAY_MS);
}

function resumeDroppedTone(): void {
  resumeTimer = null;
  const dropped = tone;
  if (!dropped || dropped.ctx.state !== "suspended") return;
  dropped.ctx.resume().catch((e: unknown) => {
    if (tone !== dropped) return;
    console.warn("[page-awake-tone] resume failed, starting a new tone:", e);
    stopTone();
    tone = createTone();
  });
}

function closeContext(ctx: AudioContext): void {
  try {
    ctx.close().catch((e: unknown) => console.warn("[page-awake-tone] close failed:", e));
  } catch (e) {
    console.warn("[page-awake-tone] close failed:", e);
  }
}

function stopTone(): void {
  if (resumeTimer) {
    clearTimeout(resumeTimer);
    resumeTimer = null;
  }
  const current = tone;
  tone = null;
  if (!current) return;
  try {
    current.osc.stop();
    current.osc.disconnect();
    current.gain.disconnect();
  } catch (e) {
    console.warn("[page-awake-tone] could not stop the tone:", e);
  }
  closeContext(current.ctx);
}

function rememberEnded(callId: string): void {
  endedCalls.delete(callId);
  endedCalls.add(callId);
  if (endedCalls.size <= MAX_ENDED_CALLS) return;
  const oldest = endedCalls.values().next().value;
  if (oldest !== undefined) endedCalls.delete(oldest);
}

/** Keep the page audible for this call, unless it has already ended. Never throws. */
export function holdPageAwake(callId: string): void {
  if (!callId || endedCalls.has(callId)) return;
  holders.add(callId);
  try {
    if (!tone) tone = createTone();
    if (tone?.ctx.state === "suspended") {
      tone.ctx.resume().catch((e: unknown) => console.warn("[page-awake-tone] resume failed:", e));
    }
  } catch (e) {
    console.warn("[page-awake-tone] could not keep the page audible:", e);
  }
}

/** The call has ended: drop its hold; the tone stops with the last one. Never throws. */
export function releasePageAwake(callId: string): void {
  if (!callId) return;
  rememberEnded(callId);
  if (!holders.delete(callId) || holders.size > 0) return;
  stopTone();
}

/** Test-only: forget every holder and ended call, and stop the tone. */
export function __resetPageAwakeToneForTests(): void {
  holders.clear();
  endedCalls.clear();
  stopTone();
}
