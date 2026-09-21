import { describe, it, expect, vi, beforeEach } from "vitest";
import { MatrixClientService } from "../matrix-client";

/**
 * The unread badge takes out the call hangups the server counts. Those the live timeline lost
 * to a gap are fetched with /messages, filtered to m.call.hangup, backwards from the gap.
 */
describe("MatrixClientService.fetchRoomHangups", () => {
  let service: MatrixClientService;
  let createMessagesRequest: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    service = new MatrixClientService("test.invalid");
    createMessagesRequest = vi.fn().mockResolvedValue({
      chunk: [{ type: "m.call.hangup", event_id: "$h" }],
      end: "t2",
    });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (service as any).client = { createMessagesRequest, getUserId: () => "@me:test.invalid" };
  });

  it("asks only for the call signals the badge takes back, backwards from the token", async () => {
    const res = await service.fetchRoomHangups("!r:test.invalid", "t1", 50);

    expect(res).toEqual({ chunk: [{ type: "m.call.hangup", event_id: "$h" }], end: "t2" });
    expect(createMessagesRequest).toHaveBeenCalledTimes(1);
    const [roomId, from, limit, dir, filter] = createMessagesRequest.mock.calls[0];
    expect(roomId).toBe("!r:test.invalid");
    expect(from).toBe("t1");
    expect(limit).toBe(50);
    expect(dir).toBe("b");
    expect(filter.getRoomTimelineFilterComponent().toJSON().types).toEqual([
      "m.call.hangup",
      "m.call.select_answer",
      "m.call.invite",
      "m.call.answer",
    ]);
  });

  it("returns null without a client", async () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (service as any).client = null;
    await expect(service.fetchRoomHangups("!r:test.invalid", "t1", 50)).resolves.toBeNull();
  });

  it("returns null and warns when the request fails", async () => {
    createMessagesRequest.mockRejectedValueOnce(new Error("network down"));
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});

    await expect(service.fetchRoomHangups("!r:test.invalid", "t1", 50)).resolves.toBeNull();
    expect(warn).toHaveBeenCalled();

    warn.mockRestore();
  });
});
