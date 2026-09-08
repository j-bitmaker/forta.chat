import { describe, it, expect, vi, beforeEach, type Mock } from "vitest";

// ---------------------------------------------------------------------------
// Mocks
// ---------------------------------------------------------------------------

const mockIsNative = { value: true };
const mockIsAndroid = { value: true };
vi.mock("@/shared/lib/platform", () => ({
  get isNative() { return mockIsNative.value; },
  get isAndroid() { return mockIsAndroid.value; },
  isIOS: false,
  isElectron: false,
  isWeb: false,
  currentPlatform: "android",
}));

const mockGetDeviceManufacturer: Mock = vi.fn();
const mockOpenNotificationSettings: Mock = vi.fn();
const mockGetFullScreenIntentStatus: Mock = vi.fn();
const mockOpenFullScreenIntentSettings: Mock = vi.fn();
vi.mock("@/shared/lib/push/push-data-plugin", () => ({
  PushData: {
    getDeviceManufacturer: (...args: unknown[]) => mockGetDeviceManufacturer(...args),
    openNotificationSettings: (...args: unknown[]) => mockOpenNotificationSettings(...args),
    getFullScreenIntentStatus: (...args: unknown[]) => mockGetFullScreenIntentStatus(...args),
    openFullScreenIntentSettings: (...args: unknown[]) => mockOpenFullScreenIntentSettings(...args),
  },
}));

import { useNotificationSettings } from "./use-notification-settings";

describe("useNotificationSettings", () => {
  beforeEach(() => {
    mockIsNative.value = true;
    mockIsAndroid.value = true;
    mockGetDeviceManufacturer.mockReset();
    mockOpenNotificationSettings.mockReset();
    mockOpenNotificationSettings.mockResolvedValue(undefined);
    mockGetFullScreenIntentStatus.mockReset();
    mockOpenFullScreenIntentSettings.mockReset();
    mockOpenFullScreenIntentSettings.mockResolvedValue(undefined);
  });

  it("exposes a system-settings deep-link on native Android", () => {
    const { canOpenSystemSettings } = useNotificationSettings();
    expect(canOpenSystemSettings.value).toBe(true);
  });

  it("hides the deep-link off native (web)", () => {
    mockIsNative.value = false;
    const { canOpenSystemSettings } = useNotificationSettings();
    expect(canOpenSystemSettings.value).toBe(false);
  });

  it("opens the OS notification settings and reports success", async () => {
    const { openSystemNotificationSettings } = useNotificationSettings();
    const ok = await openSystemNotificationSettings();
    expect(ok).toBe(true);
    expect(mockOpenNotificationSettings).toHaveBeenCalledTimes(1);
  });

  it("does not call native when the platform can't open settings", async () => {
    mockIsNative.value = false;
    const { openSystemNotificationSettings } = useNotificationSettings();
    const ok = await openSystemNotificationSettings();
    expect(ok).toBe(false);
    expect(mockOpenNotificationSettings).not.toHaveBeenCalled();
  });

  it("returns false (no throw) when the native call rejects", async () => {
    mockOpenNotificationSettings.mockRejectedValue(new Error("no settings activity"));
    const { openSystemNotificationSettings } = useNotificationSettings();
    await expect(openSystemNotificationSettings()).resolves.toBe(false);
  });

  it("flags aggressive-OEM guidance for Xiaomi/MIUI (regression for #942)", async () => {
    mockGetDeviceManufacturer.mockResolvedValue({ manufacturer: "Xiaomi", model: "23122PCD1G", sdk: 34 });
    const { vendorGuidanceId, detectVendor } = useNotificationSettings();
    await detectVendor();
    expect(vendorGuidanceId.value).toBe("xiaomi");
  });

  it("maps Redmi/Poco sub-brands onto the xiaomi guidance", async () => {
    mockGetDeviceManufacturer.mockResolvedValue({ manufacturer: "Redmi", model: "x", sdk: 33 });
    const { vendorGuidanceId, detectVendor } = useNotificationSettings();
    await detectVendor();
    expect(vendorGuidanceId.value).toBe("xiaomi");
  });

  it("shows no vendor guidance for non-aggressive manufacturers", async () => {
    mockGetDeviceManufacturer.mockResolvedValue({ manufacturer: "Google", model: "Pixel 8", sdk: 34 });
    const { vendorGuidanceId, detectVendor } = useNotificationSettings();
    await detectVendor();
    expect(vendorGuidanceId.value).toBeNull();
  });

  it("swallows vendor-detection failures and shows no guidance", async () => {
    mockGetDeviceManufacturer.mockRejectedValue(new Error("plugin missing"));
    const { vendorGuidanceId, detectVendor } = useNotificationSettings();
    await detectVendor();
    expect(vendorGuidanceId.value).toBeNull();
  });

  it("skips vendor detection entirely off native", async () => {
    mockIsNative.value = false;
    const { detectVendor } = useNotificationSettings();
    await detectVendor();
    expect(mockGetDeviceManufacturer).not.toHaveBeenCalled();
  });
});

describe("useNotificationSettings — full-screen intent (O10)", () => {
  beforeEach(() => {
    mockIsNative.value = true;
    mockIsAndroid.value = true;
    mockGetFullScreenIntentStatus.mockReset();
    mockOpenFullScreenIntentSettings.mockReset();
    mockOpenFullScreenIntentSettings.mockResolvedValue(undefined);
  });

  it("flags a revoked full-screen intent so the banner can show", async () => {
    mockGetFullScreenIntentStatus.mockResolvedValue({ allowed: false, manageable: true });
    const { fullScreenIntentAllowed, detectFullScreenIntent } = useNotificationSettings();
    expect(fullScreenIntentAllowed.value).toBeNull();
    await detectFullScreenIntent();
    expect(fullScreenIntentAllowed.value).toBe(false);
  });

  it("stays null before Android 14, off Android, and when the query rejects", async () => {
    mockGetFullScreenIntentStatus.mockResolvedValue({ allowed: true, manageable: false });
    const a = useNotificationSettings();
    await a.detectFullScreenIntent();
    expect(a.fullScreenIntentAllowed.value).toBeNull();

    mockGetFullScreenIntentStatus.mockRejectedValue(new Error("not implemented"));
    const b = useNotificationSettings();
    await b.detectFullScreenIntent();
    expect(b.fullScreenIntentAllowed.value).toBeNull();

    mockIsAndroid.value = false;
    mockGetFullScreenIntentStatus.mockClear();
    const c = useNotificationSettings();
    await c.detectFullScreenIntent();
    expect(mockGetFullScreenIntentStatus).not.toHaveBeenCalled();
  });

  it("opens the system screen and reports failure without throwing", async () => {
    const { openFullScreenIntentSettings } = useNotificationSettings();
    await expect(openFullScreenIntentSettings()).resolves.toBe(true);
    mockOpenFullScreenIntentSettings.mockRejectedValue(new Error("unsupported"));
    await expect(openFullScreenIntentSettings()).resolves.toBe(false);
  });
});
