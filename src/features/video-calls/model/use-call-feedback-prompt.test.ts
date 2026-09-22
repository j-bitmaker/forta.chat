import { describe, it, expect, beforeEach } from "vitest";
import {
  acquireCallFeedbackDock,
  useCallFeedbackPrompt,
  __resetCallFeedbackPromptForTests,
} from "./use-call-feedback-prompt";

beforeEach(() => {
  __resetCallFeedbackPromptForTests();
});

describe("call feedback prompt state", () => {
  it("carries the call duration into the visible card", () => {
    const { visible, durationS, show } = useCallFeedbackPrompt();

    expect(visible.value).toBe(false);
    show(7.4);

    expect(visible.value).toBe(true);
    expect(durationS.value).toBe(7.4);
  });

  it("hides the card on dismiss", () => {
    const { visible, show, dismiss } = useCallFeedbackPrompt();
    show(30);

    dismiss();

    expect(visible.value).toBe(false);
  });

  it("shares one card between every caller", () => {
    const raiser = useCallFeedbackPrompt();
    const reader = useCallFeedbackPrompt();

    raiser.show(12);

    expect(reader.visible.value).toBe(true);
    expect(reader.durationS.value).toBe(12);
  });
});

describe("dock registration", () => {
  it("reports a dock while one is acquired", () => {
    const { isDocked } = useCallFeedbackPrompt();
    expect(isDocked.value).toBe(false);

    const release = acquireCallFeedbackDock();
    expect(isDocked.value).toBe(true);

    release();
    expect(isDocked.value).toBe(false);
  });

  it("keeps the dock while a second one overlaps", () => {
    // Vue mounts the incoming chat column before unmounting the outgoing one;
    // a boolean flag would drop the dock mid-swap and flash the overlay.
    const { isDocked } = useCallFeedbackPrompt();
    const first = acquireCallFeedbackDock();
    const second = acquireCallFeedbackDock();

    first();
    expect(isDocked.value).toBe(true);

    second();
    expect(isDocked.value).toBe(false);
  });

  it("ignores a release called twice", () => {
    const { isDocked } = useCallFeedbackPrompt();
    const release = acquireCallFeedbackDock();
    const other = acquireCallFeedbackDock();

    release();
    release();

    expect(isDocked.value).toBe(true);
    other();
    expect(isDocked.value).toBe(false);
  });
});
