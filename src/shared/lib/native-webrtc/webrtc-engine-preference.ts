import { useLocalStorage } from "@/shared/lib/browser";

/**
 * Which WebRTC media engine Android uses for calls.
 *
 * `native`  — the default. `installNativeWebRTCProxy()` replaces
 *             `window.RTCPeerConnection` / `getUserMedia` so the Matrix SDK
 *             drives the native libwebrtc engine (NativeWebRTCManager.kt).
 * `webview` — the proxy is not installed and the SDK uses the WebView's own
 *             WebRTC stack.
 *
 * Why this switch exists: the native engine ships to 100% of Android with no
 * runtime escape hatch, so a regression in it can only be undone by shipping a
 * new release. It also doubles as a diagnostic: when a user reports broken
 * audio, flipping the engine tells us in one call whether the fault is in the
 * native media path or in the surrounding audio routing / signalling — the
 * latter stays identical either way, because this only gates the media proxy.
 * `chrome://webrtc-internals` is likewise only populated in `webview` mode.
 *
 * The proxy installs as a module-level side effect in `call-service.ts`, so a
 * change takes effect on the next app start, not mid-session.
 */
export type WebRTCEngine = "native" | "webview";

export const WEBRTC_ENGINE_LS_KEY = "call_webrtc_engine";

const DEFAULT_ENGINE: WebRTCEngine = "native";

function isWebRTCEngine(value: unknown): value is WebRTCEngine {
  return value === "native" || value === "webview";
}

/**
 * Reads the stored preference. Falls back to `native` for a missing, corrupt
 * or unreadable value — a private-mode or storage-disabled browser must not
 * silently change which engine calls run on.
 */
export function getWebRTCEngine(): WebRTCEngine {
  try {
    const { value } = useLocalStorage<WebRTCEngine>(WEBRTC_ENGINE_LS_KEY);
    return isWebRTCEngine(value) ? value : DEFAULT_ENGINE;
  } catch {
    return DEFAULT_ENGINE;
  }
}

/** Persists the preference. Takes effect on the next app start. */
export function setWebRTCEngine(engine: WebRTCEngine): void {
  try {
    const { setLSValue } = useLocalStorage<WebRTCEngine>(WEBRTC_ENGINE_LS_KEY);
    setLSValue(engine);
  } catch {
    // Storage unavailable — the engine stays at whatever the last start read.
  }
}

/** True when the native media proxy should be installed. */
export function isNativeWebRTCEngineEnabled(): boolean {
  return getWebRTCEngine() === "native";
}
