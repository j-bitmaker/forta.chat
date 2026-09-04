import { describe, it, expect } from "vitest";
import { dedupeCallEvents } from "./dedupe-call-events";

type Row = { id: string; callInfo?: { callType: "voice" | "video"; missed: boolean; callId?: string } };

const call = (id: string, callId?: string, missed = false): Row => ({
  id,
  callInfo: { callType: "voice", missed, callId },
});
const text = (id: string): Row => ({ id });

describe("dedupeCallEvents", () => {
  it("keeps one record when both sides hang up the same call", () => {
    // The real shape of #1027: two hangup events, different event ids, one call.
    const rows = [call("ev1", "call-a"), call("ev2", "call-a")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["ev1"]);
  });

  it("keeps the earliest record so the timeline does not shift", () => {
    const rows = [call("first", "call-a"), call("second", "call-a")];

    expect(dedupeCallEvents(rows)[0].id).toBe("first");
  });

  it("keeps separate calls apart", () => {
    const rows = [call("ev1", "call-a"), call("ev2", "call-b"), call("ev3", "call-a")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["ev1", "ev2"]);
  });

  it("leaves non-call messages untouched and in place", () => {
    const rows = [text("m1"), call("ev1", "call-a"), text("m2"), call("ev2", "call-a")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["m1", "ev1", "m2"]);
  });

  it("keeps every record written before call ids were stored", () => {
    // Old history has no id to group on; dropping such rows on a guess would
    // silently rewrite what the user already saw.
    const rows = [call("old1"), call("old2")];

    expect(dedupeCallEvents(rows).map((r) => r.id)).toEqual(["old1", "old2"]);
  });

  it("returns an empty list unchanged", () => {
    expect(dedupeCallEvents([])).toEqual([]);
  });
});
