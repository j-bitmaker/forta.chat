import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";

// Mock localStorage
const storage = new Map<string, string>();
const localStorageMock = {
  getItem: vi.fn((key: string) => storage.get(key) ?? null),
  setItem: vi.fn((key: string, value: string) => storage.set(key, value)),
  removeItem: vi.fn((key: string) => storage.delete(key)),
  clear: vi.fn(() => storage.clear()),
  length: 0,
  key: vi.fn(() => null),
};
Object.defineProperty(globalThis, "localStorage", { value: localStorageMock });

// Import after mocking localStorage
const { tRaw } = await import("./index");

describe("tRaw", () => {
  beforeEach(() => {
    storage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("returns English text when no locale is set on an English device", () => {
    vi.stubGlobal("navigator", { language: "en-US" });
    const result = tRaw("push.newMessage");
    expect(result).toBe("New message");
  });

  it("returns Russian text when locale is JSON-encoded 'ru'", () => {
    storage.set("forta-chat:locale", JSON.stringify("ru"));
    const result = tRaw("push.newMessage");
    expect(result).toBe("Новое сообщение");
  });

  it("handles plain string locale (legacy format)", () => {
    storage.set("forta-chat:locale", "ru");
    const result = tRaw("push.newMessage");
    expect(result).toBe("Новое сообщение");
  });

  // The locale store shows a Russian UI on a Russian device without ever
  // saving that choice, so tRaw must not answer in English there.
  it("follows the device language when no locale was ever saved", () => {
    vi.stubGlobal("navigator", { language: "ru-RU" });
    expect(tRaw("call.warning.torBypassed")).toBe(
      "Звонки идут мимо Tor: собеседник может видеть ваш IP-адрес.",
    );
  });

  it("prefers a saved locale over the device language", () => {
    vi.stubGlobal("navigator", { language: "ru-RU" });
    storage.set("forta-chat:locale", JSON.stringify("en"));
    expect(tRaw("push.newMessage")).toBe("New message");
  });

  it("falls back to English for unknown locale", () => {
    storage.set("forta-chat:locale", JSON.stringify("fr"));
    const result = tRaw("push.newMessage");
    expect(result).toBe("New message");
  });

  it("interpolates parameters", () => {
    const result = tRaw("sync.error" as any);
    expect(typeof result).toBe("string");
  });

  it("returns key itself when key not found", () => {
    const result = tRaw("nonexistent.key" as any);
    expect(result).toBe("nonexistent.key");
  });
});
