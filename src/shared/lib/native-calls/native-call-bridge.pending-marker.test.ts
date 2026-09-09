import { describe, it, expect, vi, type Mock } from 'vitest';
import { PENDING_MARKER_ROOM_TTL_MS } from './pending-call-marker';

/**
 * A pending answer/reject marker must not outlive the call it belongs to.
 *
 * Found on the Samsung bench 2026-09-09: the ringer's 30 s auto-reject left a
 * marker nobody consumed, and the next call from the same room — a fresh
 * callId, 5.5 minutes later — was rejected before it ever rang. The roomId
 * fallback cannot simply be dropped (the push payload's call_id is an
 * event_id, so on cold-start-from-push the room is the only correlation), so
 * the marker carries its write time and ages out instead.
 */

const ROOM = '!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app';
const PUSH_EVENT_ID = '$ZM8kQ5-push-event-id';
const MATRIX_CALL_ID = '17889559802696wWSVJYT0cFnIWQt';

const android = {
  isNative: true,
  isAndroid: true,
  isIOS: false,
  isElectron: false,
  isWeb: false,
  currentPlatform: 'android' as const,
};

async function loadBridge(answer: Mock, reject: Mock) {
  vi.resetModules();
  vi.doMock('@capacitor/core', () => ({
    registerPlugin: (name: string) => {
      if (name === 'NativeCall') {
        return {
          getPendingAnswer: answer,
          getPendingReject: reject,
          addListener: vi.fn().mockResolvedValue({ remove: vi.fn() }),
          requestAudioPermission: vi.fn().mockResolvedValue({ granted: true }),
        };
      }
      return new Proxy({}, { get: () => vi.fn().mockResolvedValue({}) });
    },
  }));
  vi.doMock('@/shared/lib/platform', () => android);
  vi.doMock('@/shared/lib/native-webrtc/native-webrtc-bridge', () => ({
    NativeWebRTC: { addListener: vi.fn() },
  }));
  vi.doMock('@capacitor/camera', () => ({ Camera: { requestPermissions: vi.fn() } }));
  return await import('./native-call-bridge');
}

const noMarker = () => vi.fn().mockResolvedValue({ callId: null, roomId: null, atMs: 0 });

/** A native marker written `ageMs` ago, whose callId cannot match Matrix's. */
const markerAged = (ageMs: number) =>
  vi.fn().mockResolvedValue({
    callId: PUSH_EVENT_ID,
    roomId: ROOM,
    atMs: Date.now() - ageMs,
  });

describe('consumePendingRejectCallId', () => {
  it('rejects the call the user declined moments ago (cold start from push)', async () => {
    const { consumePendingRejectCallId } = await loadBridge(noMarker(), markerAged(3_000));

    await expect(consumePendingRejectCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(true);
  });

  it('leaves a later call from the same room alone once the marker has aged out', async () => {
    const staleByMinutes = markerAged(5 * 60_000 + 37_000);
    const { consumePendingRejectCallId } = await loadBridge(noMarker(), staleByMinutes);

    await expect(consumePendingRejectCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(false);
  });

  it('still rejects the exact call named by an old marker', async () => {
    const old = vi.fn().mockResolvedValue({
      callId: MATRIX_CALL_ID,
      roomId: ROOM,
      atMs: Date.now() - 10 * 60_000,
    });
    const { consumePendingRejectCallId } = await loadBridge(noMarker(), old);

    await expect(consumePendingRejectCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(true);
  });

  it('holds the room fallback right up to the TTL', async () => {
    const { consumePendingRejectCallId } = await loadBridge(
      noMarker(),
      markerAged(PENDING_MARKER_ROOM_TTL_MS - 5_000),
    );

    await expect(consumePendingRejectCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(true);
  });
});

describe('markers seeded by wire()', () => {
  // The production sequence during a live session: wire() reads the markers
  // once at boot and keeps them in module state, so a later consume resolves
  // against that copy rather than peeking at native again. The stamp has to
  // survive that hop or the marker silently becomes ageless.
  const callService = { answerCall: vi.fn(), rejectCall: vi.fn(), hangup: vi.fn() };

  it('ages out a stale reject marker that wire() carried over', async () => {
    const stale = markerAged(5 * 60_000 + 37_000);
    const mod = await loadBridge(noMarker(), stale);
    await mod.nativeCallBridge.wire(callService);
    stale.mockResolvedValue({ callId: null, roomId: null, atMs: 0 });

    await expect(mod.consumePendingRejectCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(false);
  });

  it('a re-run of wire() does not leave a previous call\'s room behind', async () => {
    // wire() runs on every login, and module state outlives it. A native
    // marker naming a call but no room used to overwrite only the callId and
    // the stamp, leaving the earlier call's room paired with a fresh time —
    // and the room fallback would then swallow an unrelated call.
    const answer = vi.fn().mockResolvedValue({
      callId: PUSH_EVENT_ID,
      roomId: ROOM,
      atMs: Date.now() - 4_000,
    });
    const mod = await loadBridge(answer, noMarker());
    await mod.nativeCallBridge.wire(callService);

    answer.mockResolvedValue({ callId: 'later-call', roomId: null, atMs: Date.now() });
    await mod.nativeCallBridge.wire(callService);
    answer.mockResolvedValue({ callId: null, roomId: null, atMs: 0 });

    await expect(mod.consumePendingAnswerCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(false);
  });

  it('still honours a fresh reject marker that wire() carried over', async () => {
    const fresh = markerAged(4_000);
    const mod = await loadBridge(noMarker(), fresh);
    await mod.nativeCallBridge.wire(callService);
    fresh.mockResolvedValue({ callId: null, roomId: null, atMs: 0 });

    await expect(mod.consumePendingRejectCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(true);
  });
});

describe('consumePendingAnswerCallId', () => {
  it('answers the call the user accepted moments ago (cold start from push)', async () => {
    const { consumePendingAnswerCallId } = await loadBridge(markerAged(3_000), noMarker());

    await expect(consumePendingAnswerCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(true);
  });

  it('does not auto-answer a later call from the same room on a stale marker', async () => {
    const { consumePendingAnswerCallId } = await loadBridge(
      markerAged(5 * 60_000),
      noMarker(),
    );

    await expect(consumePendingAnswerCallId(MATRIX_CALL_ID, ROOM)).resolves.toBe(false);
  });
});
