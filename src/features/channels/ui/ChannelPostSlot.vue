<script setup lang="ts">
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from "vue";
import { PostCard } from "@/features/post-player";

/**
 * Keeps a channel post card mounted only near the visible part of the list
 * (audit W2D-02). A long channel session kept every card it ever showed, with
 * its images, players and score entries. Out of range, an empty box of the
 * card's measured height takes its place, so the column-reverse list does not
 * jump.
 */
const props = defineProps<{ txid: string }>();

/** How far outside the visible part of the list a card stays mounted. */
const KEEP_MOUNTED_MARGIN = "1500px 0px";
/** Marks the list's scroll container. The observer must use it as the root: a
 *  scroll container clips its children, and rootMargin only widens the root. */
const SCROLL_ROOT_SELECTOR = "[data-channel-scroll]";

const rootRef = ref<HTMLElement>();
const cardRef = ref<{ holdsOverlay?: boolean } | null>(null);
const isCardMounted = ref(true);
/** Height of the card when it was swapped out; the empty box keeps it. */
const placeholderHeight = ref(0);
/**
 * A card mounted again renders short until its images load. Below the visible
 * post, that growth would push the list, so the slot keeps the old height as
 * a minimum until the card reaches it.
 */
const reservedHeight = ref(0);
let observer: IntersectionObserver | null = null;
let growthObserver: ResizeObserver | null = null;

const slotStyle = computed(() => {
  if (!isCardMounted.value) return { height: `${placeholderHeight.value}px` };
  return reservedHeight.value ? { minHeight: `${reservedHeight.value}px` } : undefined;
});

function stopGrowthWatch() {
  growthObserver?.disconnect();
  growthObserver = null;
}

function releaseReserveWhenGrown() {
  const card = rootRef.value?.firstElementChild as HTMLElement | null | undefined;
  if (!card || !reservedHeight.value || typeof ResizeObserver === "undefined") return;
  const release = () => {
    if (card.offsetHeight < reservedHeight.value) return;
    reservedHeight.value = 0;
    stopGrowthWatch();
  };
  growthObserver = new ResizeObserver(release);
  growthObserver.observe(card);
  release();
}

function handleIntersect(entries: IntersectionObserverEntry[]) {
  const entry = entries[entries.length - 1];
  if (!entry) return;
  if (entry.isIntersecting) {
    if (isCardMounted.value) return;
    reservedHeight.value = placeholderHeight.value;
    isCardMounted.value = true;
    void nextTick(releaseReserveWhenGrown);
    return;
  }
  // Unmounting the card would close its modal, gallery or boost dialog.
  if (!isCardMounted.value || cardRef.value?.holdsOverlay) return;
  // Zero height: the list is hidden, not scrolled away. An empty box would
  // collapse it and lose the scroll position.
  const height = rootRef.value?.offsetHeight ?? 0;
  if (height === 0) return;
  stopGrowthWatch();
  placeholderHeight.value = height;
  reservedHeight.value = 0;
  isCardMounted.value = false;
}

onMounted(() => {
  if (!rootRef.value || typeof IntersectionObserver === "undefined") return;
  const root = rootRef.value.closest(SCROLL_ROOT_SELECTOR);
  observer = new IntersectionObserver(handleIntersect, { root, rootMargin: KEEP_MOUNTED_MARGIN });
  observer.observe(rootRef.value);
});

onBeforeUnmount(() => {
  observer?.disconnect();
  observer = null;
  stopGrowthWatch();
});
</script>

<template>
  <div ref="rootRef" :data-post-slot="props.txid" :style="slotStyle">
    <PostCard v-if="isCardMounted" ref="cardRef" :txid="props.txid" :is-own="false" />
  </div>
</template>
