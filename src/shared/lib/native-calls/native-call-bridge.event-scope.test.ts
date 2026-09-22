import { describe, it, expect, vi, type Mock } from 'vitest';

/**
 * A native `callEnded` / `callDeclined` must act on the call it names, not on
 * whatever call JS happens to hold.
 *
 * Measured on the Samsung 2026-09-10, while verifying the orphan-slot release:
 * Telecom refuses a new incoming call while this app holds a RINGING
 * self-managed one, so the stranded connection is disconnected first — and its
 * `callEnded` landed 3 ms after JS had already offered the NEXT call, hanging
 * up a call that had been ringing for 116 ms.
 */

const ROOM = '!XfcsFwyJkEXLRTnPzc:matrix.pocketnet.app';
const OLD_CALL = '1789002434417pyhl9z3azvtR556V';
const NEW_CALL = '1789002456340bHi7Fz1DUPFPFOt4';
const OTHER_ROOM = '!QqvSkKmzUfPtNbxWyE:matrix.pocketnet.app';
const PUSH_EVENT_ID = '$ZM8kQ5-push-event-id';
const OTHER_PUSH_EVENT_ID = '$aLm1Wk-other-push-event-id';

type Listeners = Record<string, (payload: { callId: string; roomId?: string }) => void>;

async function wireBridge(
  currentCallId: string | undefined,
  currentRoom = ROOM,
  nativeAnswerMarker: { callId: string | null; roomId: string | null; atMs: number } = {
    callId: null,
    roomId: null,
    atMs: 0,
  },
) {
  const listeners: Listeners = {};
  vi.resetModules();
  vi.doMock('@capacitor/core', () => ({
    registerPlugin: () =>
      new Proxy(
        {
          addListener: vi.fn((event: string, handler: (p: { callId: string }) => void) => {
            listeners[event] = handler;
            return Promise.resolve({ remove: vi.fn() });
          }),
          getPendingAnswer: vi.fn().mockResolvedValue(nativeAnswerMarker),
          getPendingReject: vi.fn().mockResolvedValue({ callId: null, roomId: null, atMs: 0 }),
        },
        { get: (t: Record<string, unknown>, p: string) => t[p] ?? vi.fn().mockResolvedValue({}) },
      ),
  }));
  vi.doMock('@/shared/lib/platform', () => ({
    isNative: true,
    isAndroid: true,
    isIOS: false,
    isElectron: false,
    isWeb: false,
    currentPlatform: 'android' as const,
  }));
  vi.doMock('@/shared/lib/native-webrtc/native-webrtc-bridge', () => ({
    NativeWebRTC: { addListener: vi.fn() },
  }));
  vi.doMock('@/entities/call', () => ({
    useCallStore: () => ({ matrixCall: { callId: currentCallId, roomId: ROOM } }),
  }));

  let held = currentCallId;
  const callService = {
    answerCall: vi.fn(),
    rejectCall: vi.fn() as Mock,
    hangup: vi.fn() as Mock,
    currentCall: () => ({ callId: held, roomId: currentRoom }),
    setCurrent: (callId: string | undefined) => {
      held = callId;
    },
  };
  const bridgeModule = await import('./native-call-bridge');
  await bridgeModule.nativeCallBridge.wire(callService);
  return { listeners, callService, bridgeModule };
}

describe('native call-lifecycle events are scoped to the call they name', () => {
  it('ends the call the event names', async () => {
    const { listeners, callService } = await wireBridge(NEW_CALL);

    listeners.callEnded({ callId: NEW_CALL, roomId: ROOM });

    expect(callService.hangup).toHaveBeenCalled();
  });

  it('leaves the current call alone when the event names a call that already finished', async () => {
    const { listeners, callService } = await wireBridge(NEW_CALL);

    listeners.callEnded({ callId: OLD_CALL, roomId: ROOM });

    expect(callService.hangup).not.toHaveBeenCalled();
  });

  it('still ends the call for a push-keyed id from the same room, which can never equal a Matrix callId', async () => {
    // Cold start from push: the decline the user tapped on the native ringer
    // has to reach the call, and its id will never match one from the SDK.
    const { listeners, callService } = await wireBridge(NEW_CALL);

    listeners.callEnded({ callId: PUSH_EVENT_ID, roomId: ROOM });

    expect(callService.hangup).toHaveBeenCalled();
  });

  it('leaves the current call alone for a push-keyed event from another room', async () => {
    // A stranded push connection displaced by a second caller: its id cannot be
    // compared, so without the room it would end whichever call JS held.
    const { listeners, callService } = await wireBridge(NEW_CALL, ROOM);

    listeners.callEnded({ callId: PUSH_EVENT_ID, roomId: OTHER_ROOM });

    expect(callService.hangup).not.toHaveBeenCalled();
  });

  it('still ends the call when JS holds no id to compare against', async () => {
    const { listeners, callService } = await wireBridge(undefined);

    listeners.callEnded({ callId: OLD_CALL, roomId: ROOM });

    expect(callService.hangup).toHaveBeenCalled();
  });

  it('does not decline the current call for a decline that names a finished one', async () => {
    // Reachable without any orphan release: armRingTimeout rejects a stale
    // RINGING connection 45 s in, which can land while a newer call is up.
    const { listeners, callService } = await wireBridge(NEW_CALL);

    listeners.callDeclined({ callId: OLD_CALL, roomId: ROOM });

    expect(callService.rejectCall).not.toHaveBeenCalled();
  });

  it('declines the call a decline actually names', async () => {
    const { listeners, callService } = await wireBridge(NEW_CALL);

    listeners.callDeclined({ callId: NEW_CALL, roomId: ROOM });

    expect(callService.rejectCall).toHaveBeenCalled();
  });

  it('leaves the current call alone once JS knows which connection is its own', async () => {
    // JS adopted its call from a marker whose id IS the SDK call's id, which
    // proves the connection behind it is keyed the same way. A push-keyed
    // teardown from the same room therefore belongs to some other connection —
    // the same-room overlap the room fallback alone cannot see.
    const { listeners, callService, bridgeModule } = await wireBridge(NEW_CALL, ROOM, {
      callId: NEW_CALL,
      roomId: ROOM,
      atMs: Date.now(),
    });
    expect(await bridgeModule.consumePendingAnswerCallId(NEW_CALL, ROOM)).toBe(true);

    listeners.callEnded({ callId: PUSH_EVENT_ID, roomId: ROOM });

    expect(callService.hangup).not.toHaveBeenCalled();
  });

  it('never learns a native key from a room-only match', async () => {
    // The room branch is a guess: the marker may be a leftover from another
    // connection in this room whose invite never reached JS. Believing it would
    // bind the WRONG key and then withhold this call's own teardown for the
    // rest of its life — JS holding a call Telecom has ended is the worse
    // failure by a wide margin, so an unprovable correlation records nothing
    // and the room fallback stays in charge.
    const { listeners, callService, bridgeModule } = await wireBridge(NEW_CALL, ROOM, {
      callId: PUSH_EVENT_ID,
      roomId: ROOM,
      atMs: Date.now(),
    });
    expect(await bridgeModule.consumePendingAnswerCallId(NEW_CALL, ROOM)).toBe(true);

    listeners.callEnded({ callId: OTHER_PUSH_EVENT_ID, roomId: ROOM });

    expect(callService.hangup).toHaveBeenCalled();
  });

  it('still ends the call when the event names the connection JS adopted', async () => {
    const { listeners, callService, bridgeModule } = await wireBridge(NEW_CALL, ROOM, {
      callId: NEW_CALL,
      roomId: ROOM,
      atMs: Date.now(),
    });
    expect(await bridgeModule.consumePendingAnswerCallId(NEW_CALL, ROOM)).toBe(true);

    listeners.callEnded({ callId: NEW_CALL, roomId: ROOM });

    expect(callService.hangup).toHaveBeenCalled();
  });

  it('does not apply one call\'s native key to the next call JS holds', async () => {
    // The binding is read only for the call it was recorded against, so a
    // finished call cannot make the next one look like a different connection.
    const { listeners, callService, bridgeModule } = await wireBridge(OLD_CALL, ROOM, {
      callId: OLD_CALL,
      roomId: ROOM,
      atMs: Date.now(),
    });
    expect(await bridgeModule.consumePendingAnswerCallId(OLD_CALL, ROOM)).toBe(true);
    callService.setCurrent(NEW_CALL);

    listeners.callEnded({ callId: OTHER_PUSH_EVENT_ID, roomId: ROOM });

    expect(callService.hangup).toHaveBeenCalled();
  });
});
