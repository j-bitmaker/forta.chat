<script setup lang="ts">
import { ref } from "vue";
import { useBugReport } from "@/features/bug-report";
import {
  CALL_PROBLEMS,
  buildFeedbackDescription,
  type CallProblem,
} from "../model/call-feedback";

/**
 * Asked once a call ends (see `shouldPromptForFeedback`). A thumbs-down opens
 * the existing bug-report form pre-filled with the chosen symptom, so the
 * report carries diagnostics collected while the audio timeline is still in
 * the native ring buffer — minutes later it describes an idle device.
 */
const props = defineProps<{ durationS: number }>();
const emit = defineEmits<{ dismiss: [] }>();

const { t } = useI18n();
const { open: openBugReport } = useBugReport();

const showProblems = ref(false);

function onPositive(): void {
  emit("dismiss");
}

function onNegative(): void {
  showProblems.value = true;
}

function onProblem(problem: CallProblem): void {
  openBugReport({
    context: buildFeedbackDescription(
      problem,
      props.durationS,
      t(`callFeedback.problem.${problem}`),
    ),
  });
  emit("dismiss");
}
</script>

<template>
  <div
    class="pointer-events-auto rounded-2xl border border-neutral-grad-0 bg-background-total-theme p-4 shadow-lg"
    role="dialog"
    :aria-label="t('callFeedback.title')"
  >
    <div v-if="!showProblems" class="flex items-center gap-3">
      <span class="flex-1 text-sm text-text-color">{{ t("callFeedback.title") }}</span>
      <button
        class="flex h-10 w-10 items-center justify-center rounded-full border border-neutral-grad-0 text-lg transition-colors hover:bg-neutral-grad-0"
        :aria-label="t('callFeedback.good')"
        data-testid="call-feedback-good"
        @click="onPositive"
      >
        👍
      </button>
      <button
        class="flex h-10 w-10 items-center justify-center rounded-full border border-neutral-grad-0 text-lg transition-colors hover:bg-neutral-grad-0"
        :aria-label="t('callFeedback.bad')"
        data-testid="call-feedback-bad"
        @click="onNegative"
      >
        👎
      </button>
    </div>

    <div v-else class="space-y-2">
      <p class="text-sm text-text-color">{{ t("callFeedback.whatWentWrong") }}</p>
      <ul class="space-y-1.5">
        <li v-for="problem in CALL_PROBLEMS" :key="problem">
          <button
            class="w-full rounded-xl border border-neutral-grad-0 px-3 py-2 text-left text-sm text-text-color transition-colors hover:border-neutral-grad-2 hover:bg-neutral-grad-0"
            :data-testid="`call-feedback-problem-${problem}`"
            @click="onProblem(problem)"
          >
            {{ t(`callFeedback.problem.${problem}`) }}
          </button>
        </li>
      </ul>
      <button
        class="w-full py-1 text-xs text-text-on-main-bg-color transition-colors hover:text-text-color"
        data-testid="call-feedback-skip"
        @click="emit('dismiss')"
      >
        {{ t("callFeedback.skip") }}
      </button>
    </div>
  </div>
</template>
