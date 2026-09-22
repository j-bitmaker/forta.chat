<script setup lang="ts">
import { watch } from "vue";
import { useCallStore } from "@/entities/call";
import {
  shouldPromptForFeedback,
  getLastFeedbackPromptAt,
  markFeedbackPromptShown,
} from "../model/call-feedback";
import { useCallFeedbackPrompt } from "../model/use-call-feedback-prompt";
import CallFeedbackPrompt from "./CallFeedbackPrompt.vue";

/**
 * Watches the call history and raises the post-call prompt. It lives here
 * rather than inside CallWindow because that window unmounts as soon as the
 * call clears — the moment we actually want to ask.
 *
 * Drawing it is a separate question: this overlay is the fallback surface —
 * native, the mobile layout, and desktop screens with no chat column. When a
 * dock is mounted (see CallFeedbackDock) the card is drawn inside the chat
 * column instead, where it cannot cover the composer.
 */
const callStore = useCallStore();
const { visible, durationS, isDocked, show, dismiss } = useCallFeedbackPrompt();

watch(
  () => callStore.history.length,
  (length, previous) => {
    if (length <= (previous ?? 0)) return;
    const entry = callStore.history[0];
    if (!entry) return;

    if (
      !shouldPromptForFeedback({
        status: entry.status,
        durationS: entry.duration,
        lastPromptAtMs: getLastFeedbackPromptAt(),
        nowMs: Date.now(),
      })
    ) {
      return;
    }

    show(entry.duration);
    markFeedbackPromptShown(Date.now());
  },
);
</script>

<template>
  <div
    v-if="visible && !isDocked"
    class="pointer-events-none fixed inset-x-0 bottom-0 z-40 flex justify-center p-4 pb-safe"
  >
    <div class="w-full max-w-sm">
      <CallFeedbackPrompt :duration-s="durationS" @dismiss="dismiss" />
    </div>
  </div>
</template>
