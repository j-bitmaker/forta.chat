import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { en } from "./locales/en";
import { ru } from "./locales/ru";

/**
 * Icon-only buttons found without an accessible name on the manual web pass
 * of 2026-11-03: the section-header back arrow in settings, the call-screen
 * buttons and the icon buttons around the chat composer. Each now carries an
 * `aria-label` bound through i18n; this pins the bindings and that every key
 * they use exists in both locales. Source-level, like the MessageBubble tests.
 */
const read = (rel: string) => readFileSync(resolve(__dirname, "../../../", rel), "utf-8");

const ariaKeys = (source: string): string[] =>
  [...source.matchAll(/:aria-label="([^"]+)"/g)]
    .flatMap((m) => [...m[1].matchAll(/t\('([^']+)'\)/g)].map((k) => k[1]));

const settingsPanel = read("widgets/sidebar/ui/SettingsContentPanel.vue");
const callControls = read("features/video-calls/ui/CallControls.vue");
const callWindow = read("features/video-calls/ui/CallWindow.vue");
const messageInput = read("features/messaging/ui/MessageInput.vue");

describe("icon-only buttons have an i18n aria-label", () => {
  it("settings section header: back arrow", () => {
    const header = settingsPanel.match(/<!-- Header with back button -->[\s\S]*?<\/button>/)?.[0] ?? "";
    expect(header).toMatch(/:aria-label="t\('nav\.back'\)"/);
  });

  it("call controls: every button's aria-label mirrors its title", () => {
    const row = callControls.slice(callControls.indexOf("Buttons row"));
    const buttons = row.match(/<button[\s\S]*?>/g) ?? [];
    expect(buttons.length).toBe(6);
    for (const tag of buttons) {
      const title = tag.match(/:title="([^"]+)"/)?.[1];
      const label = tag.match(/:aria-label="([^"]+)"/)?.[1];
      expect(title, tag).toBeTruthy();
      expect(label, tag).toBe(title);
    }
  });

  it("call window: minimize button", () => {
    const minimize = callWindow.match(/<!-- Minimize button[\s\S]*?<\/button>/)?.[0] ?? "";
    expect(minimize).toMatch(/:aria-label="t\('call\.minimize'\)"/);
  });

  it("composer idle row: emoji, PKOIN, attach and record buttons", () => {
    const idle = messageInput.slice(
      messageInput.indexOf("IDLE state: normal input"),
      messageInput.indexOf("RECORDING state"),
    );
    expect(idle).toMatch(/:aria-label="t\('message\.emoji'\)"/);
    expect(idle).toMatch(/:aria-label="t\('wallet\.sendPkoin'\)"/);
    expect(idle).toMatch(/:aria-label="t\('message\.attach'\)"/);
    expect(idle).toMatch(/:aria-label="isVideoMode \? t\('message\.recordVideo'\) : t\('message\.recordVoice'\)"/);
  });

  it("composer: no icon button is left with a hard-coded title", () => {
    expect(messageInput).not.toMatch(/\btitle="Send"/);
  });

  it("every aria-label key exists in en and ru", () => {
    const keys = new Set([
      ...ariaKeys(settingsPanel),
      ...ariaKeys(callControls),
      ...ariaKeys(callWindow),
      ...ariaKeys(messageInput),
    ]);
    expect(keys.size).toBeGreaterThan(10);
    for (const key of keys) {
      expect(en[key as keyof typeof en], `en ${key}`).toBeTruthy();
      expect(ru[key as keyof typeof ru], `ru ${key}`).toBeTruthy();
    }
  });
});
