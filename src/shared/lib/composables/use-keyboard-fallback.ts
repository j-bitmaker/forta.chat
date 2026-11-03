import { onScopeDispose } from "vue";
import { isAndroid, isNative } from "@/shared/lib/platform";

/** Space kept between a focused field and the top of the keyboard. */
export const FOCUS_MARGIN_PX = 12;

const TEXT_FIELD_TAGS = new Set(["INPUT", "TEXTAREA", "SELECT"]);

function scrollableAncestor(el: HTMLElement): HTMLElement | null {
  let node = el.parentElement;
  while (node) {
    const overflowY = getComputedStyle(node).overflowY;
    if ((overflowY === "auto" || overflowY === "scroll") && node.scrollHeight > node.clientHeight) return node;
    node = node.parentElement;
  }
  return null;
}

/**
 * Scrolls the panel of the focused text field so the field ends above the
 * keyboard. The WebView does not shrink for the keyboard (MainActivity runs
 * edge-to-edge and consumes the insets), so the browser never scrolls the
 * field into view by itself: a field in the lower half of a form, such as
 * «Сайт» in the profile, stayed under the keyboard. The panel can scroll that
 * far because `.safe-bottom` pads it by `--app-bottom-inset`.
 *
 * Returns true when it scrolled.
 */
export function revealFocusedField(): boolean {
  const el = document.activeElement as HTMLElement | null;
  if (!el || !(TEXT_FIELD_TAGS.has(el.tagName) || el.isContentEditable)) return false;
  const inset =
    parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--app-bottom-inset")) || 0;
  const limit = window.innerHeight - inset - FOCUS_MARGIN_PX;
  const overlap = el.getBoundingClientRect().bottom - limit;
  if (overlap <= 0) return false;
  const panel = scrollableAncestor(el);
  if (!panel) return false;
  panel.scrollTop += overlap;
  return true;
}

/**
 * Android keyboard handling for Capacitor WebView.
 *
 * Root app-shell uses `position: fixed; inset: 0` — document scroll does NOT
 * move it. The WebView keeps its full height while the keyboard is open:
 * native code publishes the keyboard as `--app-bottom-inset`, the blocks with
 * a text field pad themselves by it, and {@link revealFocusedField} scrolls the
 * focused field above the keyboard whenever the inset changes or focus moves.
 *
 * It also cross-checks the inset against `visualViewport` (Chrome 61+, covers
 * WebView 114+) to catch OEM firmware anomalies where `WindowInsetsCompat`
 * reports incorrect IME heights.
 *
 * On iOS we drive --app-bottom-inset directly from @capacitor/keyboard
 * events via `useIOSKeyboardCssVar` — that path is authoritative, so
 * this fallback must not run there.
 *
 * Call once in the root component (App.vue) `onMounted`.
 */
export function useKeyboardFallback(): void {
  if (!isNative || !isAndroid) return;

  // After layout, so the panel's new bottom padding already exists.
  const scheduleReveal = () => requestAnimationFrame(() => revealFocusedField());
  // MainActivity.injectAllCssVars writes the inset into the root element's style.
  const insetObserver = new MutationObserver(scheduleReveal);
  insetObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["style"] });
  document.addEventListener("focusin", scheduleReveal);
  onScopeDispose(() => {
    insetObserver.disconnect();
    document.removeEventListener("focusin", scheduleReveal);
  });

  const vv = window.visualViewport;
  if (!vv) return;

  const onVVResize = () => {
    const screenHeight = window.screen.height;
    const dpr = window.devicePixelRatio || 1;
    const screenCssPx = screenHeight / dpr;
    const kbdFromVV = Math.round(screenCssPx - vv.height - vv.offsetTop);

    if (kbdFromVV <= 0) return;

    const nativeInset = parseInt(
      getComputedStyle(document.documentElement).getPropertyValue(
        "--app-bottom-inset",
      ) || "0",
      10,
    );

    if (Math.abs(nativeInset - kbdFromVV) > 30) {
      document.documentElement.style.setProperty(
        "--app-bottom-inset",
        `${kbdFromVV}px`,
      );
    }
  };

  vv.addEventListener("resize", onVVResize);
  onScopeDispose(() => vv.removeEventListener("resize", onVVResize));
}
