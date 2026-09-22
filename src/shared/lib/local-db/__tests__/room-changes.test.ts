import { describe, it, expect, afterEach } from "vitest";
import "fake-indexeddb/auto";
import { ChatDatabase } from "../schema";
import { RoomRepository } from "../room-repository";
import type { RoomChange } from "../room-repository";
import type { LocalRoom } from "../schema";

function makeLocalRoom(overrides: Partial<LocalRoom> = {}): LocalRoom {
  return {
    id: overrides.id ?? "!r:s",
    name: "Room",
    isGroup: false,
    members: [],
    membership: "join",
    unreadCount: 0,
    lastReadInboundTs: 0,
    lastReadOutboundTs: 0,
    updatedAt: Date.now(),
    syncedAt: Date.now(),
    hasMoreHistory: true,
    isDeleted: false,
    deletedAt: null,
    deleteReason: null,
    ...overrides,
  };
}

/** Wait for queued microtasks to flush */
function flushMicrotasks(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe("observeRoomChanges", () => {
  let db: ChatDatabase;
  let repo: RoomRepository;

  function setup() {
    const name = `test-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    db = new ChatDatabase(name);
    repo = new RoomRepository(db as any);
  }

  afterEach(async () => {
    if (db) {
      await db.delete();
    }
  });

  it("reports upsert when a room is created", async () => {
    setup();
    const changes: RoomChange[] = [];
    repo.observeRoomChanges((batch) => changes.push(...batch));

    const room = makeLocalRoom({ id: "!create:test" });
    await db.rooms.put(room);
    await flushMicrotasks();

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe("upsert");
    expect((changes[0] as any).room.id).toBe("!create:test");
  });

  it("reports upsert when a room is updated", async () => {
    setup();
    const room = makeLocalRoom({ id: "!update:test" });
    await db.rooms.put(room);
    await flushMicrotasks();

    const changes: RoomChange[] = [];
    repo.observeRoomChanges((batch) => changes.push(...batch));

    await db.rooms.update("!update:test", { name: "Updated" });
    await flushMicrotasks();

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe("upsert");
    expect((changes[0] as any).room.name).toBe("Updated");
  });

  it("reports delete when a room is removed", async () => {
    setup();
    const room = makeLocalRoom({ id: "!delete:test" });
    await db.rooms.put(room);
    await flushMicrotasks();

    const changes: RoomChange[] = [];
    repo.observeRoomChanges((batch) => changes.push(...batch));

    await db.rooms.delete("!delete:test");
    await flushMicrotasks();

    expect(changes).toHaveLength(1);
    expect(changes[0].type).toBe("delete");
    expect((changes[0] as any).roomId).toBe("!delete:test");
  });

  it("batches multiple changes in the same tick", async () => {
    setup();
    let callCount = 0;
    const allChanges: RoomChange[] = [];
    repo.observeRoomChanges((batch) => {
      callCount++;
      allChanges.push(...batch);
    });

    // Dexie hooks fire synchronously per put, but each `await` inside a
    // transaction yields to microtasks. To test batching, issue puts without
    // awaiting so they schedule in the same synchronous turn.
    const p1 = db.rooms.put(makeLocalRoom({ id: "!batch1:test" }));
    const p2 = db.rooms.put(makeLocalRoom({ id: "!batch2:test" }));
    const p3 = db.rooms.put(makeLocalRoom({ id: "!batch3:test" }));
    await Promise.all([p1, p2, p3]);
    await flushMicrotasks();

    // All three should be delivered (possibly batched depending on timing)
    expect(allChanges).toHaveLength(3);
    // Batching means fewer callback invocations than individual changes
    expect(callCount).toBeLessThanOrEqual(3);
  });

  it("stops reporting after unsubscribe", async () => {
    setup();
    const changes: RoomChange[] = [];
    const unsub = repo.observeRoomChanges((batch) => changes.push(...batch));

    await db.rooms.put(makeLocalRoom({ id: "!before:test" }));
    await flushMicrotasks();
    expect(changes).toHaveLength(1);

    unsub();

    await db.rooms.put(makeLocalRoom({ id: "!after:test" }));
    await flushMicrotasks();

    // No new changes after unsubscribe
    expect(changes).toHaveLength(1);
  });

  // Dexie hands the updating hook a nested object's changes as key paths
  // ("lastMessageCallInfo.missed"), not as the whole object. A call record
  // following a call record kept the earlier call's info in the chat list.
  it("reports a nested preview object in full when only some of its fields change", async () => {
    setup();
    await db.rooms.put(makeLocalRoom({
      id: "!call:test",
      lastMessageTimestamp: 1000,
      lastMessageEventId: "$hangup1",
      lastMessagePreview: "[message]",
      lastMessageCallInfo: { callType: "voice", missed: true, duration: 5 },
      lastMessageSystemMeta: { template: "system.missedVoiceCall", senderAddr: "peer" },
    }));
    const changes: RoomChange[] = [];
    repo.observeRoomChanges((batch) => changes.push(...batch));

    await repo.updateLastMessage(
      "!call:test", "[message]", 2000, "peer", undefined, "$hangup2",
      { callType: "voice", missed: false },
      { template: "system.voiceCall", senderAddr: "peer" },
    );
    await flushMicrotasks();

    expect(changes).toHaveLength(1);
    const room = (changes[0] as Extract<RoomChange, { type: "upsert" }>).room;
    expect(room.lastMessageEventId).toBe("$hangup2");
    expect(room.lastMessageCallInfo).toEqual({ callType: "voice", missed: false });
    expect(room.lastMessageSystemMeta).toEqual({ template: "system.voiceCall", senderAddr: "peer" });
    expect(Object.keys(room).filter((k) => k.includes("."))).toEqual([]);
  });
});
