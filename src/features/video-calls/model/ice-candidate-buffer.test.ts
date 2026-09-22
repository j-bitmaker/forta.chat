import { describe, it, expect, vi } from "vitest";
import { attachIceCandidateBuffer } from "./ice-candidate-buffer";

/**
 * A peer connection with just the surface the buffer relies on: the remote
 * description appears only once setRemoteDescription resolves, exactly as
 * in the browser and in the native proxy.
 */
function makePc(opts: { failSet?: boolean; failAddFor?: string } = {}) {
  const pc = {
    remoteDescription: null as RTCSessionDescription | null,
    signalingState: "have-local-offer" as RTCSignalingState,
    addIceCandidate: vi.fn(async (c?: RTCIceCandidateInit) => {
      if (opts.failAddFor && c?.candidate === opts.failAddFor) throw new Error("bad candidate");
    }),
    setRemoteDescription: vi.fn(async (d: RTCSessionDescriptionInit) => {
      if (opts.failSet) throw new Error("sdp rejected");
      pc.remoteDescription = { type: d.type, sdp: d.sdp } as RTCSessionDescription;
      pc.signalingState = "stable";
    }),
    // Like the real thing and the native proxy: close() flips the state and
    // dispatches nothing.
    close: vi.fn(() => {
      pc.signalingState = "closed";
    }),
  };
  return pc;
}

const cand = (n: number): RTCIceCandidateInit => ({ candidate: `candidate:${n}`, sdpMid: "0", sdpMLineIndex: 0 });

describe("attachIceCandidateBuffer", () => {
  it("holds candidates that arrive before the remote description and adds them after it, in order", async () => {
    const pc = makePc();
    const originalAdd = pc.addIceCandidate;
    attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);

    await pc.addIceCandidate(cand(1));
    await pc.addIceCandidate(cand(2));
    expect(originalAdd).not.toHaveBeenCalled();

    await pc.setRemoteDescription({ type: "answer", sdp: "v=0" });
    expect(originalAdd.mock.calls.map((c) => c[0]?.candidate)).toEqual(["candidate:1", "candidate:2"]);
  });

  it("passes candidates straight through once the remote description is set", async () => {
    const pc = makePc();
    const originalAdd = pc.addIceCandidate;
    attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);

    await pc.setRemoteDescription({ type: "answer", sdp: "v=0" });
    await pc.addIceCandidate(cand(3));
    expect(originalAdd).toHaveBeenCalledTimes(1);
    expect(originalAdd.mock.calls[0][0]?.candidate).toBe("candidate:3");
  });

  it("keeps end-of-candidates in its place", async () => {
    const pc = makePc();
    const originalAdd = pc.addIceCandidate;
    attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);

    await pc.addIceCandidate(cand(1));
    await pc.addIceCandidate({ candidate: "" });
    await pc.setRemoteDescription({ type: "answer", sdp: "v=0" });
    expect(originalAdd.mock.calls.map((c) => c[0]?.candidate)).toEqual(["candidate:1", ""]);
  });

  it("drops what it holds when the connection closes, and ignores candidates after that", async () => {
    const pc = makePc();
    const originalAdd = pc.addIceCandidate;
    const originalClose = pc.close;
    const info = vi.spyOn(console, "info").mockImplementation(() => {});
    try {
      attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);

      await pc.addIceCandidate(cand(1));
      pc.close();
      expect(originalClose).toHaveBeenCalledTimes(1);
      expect(info.mock.calls.some((c) => String(c[0]).includes("dropping 1 held candidate"))).toBe(true);

      await pc.addIceCandidate(cand(2));
      expect(originalAdd).not.toHaveBeenCalled();
    } finally {
      info.mockRestore();
    }
  });

  it("keeps the queue when setRemoteDescription fails and flushes on the retry", async () => {
    const failing = makePc({ failSet: true });
    const originalAdd = failing.addIceCandidate;
    const originalSet = failing.setRemoteDescription;
    attachIceCandidateBuffer(failing as unknown as RTCPeerConnection);

    await failing.addIceCandidate(cand(1));
    await expect(failing.setRemoteDescription({ type: "answer", sdp: "bad" })).rejects.toThrow("sdp rejected");
    expect(originalAdd).not.toHaveBeenCalled();

    // The retry succeeds: swap the implementation behind the spy the wrapper captured.
    originalSet.mockImplementation(async (d: RTCSessionDescriptionInit) => {
      failing.remoteDescription = { type: d.type, sdp: d.sdp } as RTCSessionDescription;
    });
    await failing.setRemoteDescription({ type: "answer", sdp: "v=0" });
    expect(originalAdd).toHaveBeenCalledTimes(1);
  });

  it("does not let one bad held candidate reject setRemoteDescription or cost the others", async () => {
    const pc = makePc({ failAddFor: "candidate:1" });
    const originalAdd = pc.addIceCandidate;
    attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);

    await pc.addIceCandidate(cand(1));
    await pc.addIceCandidate(cand(2));
    await expect(pc.setRemoteDescription({ type: "answer", sdp: "v=0" })).resolves.toBeUndefined();
    expect(originalAdd).toHaveBeenCalledTimes(2);
  });

  it("attaches once per connection", async () => {
    const pc = makePc();
    const originalAdd = pc.addIceCandidate;
    const originalSet = pc.setRemoteDescription;
    attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);
    const wrappedSet = pc.setRemoteDescription;
    attachIceCandidateBuffer(pc as unknown as RTCPeerConnection);
    expect(pc.setRemoteDescription).toBe(wrappedSet);

    await pc.setRemoteDescription({ type: "answer", sdp: "v=0" });
    await pc.addIceCandidate(cand(1));
    expect(originalAdd).toHaveBeenCalledTimes(1);
    expect(originalSet).toHaveBeenCalledTimes(1);
  });
});
