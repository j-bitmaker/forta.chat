import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";

/**
 * A call record reads as missed when the caller gave up before anyone answered, not only
 * when the invite timed out (`call-outcome.ts`). chat-store builds call records from
 * `m.call.hangup` in three places — the chat-list preview, history loads and live sync — and
 * each needs the room's other call events to decide. Live sync is covered by behaviour in
 * `chat-store.test.ts`; the preview and history builders are private, so this checks their
 * source, like `chat-store-hangup-unread-wiring.test.ts`.
 */
const source = readFileSync(resolve(__dirname, "../chat-store.ts"), "utf-8");

function bodyAfter(signature: RegExp): string {
  const match = signature.exec(source);
  if (!match) throw new Error(`${signature} not found in chat-store.ts`);
  const open = source.indexOf("{", match.index + match[0].length - 1);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}" && --depth === 0) return source.slice(open + 1, i);
  }
  throw new Error(`unbalanced body after ${signature}`);
}

describe("chat-store: missed call records", () => {
  it("no call record decides missed from the hangup reason alone", () => {
    expect(source).not.toMatch(/missed:\s*reason === "invite_timeout"/);
  });

  it("the chat-list preview reads a hangup against the room's call events", () => {
    const body = bodyAfter(/function matrixRoomToChatRoom\s*\([^)]*\)\s*:\s*ChatRoom\s*\{/);
    expect(body).toContain("indexCallEvents(");
    expect(body).toContain("isMissedCallHangup(");
  });

  it("a history load indexes the batch it parses and hands it to each event", () => {
    const batch = bodyAfter(/const parseTimelineEvents = async \([^)]*\)\s*:\s*Promise<Message\[\]>\s*=>\s*\{/);
    expect(batch).toContain("indexCallEvents(");
    expect(batch).toMatch(/parseSingleEvent\(event, roomId, roomCrypto, callEvents\)/);
    // parseSingleEvent has braces inside string literals, so take it up to the next declaration.
    const start = source.indexOf("const parseSingleEvent = async (");
    const end = source.indexOf("const parseTimelineEvents = async (");
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    const single = source.slice(start, end);
    expect(single).toContain("callEvents: CallEventIndex");
    expect(single).toContain("isMissedCallHangup(raw, callEvents)");
  });
});
