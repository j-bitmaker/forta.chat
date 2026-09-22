/**
 * Hold remote ICE candidates until the remote description is in place.
 *
 * matrix-js-sdk's `onAnswerReceived` flushes the candidates it buffered while
 * waiting for the answer *before* it applies the answer itself
 * (`addBufferedIceCandidates()` runs ahead of `setRemoteDescription()` in
 * call.ts), and it swallows the resulting rejection at info level. Both
 * engines refuse a candidate while `remoteDescription` is null — the browser
 * with an InvalidStateError, the native plugin with a reject — so every
 * candidate the callee sent before its answer was processed is lost. A callee
 * that had already finished gathering never gets a second chance, and the
 * caller sits in "Connecting…" until ICE fails.
 *
 * The wrapper keeps those candidates in arrival order and adds them right
 * after the remote description lands. It is engine-agnostic: it only relies
 * on `remoteDescription`, `signalingState`, `setRemoteDescription`,
 * `addIceCandidate` and `close`, all of which the native proxy implements.
 * Only the promise forms are wrapped; the SDK and the proxy never use the
 * legacy callback overloads.
 */

const ATTACHED_FLAG = "__iceCandidateBufferAttached";
const TAG = "[ice-buffer]";

type Candidate = RTCIceCandidateInit | RTCIceCandidate | null | undefined;

export function attachIceCandidateBuffer(pc: RTCPeerConnection): void {
  const marker = pc as unknown as Record<string, unknown>;
  if (marker[ATTACHED_FLAG]) return;
  marker[ATTACHED_FLAG] = true;

  // Bind the originals now: wrapping is per instance and must not chain on a
  // later re-wrap or on whatever another wrapper installs afterwards.
  const originalAdd = pc.addIceCandidate.bind(pc) as (candidate?: Candidate) => Promise<void>;
  const originalSetRemote = pc.setRemoteDescription.bind(pc) as (
    description: RTCSessionDescriptionInit,
  ) => Promise<void>;
  let pending: Candidate[] = [];

  const isClosed = () => pc.signalingState === "closed";
  const hasRemoteDescription = () => pc.remoteDescription != null;

  // The DOM declarations carry legacy callback overloads; the SDK and the
  // proxy only ever use the promise form.
  pc.addIceCandidate = (async (candidate?: Candidate): Promise<void> => {
    if (isClosed()) return;
    if (!hasRemoteDescription()) {
      pending.push(candidate);
      console.info(`${TAG} holding candidate #${pending.length} until the remote description is set`);
      return;
    }
    await originalAdd(candidate);
  }) as RTCPeerConnection["addIceCandidate"];

  pc.setRemoteDescription = (async (description: RTCSessionDescriptionInit): Promise<void> => {
    // A failed setRemoteDescription keeps the queue: a retry will flush it.
    await originalSetRemote(description);
    if (pending.length === 0) return;
    const queued = pending;
    pending = [];
    if (isClosed()) return;
    console.info(`${TAG} remote description set, adding ${queued.length} held candidate(s)`);
    for (const candidate of queued) {
      try {
        await originalAdd(candidate);
      } catch (e) {
        // One bad candidate must not cost the rest of the queue.
        console.warn(`${TAG} held candidate failed:`, e);
      }
    }
  }) as RTCPeerConnection["setRemoteDescription"];

  // close() dispatches no signalingstatechange — neither in the browser (per
  // spec) nor in the native proxy — so the queue is released from close itself.
  // The live isClosed() checks above are what keep a late candidate out; this
  // only stops a closed connection from holding on to what it never needed.
  const originalClose = pc.close.bind(pc);
  pc.close = (): void => {
    if (pending.length > 0) {
      console.info(`${TAG} connection closed, dropping ${pending.length} held candidate(s)`);
      pending = [];
    }
    originalClose();
  };
}
