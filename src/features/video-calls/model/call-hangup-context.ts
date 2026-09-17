import { getMatrixClientService } from "@/entities/matrix";
import { useCallStore } from "@/entities/call";

/**
 * What Android needs to send `m.call.hangup` itself when a swipe from Recents
 * destroys the WebView mid-call (`CallHangupSignal.kt`). Without it the peer sat
 * in a silent call until its connection watchdog gave up — 29 s on the
 * Samsung ↔ Pixel bench, 2026-09-17.
 *
 * Native reads it with evaluateJavascript while the call is dialled and again
 * once it connects, instead of receiving it as plugin call data, which Capacitor
 * logs in debug builds. The access token is the one the client already keeps in
 * this page. Whether the request goes through Tor is decided natively: this page
 * only knows the proxy configured at login, which is stale once Tor is switched
 * on mid-session.
 */
export interface CallHangupContextSource {
  call: { callId: string; roomId?: string } | null;
  baseUrl: string | undefined;
  accessToken: string | null | undefined;
  /** The SDK's party_id for this device; a peer ignores a hangup of a connected call from another party. */
  deviceId: string | null | undefined;
}

declare global {
  interface Window {
    __fortaCallHangupContext?: (callId: string) => string | null;
  }
}

/**
 * URL-encoded, so evaluateJavascript's JSON form of the string needs no
 * unescaping on the native side. Null unless [callId] is the store's call and
 * every field is known.
 */
export function encodeCallHangupContext(callId: string, source: CallHangupContextSource): string | null {
  const { call, baseUrl, accessToken, deviceId } = source;
  if (!call || call.callId !== callId || !call.roomId) return null;
  if (!baseUrl || !accessToken || !deviceId) return null;
  return new URLSearchParams({
    callId,
    roomId: call.roomId,
    partyId: deviceId,
    baseUrl,
    accessToken,
  })
    .toString()
    .replace(/\+/g, "%20");
}

/**
 * Installs the provider once, from wherever a call starts: the boot-time install
 * in the auth store can still be pending when a dial lands seconds after a cold
 * start, and native then captures nothing (`hswipe1`, first call after install).
 */
export function ensureCallHangupContextProvider(): void {
  if (typeof window.__fortaCallHangupContext === "function") return;
  installCallHangupContextProvider();
}

export function installCallHangupContextProvider(): void {
  window.__fortaCallHangupContext = (callId: string) => {
    try {
      const client = getMatrixClientService().client;
      const call = useCallStore().matrixCall;
      return encodeCallHangupContext(callId, {
        call: call ? { callId: call.callId, roomId: call.roomId } : null,
        baseUrl: client?.baseUrl,
        accessToken: client?.getAccessToken(),
        deviceId: client?.getDeviceId(),
      });
    } catch {
      return null;
    }
  };
}
