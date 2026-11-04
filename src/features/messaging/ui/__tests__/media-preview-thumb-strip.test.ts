// @vitest-environment happy-dom
import { describe, it, expect, afterEach, vi } from "vitest";
import { mount, enableAutoUnmount } from "@vue/test-utils";
import type { MediaFile } from "../../model/use-media-upload";

vi.mock("@/shared/lib/i18n", () => ({
  useI18n: () => ({ t: (k: string) => k }),
}));

import MediaPreview from "../MediaPreview.vue";

enableAutoUnmount(afterEach);

/**
 * The thumbnail strip used to nest the per-file remove `<button>` inside the
 * thumbnail `<button>` (manual web pass 2026-11-03). Interactive content inside
 * a button is invalid HTML and breaks keyboard / screen-reader access to the
 * inner control. The two are now siblings with the same behaviour.
 */
const file = (type: MediaFile["type"], i: number): MediaFile => ({
  file: new File(["x"], `f${i}`),
  previewUrl: `blob:${type}-${i}`,
  type,
});

const mountPreview = () =>
  mount(MediaPreview, {
    attachTo: document.body,
    props: {
      show: true,
      files: [file("image", 0), file("video", 1)],
      activeIndex: 0,
      caption: "",
      captionAbove: false,
      sending: false,
    },
  });

describe("MediaPreview — thumbnail strip", () => {
  it("renders no interactive element inside a button", () => {
    mountPreview();
    expect(document.body.querySelectorAll("button button, button a, button input")).toHaveLength(0);
    expect(document.body.querySelectorAll('[data-testid="media-preview-thumb"]')).toHaveLength(2);
    expect(document.body.querySelectorAll('[data-testid="media-preview-remove"]')).toHaveLength(2);
  });

  it("selecting a thumbnail and removing a file still emit their events", async () => {
    const w = mountPreview();
    const thumbs = document.body.querySelectorAll<HTMLButtonElement>('[data-testid="media-preview-thumb"]');
    const removes = document.body.querySelectorAll<HTMLButtonElement>('[data-testid="media-preview-remove"]');

    thumbs[1].click();
    await w.vm.$nextTick();
    expect(w.emitted("update:activeIndex")).toEqual([[1]]);

    removes[1].click();
    await w.vm.$nextTick();
    expect(w.emitted("removeFile")).toEqual([[1]]);
    // The remove click must not also select the thumbnail.
    expect(w.emitted("update:activeIndex")).toEqual([[1]]);
  });

  it("keeps the active / inactive look on the thumbnail and its wrapper", () => {
    mountPreview();
    const thumbs = document.body.querySelectorAll<HTMLButtonElement>('[data-testid="media-preview-thumb"]');
    expect(thumbs[0].className).toContain("border-white");
    expect(thumbs[1].className).toContain("border-transparent");
    expect(thumbs[0].parentElement!.className).not.toContain("opacity-60");
    expect(thumbs[1].parentElement!.className).toContain("opacity-60");
  });
});
