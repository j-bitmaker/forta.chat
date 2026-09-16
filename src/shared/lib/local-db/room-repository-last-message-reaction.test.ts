import { describe, it, expect, beforeEach, afterEach } from "vitest";
import Dexie from "dexie";
import "fake-indexeddb/auto";
import { RoomRepository } from "./room-repository";
import type { LocalRoom } from "./schema";
import { MessageType } from "@/entities/chat/model/types";

/**
 * Regression: opening a chat re-writes its last message's preview (history load → EventWriter →
 * updateLastMessage with the same event id), and every such write cleared lastMessageReaction.
 * The chat list row lost its 👍 as soon as the chat was opened (Samsung, `react-pre2`/`react-pre3`).
 */
class TestDb extends Dexie {
  rooms!: import("dexie").Table<LocalRoom, string>;
  constructor() {
    super("test-room-repo-last-message-reaction", { indexedDB, IDBKeyRange });
    this.version(1).stores({ rooms: "id, membership, updatedAt, isDeleted" });
  }
}

const ROOM = "!peer:s";
const LIKE = { emoji: "👍", senderAddress: "peer", timestamp: 1100 };

function reactedRoom(): LocalRoom {
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
    lastMessagePreview: "hi",
    lastMessageEventId: "$hi",
    lastMessageSenderId: "me",
    lastMessageType: MessageType.text,
    lastMessageReaction: LIKE,
  } as LocalRoom;
}

describe("RoomRepository.updateLastMessage and the last message's reaction", () => {
  let db: TestDb;
  let repo: RoomRepository;

  beforeEach(async () => {
    db = new TestDb();
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    repo = new RoomRepository(db as any);
    await db.rooms.put(reactedRoom());
  });

  afterEach(async () => {
    await db.delete();
  });

  it("keeps the reaction when the same last message is written again", async () => {
    await repo.updateLastMessage(ROOM, "hi", 1000, "me", MessageType.text, "$hi");
    expect((await db.rooms.get(ROOM))!.lastMessageReaction).toEqual(LIKE);
  });

  it("clears the reaction when a new last message replaces it", async () => {
    await repo.updateLastMessage(ROOM, "next", 2000, "peer", MessageType.text, "$next");
    const room = (await db.rooms.get(ROOM))!;
    expect(room.lastMessageEventId).toBe("$next");
    expect(room.lastMessageReaction ?? null).toBeNull();
  });

  it("clears the reaction when the write carries no event id", async () => {
    await repo.updateLastMessage(ROOM, "next", 2000, "peer", MessageType.text);
    expect((await db.rooms.get(ROOM))!.lastMessageReaction ?? null).toBeNull();
  });
});
