import { describe, it, expect, beforeEach, afterEach } from "vitest";
import "fake-indexeddb/auto";
import { ChatDatabase, type LocalMessage } from "../schema";
import { MessageRepository } from "../message-repository";
import { MessageType } from "@/entities/chat/model/types";

/**
 * A call the caller cancelled reads as missed only when its invite is in view
 * (`entities/chat/lib/call-outcome.ts`). Live sync keeps 4 events per room, so a
 * hangup that arrives after a gap is first stored as not missed; the history load
 * that later sees the invite writes the same event again. Duplicate events were
 * skipped, so that first reading stuck. A later "missed" now repairs the row; a later
 * "not missed" never undoes one, since a write that sees the invite sees every
 * answer after it too.
 */

const ROOM = "!room:server";

let db: ChatDatabase;
let repo: MessageRepository;

function callRow(missed: boolean, overrides: Partial<LocalMessage> = {}): LocalMessage {
  return {
    eventId: "$hangup",
    clientId: "srv_$hangup",
    roomId: ROOM,
    senderId: "peer",
    content: "",
    timestamp: 1000,
    type: MessageType.system,
    status: "synced",
    version: 1,
    softDeleted: false,
    callInfo: { callType: "voice", missed, duration: 0, callId: "call-1" },
    systemMeta: { template: missed ? "system.missedVoiceCall" : "system.voiceCall", senderAddr: "peer" },
    ...overrides,
  };
}

async function row(): Promise<LocalMessage | undefined> {
  return db.messages.where("eventId").equals("$hangup").first();
}

beforeEach(async () => {
  db = new ChatDatabase(`test-missed-call-repair-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  await db.open();
  repo = new MessageRepository(db);
});

afterEach(async () => {
  await db.delete();
});

describe("MessageRepository: a call record learns it was missed", () => {
  it("a later live write marks a stored call record missed", async () => {
    await db.messages.add(callRow(false));

    expect(await repo.upsertFromServer(callRow(true))).toBe("updated");

    const r = await row();
    expect(r?.callInfo?.missed).toBe(true);
    expect(r?.callInfo?.callId).toBe("call-1");
    expect(r?.systemMeta?.template).toBe("system.missedVoiceCall");
  });

  it("a write keyed by event id alone repairs it too", async () => {
    await db.messages.add(callRow(false, { clientId: undefined }));

    expect(await repo.upsertFromServer(callRow(true, { clientId: undefined }))).toBe("updated");

    expect((await row())?.callInfo?.missed).toBe(true);
  });

  it("a history load marks a stored call record missed", async () => {
    await db.messages.add(callRow(false));

    await repo.bulkInsert([callRow(true)]);

    const rows = await db.messages.where("eventId").equals("$hangup").toArray();
    expect(rows).toHaveLength(1);
    expect(rows[0].callInfo?.missed).toBe(true);
    expect(rows[0].systemMeta?.template).toBe("system.missedVoiceCall");
  });

  it("never turns a missed call record back into an answered one", async () => {
    await db.messages.add(callRow(true));

    await repo.upsertFromServer(callRow(false));
    await repo.bulkInsert([callRow(false)]);

    const r = await row();
    expect(r?.callInfo?.missed).toBe(true);
    expect(r?.systemMeta?.template).toBe("system.missedVoiceCall");
  });

  it("leaves rows that are not call records alone", async () => {
    await db.messages.add(callRow(false, { callInfo: undefined, content: "hello", type: MessageType.text }));

    expect(await repo.upsertFromServer(callRow(true, { clientId: undefined }))).toBe("duplicate");

    const r = await row();
    expect(r?.callInfo).toBeUndefined();
    expect(r?.content).toBe("hello");
  });
});
