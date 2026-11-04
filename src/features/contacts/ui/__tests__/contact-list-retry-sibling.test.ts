import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { en } from "@/shared/lib/i18n/locales/en";
import { ru } from "@/shared/lib/i18n/locales/ru";

const list = readFileSync(resolve(__dirname, "../ContactList.vue"), "utf-8");

/**
 * The «↻» that retries a failed room fetch used to sit inside the chat-row
 * `<button>`: a button inside a button is invalid HTML and unreachable for
 * keyboard and screen-reader users. It is now a sibling of the row inside a
 * `relative` wrapper. Source-level, matching the MessageBubble test style.
 */
describe("ContactList — room fetch retry is not nested in the row button", () => {
  const rowStart = list.indexOf("<!-- Chat room item.");
  const retryStart = list.indexOf("<!-- Fetch error — retry, outside the row button");
  const rowButton = list.slice(rowStart, retryStart);

  it("the chat row button contains no other button", () => {
    expect(rowStart).toBeGreaterThan(-1);
    expect(retryStart).toBeGreaterThan(rowStart);
    const openButtons = rowButton.match(/<button\b/g) ?? [];
    expect(openButtons).toHaveLength(1);
    expect(rowButton).not.toMatch(/retryRoomFetch/);
  });

  it("the retry button is a sibling of the row inside a relative wrapper and still retries", () => {
    expect(rowButton).toMatch(/<div v-else class="relative">\s*<button/);
    const retry = list.slice(retryStart, list.indexOf("</button>", retryStart));
    expect(retry).toMatch(/@click\.stop="chatStore\.retryRoomFetch\(\(item as ChatRoom\)\.id\)"/);
    expect(retry).toMatch(/:aria-label="t\('contactList\.retryLoad'\)"/);
    expect(retry).toMatch(/v-if="isRoomFetchError\(\(item as ChatRoom\)\.id\) && !getPreview\(item as ChatRoom\)\.text"/);
  });

  it("has the label in both locales", () => {
    expect(en["contactList.retryLoad"]).toBeTruthy();
    expect(ru["contactList.retryLoad"]).toBeTruthy();
  });
});
