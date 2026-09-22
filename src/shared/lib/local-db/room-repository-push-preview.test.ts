import { describe, it, expect, beforeEach, afterEach } from "vitest";
import Dexie from "dexie";
import "fake-indexeddb/auto";
import { RoomRepository } from "./room-repository";
import type { LocalRoom } from "./schema";
import { MessageType } from "@/entities/chat/model/types";

/**
 * Regression: the push placeholder replaced the previous last message's preview text, timestamp,
 * sender and event id but left its reaction, call info, system template and type. Until the real
 * event arrived the chat list row read as the previous call ("📞 Voice call") or kept its 👍
 * next to the new message's count (Samsung, `order1`).
 */
class TestDb extends Dexie {
  rooms!: import("dexie").Table<LocalRoom, string>;
  constructor() {
    super("test-room-repo-push-preview", { indexedDB, IDBKeyRange });
    this.version(1).stores({ rooms: "id, membership, updatedAt, isDeleted" });
  }
}

const ROOM = "!peer:s";

function callRoom(): LocalRoom {
  return {
    id: ROOM,
    name: "Peer",
    isGroup: false,
    members: ["me", "peer"],
    membership: "join",
    unreadCount: 0,
    updatedAt: 1000,
    hasMoreHistory: true,
    lastReadInboundTs: 0,
    lastReadOutboundTs: 0,
    isDeleted: false,
    deletedAt: null,
    deleteReason: null,
    syncedAt: 1000,
    lastMessageTimestamp: 1000,
    lastMessagePreview: "[message]",
    lastMessageEventId: "$hangup",
    lastMessageSenderId: "peer",
    lastMessageType: MessageType.system,
    lastMessageCallInfo: { callType: "voice", missed: false },
    lastMessageSystemMeta: { template: "system.voiceCall", senderAddr: "peer" },
    lastMessageReaction: { emoji: "👍", senderAddress: "peer", timestamp: 1100 },
    lastMessageDecryptionStatus: "failed",
  } as LocalRoom;
}

describe("RoomRepository.optimisticUpdateFromPush", () => {
  let db: TestDb;
  let repo: RoomRepository;

  beforeEach(async () => {
    db = new TestDb();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    repo = new RoomRepository(db as any);
    await db.rooms.put(callRoom());
  });

  afterEach(async () => {
    await db.delete();
  });

  it("drops what belonged to the previous last message", async () => {
    expect(await repo.optimisticUpdateFromPush(ROOM, "New message", 2000, "peer", undefined, "$new")).toBe(true);
    const room = (await db.rooms.get(ROOM))!;
    expect(room.lastMessagePreview).toBe("New message");
    expect(room.lastMessageEventId).toBe("$new");
    expect(room.unreadCount).toBe(1);
    expect(room.lastMessageReaction ?? null).toBeNull();
    expect(room.lastMessageCallInfo).toBeUndefined();
    expect(room.lastMessageSystemMeta).toBeUndefined();
    expect(room.lastMessageDecryptionStatus).toBeUndefined();
    expect(room.lastMessageType).toBe(MessageType.text);
  });

  it("keeps a message type the push knows", async () => {
    await repo.optimisticUpdateFromPush(ROOM, "📷 Photo", 2000, "peer", MessageType.image, "$new");
    expect((await db.rooms.get(ROOM))!.lastMessageType).toBe(MessageType.image);
  });

  it("still leaves a newer preview alone", async () => {
    expect(await repo.optimisticUpdateFromPush(ROOM, "New message", 500, "peer", undefined, "$older")).toBe(false);
    const room = (await db.rooms.get(ROOM))!;
    expect(room.lastMessageEventId).toBe("$hangup");
    expect(room.lastMessageReaction?.emoji).toBe("👍");
  });
});
