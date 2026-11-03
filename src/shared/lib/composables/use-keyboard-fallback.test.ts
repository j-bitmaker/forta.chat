// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h } from "vue";
import { mount } from "@vue/test-utils";

let mockAndroid = true;
vi.mock("@/shared/lib/platform", () => ({
  get isNative() {
    return true;
  },
  get isAndroid() {
    return mockAndroid;
  },
}));

const INNER_HEIGHT = 800;

/** A scroll panel holding a field whose bottom edge sits at `fieldBottom`. */
function panelWithField(fieldBottom: number, tag = "input") {
  const panel = document.createElement("div");
  panel.style.overflowY = "auto";
  Object.defineProperty(panel, "scrollHeight", { configurable: true, value: 2000 });
  Object.defineProperty(panel, "clientHeight", { configurable: true, value: 800 });
  const field = document.createElement(tag);
  field.getBoundingClientRect = () =>
    ({ top: fieldBottom - 40, bottom: fieldBottom, left: 0, right: 100, width: 100, height: 40 }) as DOMRect;
  panel.appendChild(field);
  document.body.appendChild(panel);
  return { panel, field };
}

function setInset(px: number) {
  document.documentElement.style.setProperty("--app-bottom-inset", `${px}px`);
}

beforeEach(() => {
  mockAndroid = true;
  document.body.innerHTML = "";
  document.documentElement.removeAttribute("style");
  Object.defineProperty(window, "innerHeight", { configurable: true, value: INNER_HEIGHT });
  vi.spyOn(window, "requestAnimationFrame").mockImplementation((cb) => {
    cb(0);
    return 0;
  });
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.resetModules();
});

describe("revealFocusedField", () => {
  it("scrolls the panel so a field the keyboard covers sits above it", async () => {
    const { revealFocusedField, FOCUS_MARGIN_PX } = await import("./use-keyboard-fallback");
    const { panel, field } = panelWithField(451);
    field.focus();
    setInset(354);

    expect(revealFocusedField()).toBe(true);

    // Keyboard top 800 - 354 = 446; the field must end FOCUS_MARGIN_PX above it.
    expect(panel.scrollTop).toBe(451 - (446 - FOCUS_MARGIN_PX));
  });

  it("leaves a field that is already above the keyboard alone", async () => {
    const { revealFocusedField } = await import("./use-keyboard-fallback");
    const { panel, field } = panelWithField(300);
    field.focus();
    setInset(354);

    expect(revealFocusedField()).toBe(false);
    expect(panel.scrollTop).toBe(0);
  });

  it("ignores focus on something that is not a text field", async () => {
    const { revealFocusedField } = await import("./use-keyboard-fallback");
    const { panel, field } = panelWithField(700, "button");
    field.focus();
    setInset(354);

    expect(revealFocusedField()).toBe(false);
    expect(panel.scrollTop).toBe(0);
  });
});

describe("useKeyboardFallback", () => {
  function mountFallback(useKeyboardFallback: () => void) {
    return mount(
      defineComponent({
        setup() {
          useKeyboardFallback();
          return () => h("div");
        },
      }),
      { attachTo: document.body },
    );
  }

  it("reveals the focused field when native code publishes the keyboard inset", async () => {
    const { useKeyboardFallback } = await import("./use-keyboard-fallback");
    const { panel, field } = panelWithField(451);
    field.focus();
    const wrapper = mountFallback(useKeyboardFallback);

    // MainActivity.injectAllCssVars writes the inset into the root style.
    setInset(354);
    await new Promise((r) => setTimeout(r, 0));

    expect(panel.scrollTop).toBeGreaterThan(0);
    wrapper.unmount();
  });

  it("reveals a field focused while the keyboard is already open", async () => {
    const { useKeyboardFallback } = await import("./use-keyboard-fallback");
    setInset(354);
    const wrapper = mountFallback(useKeyboardFallback);
    const { panel, field } = panelWithField(600);

    field.focus();

    expect(panel.scrollTop).toBeGreaterThan(0);
    wrapper.unmount();
  });

  it("does nothing off Android", async () => {
    mockAndroid = false;
    const { useKeyboardFallback } = await import("./use-keyboard-fallback");
    const { panel, field } = panelWithField(451);
    field.focus();
    const wrapper = mountFallback(useKeyboardFallback);

    setInset(354);
    await new Promise((r) => setTimeout(r, 0));

    expect(panel.scrollTop).toBe(0);
    wrapper.unmount();
  });
});
