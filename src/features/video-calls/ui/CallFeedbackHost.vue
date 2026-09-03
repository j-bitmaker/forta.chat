<script setup lang="ts">
import { ref, watch } from "vue";
import { useCallStore } from "@/entities/call";
import {
  shouldPromptForFeedback,
  getLastFeedbackPromptAt,
  markFeedbackPromptShown,
} from "../model/call-feedback";
import CallFeedbackPrompt from "./CallFeedbackPrompt.vue";

/**
 * Watches the call history and shows the post-call prompt. It lives here
 * rather than inside CallWindow because that window unmounts as soon as the
 * call clears — the moment we actually want to ask.
 */
const callStore = useCallStore();

const visible = ref(false);
const durationS = ref(0);

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

    durationS.value = entry.duration;
    visible.value = true;
    markFeedbackPromptShown(Date.now());
  },
);
</script>

<template>
  <div
    v-if="visible"
    class="pointer-events-none fixed inset-x-0 bottom-0 z-40 flex justify-center p-4 pb-safe"
  >
    <div class="w-full max-w-sm">
      <CallFeedbackPrompt :duration-s="durationS" @dismiss="visible = false" />
    </div>
  </div>
</template>
