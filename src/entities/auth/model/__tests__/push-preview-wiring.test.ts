import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";

/**
 * Regression: the push for a message /sync had already delivered wrote its "New message"
 * placeholder and counted the message a second time (Samsung, `order2`: sync at +0 ms, push
 * write at +78 ms). The updater is wired inside `initMatrix`, which needs the SDK, Pcrypto and
 * Dexie, so the wiring is checked in source; `chatStore.hasMessage` is covered by behaviour in
 * `chat-store-inbound-row-order.test.ts`.
 */
const source = readFileSync(resolve(__dirname, "../stores.ts"), "utf-8");

describe("auth store: push room preview", () => {
  it("skips the placeholder for an event the chat store already has", () => {
    const start = source.indexOf("pushService.setOptimisticRoomUpdater(");
    expect(start).toBeGreaterThan(-1);
    const end = source.indexOf("pushService.setRoomInfoGetter(", start);
    expect(end).toBeGreaterThan(start);
    const wiring = source.slice(start, end);
    expect(wiring).toMatch(/eventId && chatStore\.hasMessage\(roomId, eventId\)\s*\?\s*Promise\.resolve\(false\)\s*:\s*chatDbKit\.rooms\.optimisticUpdateFromPush\(/);
  });
});
