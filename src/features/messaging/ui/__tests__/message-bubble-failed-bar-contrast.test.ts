import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const bubble = readFileSync(resolve(__dirname, "../MessageBubble.vue"), "utf-8");

/**
 * «Нажмите для повтора» / «Отменить» on a failed own text message sit inside
 * the blue own bubble, where the hard-coded red (#FF4444) was unreadable in
 * both themes (manual web pass 2026-11-03). The bar now uses the on-accent
 * text token, and falls back to the error token only for jumbo emoji, which
 * have no bubble. Source-level, matching the MessageBubble test style.
 */
describe("MessageBubble — failed bar contrast on the own bubble", () => {
  const failedBar = bubble.match(/<!-- Failed text message: retry \/ cancel bar -->[\s\S]*?\n {8}<\/div>/)?.[0] ?? "";

  it("no longer hard-codes a red colour inside the own bubble", () => {
    expect(failedBar).not.toMatch(/#FF4444|text-red-|hover:bg-red-/i);
  });

  it("binds the bar text and button hover to theme-token classes", () => {
    expect(failedBar).toMatch(/data-testid="failed-message-bar"[\s\S]*?:class="failedBarTextClass"/);
    const hoverBindings = failedBar.match(/:class="failedBarHoverClass"/g) ?? [];
    expect(hoverBindings).toHaveLength(2);
  });

  it("uses the on-accent token on the bubble and the error token only without a bubble", () => {
    expect(bubble).toMatch(
      /const failedBarTextClass = computed\(\(\) =>\s*jumboEmojiCount\.value \? "text-color-bad" : "text-text-on-bg-ac-color",?\s*\)/,
    );
    expect(bubble).toMatch(
      /const failedBarHoverClass = computed\(\(\) =>\s*jumboEmojiCount\.value \? "hover:bg-color-bad\/10" : "hover:bg-text-on-bg-ac-color\/15",?\s*\)/,
    );
  });
});
