/**
 * Tests for webrtcDiagnostics — Session 03 event-emitter API.
 *
 * The diagnostics module already polls getStats every 3s and detects sustained
 * zero-audio. Session 03 adds an event-emitter so the UI can show a toast
 * ("Нет входящего аудио") instead of silently logging to console.error.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { webrtcDiagnostics } from "./webrtc-diagnostics";

interface FakeStat {
  type: string;
  kind?: string;
  bytesSent?: number;
  bytesReceived?: number;
  packetsSent?: number;
  packetsReceived?: number;
}

function buildStatsReport(
  inboundBytes: number,
  outboundBytes: number,
): Map<string, FakeStat> {
  const map = new Map<string, FakeStat>();
  map.set("outbound-audio-0", {
    type: "outbound-rtp",
    kind: "audio",
    bytesSent: outboundBytes,
    packetsSent: outboundBytes > 0 ? Math.floor(outboundBytes / 100) : 0,
  });
  map.set("inbound-audio-0", {
    type: "inbound-rtp",
    kind: "audio",
    bytesReceived: inboundBytes,
    packetsReceived: inboundBytes > 0 ? Math.floor(inboundBytes / 100) : 0,
  });
  return map;
}

function makeMockPc(reports: Array<Map<string, FakeStat>>): RTCPeerConnection {
  const queue = [...reports];
  return {
    iceConnectionState: "connected",
    iceGatheringState: "complete",
    signalingState: "stable",
    connectionState: "connected",
    oniceconnectionstatechange: null,
    onsignalingstatechange: null,
    onconnectionstatechange: null,
    onicecandidate: null,
    getStats: vi.fn(async () => {
      const next =
        queue.shift() ??
        reports[reports.length - 1] ??
        buildStatsReport(0, 0);
      return next as unknown as RTCStatsReport;
    }),
  } as unknown as RTCPeerConnection;
}

describe("webrtcDiagnostics — event emitter (Session 03)", () => {
  let warningHandler: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    warningHandler = vi.fn();
    webrtcDiagnostics.addEventListener(
      "warning",
      warningHandler as unknown as EventListener,
    );
  });

  afterEach(() => {
    webrtcDiagnostics.detach();
    webrtcDiagnostics.removeEventListener(
      "warning",
      warningHandler as unknown as EventListener,
    );
    vi.useRealTimers();
  });

  it("emits 'warning' with type=no_inbound_audio after sustained zero bytesReceived", async () => {
    // 4 reports: outbound increments (mic works), inbound stays at 0.
    const reports = [
      buildStatsReport(0, 1_000),
      buildStatsReport(0, 2_000),
      buildStatsReport(0, 3_000),
      buildStatsReport(0, 4_000),
    ];
    webrtcDiagnostics.attach(makeMockPc(reports));

    // Each poll = 3s; need at least 3 polls to trigger ZERO_AUDIO_ALERT_THRESHOLD.
    await vi.advanceTimersByTimeAsync(12_000);

    const noInboundCalls = warningHandler.mock.calls.filter((c) => {
      const ev = c[0] as CustomEvent;
      return ev?.detail?.type === "no_inbound_audio";
    });
    expect(noInboundCalls.length).toBeGreaterThanOrEqual(1);
  });

  it("emits 'warning' with type=no_outbound_audio after sustained zero bytesSent", async () => {
    const reports = [
      buildStatsReport(1_000, 0),
      buildStatsReport(2_000, 0),
      buildStatsReport(3_000, 0),
      buildStatsReport(4_000, 0),
    ];
    webrtcDiagnostics.attach(makeMockPc(reports));

    await vi.advanceTimersByTimeAsync(12_000);

    const noOutboundCalls = warningHandler.mock.calls.filter((c) => {
      const ev = c[0] as CustomEvent;
      return ev?.detail?.type === "no_outbound_audio";
    });
    expect(noOutboundCalls.length).toBeGreaterThanOrEqual(1);
  });

  it("emits each warning type at most once per session (no spam)", async () => {
    // Sustained zero-inbound for many polls — should only emit once.
    const reports = Array.from({ length: 10 }, (_, i) =>
      buildStatsReport(0, (i + 1) * 1_000),
    );
    webrtcDiagnostics.attach(makeMockPc(reports));

    await vi.advanceTimersByTimeAsync(35_000);

    const noInboundCalls = warningHandler.mock.calls.filter((c) => {
      const ev = c[0] as CustomEvent;
      return ev?.detail?.type === "no_inbound_audio";
    });
    expect(noInboundCalls.length).toBe(1);
  });

  it("does NOT emit warning when audio is flowing", async () => {
    const reports = [
      buildStatsReport(1_000, 1_000),
      buildStatsReport(2_000, 2_000),
      buildStatsReport(3_000, 3_000),
      buildStatsReport(4_000, 4_000),
    ];
    webrtcDiagnostics.attach(makeMockPc(reports));

    await vi.advanceTimersByTimeAsync(15_000);

    expect(warningHandler).not.toHaveBeenCalled();
  });

  it("resets warning state on detach so a new attach can re-emit", async () => {
    const firstReports = [
      buildStatsReport(0, 1_000),
      buildStatsReport(0, 2_000),
      buildStatsReport(0, 3_000),
      buildStatsReport(0, 4_000),
    ];
    webrtcDiagnostics.attach(makeMockPc(firstReports));
    await vi.advanceTimersByTimeAsync(13_000);

    const firstCount = warningHandler.mock.calls.length;
    expect(firstCount).toBeGreaterThanOrEqual(1);

    webrtcDiagnostics.detach();
    warningHandler.mockClear();

    const secondReports = [
      buildStatsReport(0, 1_000),
      buildStatsReport(0, 2_000),
      buildStatsReport(0, 3_000),
      buildStatsReport(0, 4_000),
    ];
    webrtcDiagnostics.attach(makeMockPc(secondReports));
    await vi.advanceTimersByTimeAsync(13_000);

    // After detach + re-attach we expect the warning to be fresh.
    expect(warningHandler).toHaveBeenCalled();
  });
});

describe("webrtcDiagnostics — ICE summary and the no-relay warning (O05)", () => {
  let warningHandler: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers();
    warningHandler = vi.fn();
    webrtcDiagnostics.addEventListener("warning", warningHandler as unknown as EventListener);
  });

  afterEach(() => {
    webrtcDiagnostics.detach();
    webrtcDiagnostics.removeEventListener("warning", warningHandler as unknown as EventListener);
    vi.useRealTimers();
  });

  function candidate(type: string): RTCPeerConnectionIceEvent {
    return { candidate: { type, protocol: "udp", address: "10.0.0.1", port: 1 } } as unknown as RTCPeerConnectionIceEvent;
  }

  function warningTypes(): string[] {
    return warningHandler.mock.calls.map((c) => (c[0] as CustomEvent).detail.type);
  }

  it("counts gathered candidates by type and reads TURN servers from the configuration", () => {
    const pc = makeMockPc([]) as unknown as RTCPeerConnection & { getConfiguration: () => RTCConfiguration };
    pc.getConfiguration = () => ({
      iceServers: [
        { urls: "stun:stun.example.org" },
        { urls: ["turn:relay.example.org:3478", "turns:relay.example.org:443?transport=tcp"] },
      ],
    });
    webrtcDiagnostics.attach(pc);
    pc.onicecandidate?.(candidate("host"));
    pc.onicecandidate?.(candidate("srflx"));
    pc.onicecandidate?.(candidate("relay"));
    pc.onicecandidate?.({ candidate: null } as unknown as RTCPeerConnectionIceEvent);

    expect(webrtcDiagnostics.getIceSummary()).toEqual({
      total: 3,
      relay: 1,
      host: 1,
      srflx: 1,
      selectedPairType: null,
      lastIceState: null,
      turnServers: 2,
    });
  });

  it("reports turnServers=null when the engine exposes no configuration", () => {
    const pc = makeMockPc([]);
    webrtcDiagnostics.attach(pc);
    expect(webrtcDiagnostics.getIceSummary().turnServers).toBeNull();
  });

  it("emits ice_failed_no_relay once when ICE fails with no relay candidate", () => {
    const pc = makeMockPc([]) as RTCPeerConnection & { iceConnectionState: string };
    webrtcDiagnostics.attach(pc);
    pc.onicecandidate?.(candidate("host"));
    pc.iceConnectionState = "failed";
    pc.oniceconnectionstatechange?.(new Event("iceconnectionstatechange"));
    pc.oniceconnectionstatechange?.(new Event("iceconnectionstatechange"));

    expect(warningTypes()).toEqual(["ice_failed_no_relay"]);
  });

  it("stays quiet on failure when a relay candidate was gathered", () => {
    const pc = makeMockPc([]) as RTCPeerConnection & { iceConnectionState: string };
    webrtcDiagnostics.attach(pc);
    pc.onicecandidate?.(candidate("relay"));
    pc.iceConnectionState = "failed";
    pc.oniceconnectionstatechange?.(new Event("iceconnectionstatechange"));

    expect(warningTypes()).toEqual([]);
  });

  it("still forwards the state change to the SDK's own handler", () => {
    const pc = makeMockPc([]);
    const sdkHandler = vi.fn();
    pc.oniceconnectionstatechange = sdkHandler;
    webrtcDiagnostics.attach(pc);
    const ev = new Event("iceconnectionstatechange");
    pc.oniceconnectionstatechange?.(ev);
    expect(sdkHandler).toHaveBeenCalledWith(ev);
  });
});
