import { describe, it, expect } from "vitest";
import { isFrontFacingStream, isFrontFacingTrack, isFrontFacingByLabel } from "./camera-facing";

function makeTrack(facingMode: string | undefined): MediaStreamTrack {
  return {
    getSettings: () => (facingMode === undefined ? {} : { facingMode }),
  } as unknown as MediaStreamTrack;
}

function makeStream(facingMode: string | undefined): MediaStream {
  const track = makeTrack(facingMode);
  return {
    getVideoTracks: () => [track],
  } as unknown as MediaStream;
}

describe("isFrontFacingTrack (WEE-36 / forta-bugs#749)", () => {
  it("returns true for user-facing (selfie) camera — local preview must mirror", () => {
    expect(isFrontFacingTrack(makeTrack("user"))).toBe(true);
  });

  it("returns false for back camera — local preview must NOT mirror", () => {
    expect(isFrontFacingTrack(makeTrack("environment"))).toBe(false);
  });

  it("returns false for side-mounted cameras", () => {
    expect(isFrontFacingTrack(makeTrack("left"))).toBe(false);
    expect(isFrontFacingTrack(makeTrack("right"))).toBe(false);
  });

  it("defaults to true when facingMode is missing (desktop webcam)", () => {
    expect(isFrontFacingTrack(makeTrack(undefined))).toBe(true);
  });

  it("returns true when track is null/undefined (no stream yet)", () => {
    expect(isFrontFacingTrack(null)).toBe(true);
    expect(isFrontFacingTrack(undefined)).toBe(true);
  });

  it("returns true when getSettings throws (older WebView)", () => {
    const track = {
      getSettings: () => {
        throw new Error("not supported");
      },
    } as unknown as MediaStreamTrack;
    expect(isFrontFacingTrack(track)).toBe(true);
  });
});

describe("isFrontFacingStream", () => {
  it("inspects the first video track", () => {
    expect(isFrontFacingStream(makeStream("environment"))).toBe(false);
    expect(isFrontFacingStream(makeStream("user"))).toBe(true);
  });

  it("returns true when the stream has no video track", () => {
    const empty = { getVideoTracks: () => [] } as unknown as MediaStream;
    expect(isFrontFacingStream(empty)).toBe(true);
  });

  it("returns true when the stream itself is null", () => {
    expect(isFrontFacingStream(null)).toBe(true);
  });
});

describe("facing detection without facingMode (native Android engine)", () => {
  // libwebrtc does not populate facingMode, so on Android the label is the
  // only signal. Before this fallback the back camera was mirrored (#939).
  const trackWithLabel = (label: string): MediaStreamTrack =>
    ({ label, getSettings: () => ({}) }) as unknown as MediaStreamTrack;

  it("treats an Android back camera as rear-facing", () => {
    expect(isFrontFacingTrack(trackWithLabel("camera2 0, facing back"))).toBe(false);
  });

  it("treats an Android front camera as user-facing", () => {
    expect(isFrontFacingTrack(trackWithLabel("camera2 1, facing front"))).toBe(true);
  });

  it("recognises the common wordings", () => {
    expect(isFrontFacingByLabel("Rear Camera")).toBe(false);
    expect(isFrontFacingByLabel("Selfie cam")).toBe(true);
    expect(isFrontFacingByLabel("environment")).toBe(false);
  });

  it("says nothing when the label is uninformative", () => {
    // A laptop webcam named "Integrated Camera" must not be guessed at — the
    // caller's default (mirror) is the right answer there.
    expect(isFrontFacingByLabel("Integrated Camera")).toBeUndefined();
    expect(isFrontFacingByLabel("")).toBeUndefined();
    expect(isFrontFacingByLabel(undefined)).toBeUndefined();
  });

  it("keeps mirroring when neither facingMode nor label is available", () => {
    expect(isFrontFacingTrack(trackWithLabel(""))).toBe(true);
  });

  it("lets an explicit facingMode win over the label", () => {
    const conflicting = {
      label: "camera2 0, facing back",
      getSettings: () => ({ facingMode: "user" }),
    } as unknown as MediaStreamTrack;

    expect(isFrontFacingTrack(conflicting)).toBe(true);
  });
});
