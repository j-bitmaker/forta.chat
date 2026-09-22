import { computed, ref } from "vue";

/**
 * Shared state for the post-call feedback card.
 *
 * The card is raised app-wide — `CallFeedbackHost` watches the call history and
 * a call can end on any screen — but it is drawn by whichever surface is
 * available: docked into the composer stack inside the chat column on desktop,
 * or as a bottom overlay everywhere else. Keeping the state at module scope
 * (the `use-toast` approach) lets both surfaces read the same card without the
 * host owning the chat column's lifecycle.
 */
const visible = ref(false);
const durationS = ref(0);

/**
 * How many docks can currently draw the card. A count rather than a flag:
 * while Vue swaps the chat column the outgoing dock unmounts after the incoming
 * one mounts, and a flag would let the overlay flash in between.
 */
const dockCount = ref(0);

const isDocked = computed(() => dockCount.value > 0);

function show(callDurationS: number): void {
  durationS.value = callDurationS;
  visible.value = true;
}

function dismiss(): void {
  visible.value = false;
}

/**
 * Registers a dock as able to draw the card. Call the returned release once it
 * can no longer draw — on unmount, or when the viewport drops to the mobile
 * layout. Releasing twice is a no-op.
 */
export function acquireCallFeedbackDock(): () => void {
  dockCount.value += 1;
  let released = false;

  return () => {
    if (released) return;
    released = true;
    dockCount.value = Math.max(0, dockCount.value - 1);
  };
}

/** Drops the card and every dock registration. For test teardown. */
export function __resetCallFeedbackPromptForTests(): void {
  visible.value = false;
  durationS.value = 0;
  dockCount.value = 0;
}

export function useCallFeedbackPrompt() {
  return { visible, durationS, isDocked, show, dismiss };
}
