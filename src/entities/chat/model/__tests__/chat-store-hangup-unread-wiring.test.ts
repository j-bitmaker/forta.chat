import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";

/**
 * Every place that turns the server's unread count into the chat-list badge takes
 * the peer's call hangups back out (see `call-hangup-unread.ts`). The count reaches
 * Dexie through `matrixRoomToChatRoom` and through `syncAllUnreadFromMatrix`, and the
 * SDK overwrites its own copy on every sync — so the subtraction has to happen where
 * the count is read, in both places.
 *
 * Source-level, like `members-membership-split.test.ts`: `matrixRoomToChatRoom` is
 * not exported, and the rules live in the pure helpers' own tests.
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

describe("chat-store: unread counts leave out peer call hangups", () => {
  it("matrixRoomToChatRoom reads its count through roomUnreadCount", () => {
    const body = bodyAfter(/function matrixRoomToChatRoom\s*\([^)]*\)\s*:\s*ChatRoom\s*\{/);
    expect(body).toContain("roomUnreadCount(room, myUserId)");
    expect(body).not.toContain("getUnreadNotificationCount");
  });

  it("syncAllUnreadFromMatrix reads its count through roomUnreadCount", () => {
    const body = bodyAfter(/const syncAllUnreadFromMatrix = async \(\) => \{/);
    expect(body).toContain("roomUnreadCount(mxRoom, myUserId)");
    expect(body).not.toContain("getUnreadNotificationCount");
  });

  it("roomUnreadCount takes hangups out only while the rule is known", () => {
    const body = bodyAfter(/function roomUnreadCount\s*\([^)]*\)\s*:\s*number\s*\{/);
    expect(body).toContain('getUnreadNotificationCount?.("total")');
    expect(body).toContain("callHangupRuleSince(");
    expect(body).toContain("unreadPeerHangupCount(");
    expect(body).toContain("unreadCountWithoutHangups(");
  });
});
