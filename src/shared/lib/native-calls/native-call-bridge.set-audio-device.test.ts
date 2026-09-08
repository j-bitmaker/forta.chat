import { describe, it, expect, vi, type Mock } from 'vitest';

/**
 * O08 — `setAudioDevice` reports a refused route instead of swallowing it.
 *
 * The native AudioRouter refuses a route while it is not running (the call
 * has not reached start(), or is already torn down) and CallPlugin rejects
 * with `router_inactive`. The bridge used to catch that and resolve as if
 * the route had been applied, so the in-call toggle showed "speaker on"
 * over a live earpiece. Now the boolean lets the UI roll back.
 */

const android = {
  isNative: true,
  isAndroid: true,
  isIOS: false,
  isElectron: false,
  isWeb: false,
  currentPlatform: 'android' as const,
};

async function loadBridgeWith(setAudioDeviceSpy: Mock) {
  vi.resetModules();
  vi.doMock('@capacitor/core', () => ({
    registerPlugin: (name: string) => {
      if (name === 'NativeCall') {
        return {
          setAudioDevice: setAudioDeviceSpy,
          requestAudioPermission: vi.fn().mockResolvedValue({ granted: true }),
          addListener: vi.fn().mockResolvedValue({ remove: vi.fn() }),
          getPendingAnswer: vi.fn().mockResolvedValue({ callId: null, roomId: null }),
          getPendingReject: vi.fn().mockResolvedValue({ callId: null, roomId: null }),
        };
      }
      return new Proxy({}, { get: () => vi.fn().mockResolvedValue({}) });
    },
  }));
  vi.doMock('@/shared/lib/platform', () => android);
  vi.doMock('@/shared/lib/native-webrtc/native-webrtc-bridge', () => ({
    NativeWebRTC: { addListener: vi.fn() },
  }));
  vi.doMock('@capacitor/camera', () => ({
    Camera: { requestPermissions: vi.fn() },
  }));
  return (await import('./native-call-bridge')).nativeCallBridge;
}

describe('nativeCallBridge.setAudioDevice — refusal is reported (O08)', () => {
  it('resolves true when the native router applied the route', async () => {
    const spy = vi.fn().mockResolvedValue(undefined);
    const bridge = await loadBridgeWith(spy);
    await expect(bridge.setAudioDevice({ type: 'speaker' })).resolves.toBe(true);
    expect(spy).toHaveBeenCalledWith({ type: 'speaker' });
  });

  it('resolves false — and does not throw — when the plugin rejects with router_inactive', async () => {
    const spy = vi.fn().mockRejectedValue({ code: 'router_inactive', message: 'Audio routing inactive' });
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    try {
      const bridge = await loadBridgeWith(spy);
      await expect(bridge.setAudioDevice({ type: 'speaker' })).resolves.toBe(false);
      expect(warn).toHaveBeenCalled();
    } finally {
      warn.mockRestore();
    }
  });
});
