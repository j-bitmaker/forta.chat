import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import { resolve } from "path";

/**
 * Regression: a reaction on a room's last message reached `chatStore.sortedRooms` but not the
 * chat list row. ContactList keeps its own per-room item cache and compared only timestamp,
 * status, content, sender and a few room fields, so a last message rewritten in place (a
 * reaction added, a hangup that turned missed) kept returning the old row. The list mounts
 * RecycleScroller and half the stores, so this checks the cache's source instead.
 */
const source = readFileSync(resolve(__dirname, "ContactList.vue"), "utf-8");

describe("ContactList row cache", () => {
  it("imports the last-message row key", () => {
    expect(source).toMatch(/import \{ lastMessageRowKey \} from "@\/features\/contacts\/lib\/last-message-row-key";/);
  });

  it("compares and stores the key for every room row", () => {
    const start = source.indexOf("const allFilteredRooms = computed");
    const end = source.indexOf("if (props.filter === \"personal\")", start);
    expect(start).toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    const toItem = source.slice(start, end);
    expect(toItem).toContain("const lastMessageKey = lastMessageRowKey(r);");
    expect(toItem).toContain("cached.lastMessageKey === lastMessageKey");
    expect(toItem).toMatch(/_unifiedItemCache\.set\(r\.id, \{[^}]*lastMessageKey[^}]*item \}\)/);
  });
});
