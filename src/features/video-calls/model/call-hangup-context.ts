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
 * this page.
 */
export interface CallHangupContextSource {
  call: { callId: string; roomId?: string } | null;
  baseUrl: string | undefined;
  accessToken: string | null | undefined;
  /** The SDK's party_id for this device; a peer ignores a hangup of a connected call from another party. */
  deviceId: string | null | undefined;
  viaTorProxy: boolean;
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
    viaTorProxy: source.viaTorProxy ? "1" : "0",
  })
    .toString()
    .replace(/\+/g, "%20");
}

export function installCallHangupContextProvider(): void {
  window.__fortaCallHangupContext = (callId: string) => {
    try {
      const service = getMatrixClientService();
      const client = service.client;
      const call = useCallStore().matrixCall;
      return encodeCallHangupContext(callId, {
        call: call ? { callId: call.callId, roomId: call.roomId } : null,
        baseUrl: client?.baseUrl,
        accessToken: client?.getAccessToken(),
        deviceId: client?.getDeviceId(),
        viaTorProxy: service.usesTorProxy,
      });
    } catch {
      return null;
    }
  };
}
