import { describe, it, expect, vi, beforeEach } from "vitest";
import { ref } from "vue";
import { mount } from "@vue/test-utils";
import { setActivePinia, createPinia } from "pinia";

/**
 * O10 — the notification settings page tells the user when Android has
 * revoked the full-screen incoming-call surface, and offers the system
 * screen that grants it back.
 */

const state = {
  fullScreenIntentAllowed: ref<boolean | null>(null),
  openFullScreenIntentSettings: vi.fn().mockResolvedValue(true),
};
vi.mock("../../model/use-notification-settings", () => ({
  useNotificationSettings: () => ({
    canOpenSystemSettings: ref(true),
    vendorGuidanceId: ref(null),
    openSystemNotificationSettings: vi.fn(),
    detectVendor: vi.fn(),
    fullScreenIntentAllowed: state.fullScreenIntentAllowed,
    detectFullScreenIntent: vi.fn(),
    openFullScreenIntentSettings: state.openFullScreenIntentSettings,
  }),
}));
vi.mock("@/shared/lib/platform", () => ({
  isNative: true,
  isAndroid: true,
  isIOS: false,
  isElectron: false,
  isWeb: false,
  hasTor: false,
  isAndroidWeb: false,
  currentPlatform: "android",
  getElectronAPI: () => undefined,
  resolveAppUpdaterEnabled: () => false,
}));
vi.mock("@/shared/ui/settings-section", () => ({
  SettingsSection: { template: "<section><slot /></section>" },
}));

describe("NotificationSettings — full-screen intent banner (O10)", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    state.fullScreenIntentAllowed.value = null;
    state.openFullScreenIntentSettings.mockClear();
    vi.stubGlobal("useI18n", () => ({ t: (k: string) => k }));
  });

  it("shows the banner only when the intent is known to be revoked", async () => {
    const { default: NotificationSettings } = await import("../NotificationSettings.vue");
    const wrapper = mount(NotificationSettings);
    expect(wrapper.find('[data-testid="fsi-banner"]').exists()).toBe(false);

    state.fullScreenIntentAllowed.value = false;
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="fsi-banner"]').exists()).toBe(true);

    state.fullScreenIntentAllowed.value = true;
    await wrapper.vm.$nextTick();
    expect(wrapper.find('[data-testid="fsi-banner"]').exists()).toBe(false);
  });

  it("opens the system screen from the banner", async () => {
    state.fullScreenIntentAllowed.value = false;
    const { default: NotificationSettings } = await import("../NotificationSettings.vue");
    const wrapper = mount(NotificationSettings);
    await wrapper.find('[data-testid="fsi-open"]').trigger("click");
    expect(state.openFullScreenIntentSettings).toHaveBeenCalledTimes(1);
  });
});
