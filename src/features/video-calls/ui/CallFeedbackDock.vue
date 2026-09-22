<script setup lang="ts">
import { computed, watch } from "vue";
import { isNative } from "@/shared/lib/platform";
import { useMobile } from "@/shared/lib/composables/use-media-query";
import {
  acquireCallFeedbackDock,
  useCallFeedbackPrompt,
} from "../model/use-call-feedback-prompt";
import CallFeedbackPrompt from "./CallFeedbackPrompt.vue";

/**
 * Desktop surface for the post-call card: a bar in the composer stack.
 *
 * The app-level overlay (`CallFeedbackHost`) centres itself on the viewport,
 * which on desktop means the sidebar drags it off-centre from the conversation
 * and it lands on top of the composer. Docking here keeps the card inside the
 * chat column and leaves the input reachable. Native shells and the mobile
 * layout keep the overlay — `isNative` guards them at any viewport, tablets
 * included.
 */
const { visible, durationS, dismiss } = useCallFeedbackPrompt();

const isMobileWidth = useMobile();
/** Matches the 768px threshold ChatPage uses to switch to the mobile layout. */
const canDock = computed(() => !isNative && !isMobileWidth.value);

// watch, not watchEffect: acquiring reads the dock counter it increments, and
// watchEffect would track that read and retrigger itself forever.
watch(
  canDock,
  (enabled, _was, onCleanup) => {
    if (!enabled) return;
    const release = acquireCallFeedbackDock();
    onCleanup(release);
  },
  { immediate: true },
);
</script>

<template>
  <div v-if="canDock && visible" class="w-full shrink-0 px-2 pb-2">
    <!-- max-w-6xl mirrors the composer's own row so the card lines up with it. -->
    <div class="mx-auto max-w-6xl">
      <CallFeedbackPrompt :duration-s="durationS" @dismiss="dismiss" />
    </div>
  </div>
</template>
