import { describe, it, expect } from "vitest";
import { addLocalCandidatesToSdp } from "./sdp-local-candidates";

const AUDIO_VIDEO = [
  "v=0",
  "o=- 1 2 IN IP4 127.0.0.1",
  "s=-",
  "t=0 0",
  "a=group:BUNDLE 0 1",
  "m=audio 9 UDP/TLS/RTP/SAVPF 111",
  "c=IN IP4 0.0.0.0",
  "a=ice-ufrag:AAAA",
  "a=mid:0",
  "a=rtpmap:111 opus/48000/2",
  "m=video 9 UDP/TLS/RTP/SAVPF 96",
  "c=IN IP4 0.0.0.0",
  "a=ice-ufrag:AAAA",
  "a=mid:1",
  "a=rtpmap:96 VP8/90000",
  "",
].join("\r\n");

const host = (ufrag: string) => `candidate:1 1 udp 2122260223 192.0.2.1 50000 typ host generation 0 ufrag ${ufrag} network-id 1`;
const relay = (ufrag: string) => `candidate:2 1 udp 41885695 198.51.100.7 49596 typ relay raddr 0.0.0.0 rport 0 generation 0 ufrag ${ufrag}`;

describe("addLocalCandidatesToSdp", () => {
  it("adds gathered candidates at the end of their media section", () => {
    const sdp = addLocalCandidatesToSdp(AUDIO_VIDEO, [
      { candidate: host("AAAA"), sdpMid: "0", sdpMLineIndex: 0 },
      { candidate: relay("AAAA"), sdpMid: "0", sdpMLineIndex: 0 },
    ]);

    const lines = sdp.split("\r\n");
    const rtpmap = lines.indexOf("a=rtpmap:111 opus/48000/2");
    expect(lines.slice(rtpmap + 1, rtpmap + 3)).toEqual([`a=${host("AAAA")}`, `a=${relay("AAAA")}`]);
    expect(lines[rtpmap + 3]).toBe("m=video 9 UDP/TLS/RTP/SAVPF 96");
    expect(sdp.endsWith("a=rtpmap:96 VP8/90000\r\n")).toBe(true);
  });

  it("finds the section by index when the candidate has no mid", () => {
    const sdp = addLocalCandidatesToSdp(AUDIO_VIDEO, [{ candidate: host("AAAA"), sdpMid: null, sdpMLineIndex: 1 }]);

    expect(sdp.endsWith(`a=rtpmap:96 VP8/90000\r\na=${host("AAAA")}\r\n`)).toBe(true);
  });

  it("leaves out candidates of an earlier ICE generation", () => {
    const sdp = addLocalCandidatesToSdp(AUDIO_VIDEO, [
      { candidate: host("OLD1"), sdpMid: "0", sdpMLineIndex: 0 },
      { candidate: relay("AAAA"), sdpMid: "0", sdpMLineIndex: 0 },
    ]);

    expect(sdp).not.toContain("ufrag OLD1");
    expect(sdp).toContain(`a=${relay("AAAA")}`);
  });

  it("does not repeat a candidate the description already carries", () => {
    const once = addLocalCandidatesToSdp(AUDIO_VIDEO, [{ candidate: host("AAAA"), sdpMid: "0", sdpMLineIndex: 0 }]);
    const twice = addLocalCandidatesToSdp(once, [{ candidate: host("AAAA"), sdpMid: "0", sdpMLineIndex: 0 }]);

    expect(twice).toBe(once);
  });

  it("returns the description unchanged without candidates or a matching section", () => {
    expect(addLocalCandidatesToSdp(AUDIO_VIDEO, [])).toBe(AUDIO_VIDEO);
    expect(addLocalCandidatesToSdp(AUDIO_VIDEO, [{ candidate: "", sdpMid: "0", sdpMLineIndex: 0 }])).toBe(AUDIO_VIDEO);
    expect(addLocalCandidatesToSdp(AUDIO_VIDEO, [{ candidate: host("AAAA"), sdpMid: "7", sdpMLineIndex: 7 }])).toBe(AUDIO_VIDEO);
  });
});
