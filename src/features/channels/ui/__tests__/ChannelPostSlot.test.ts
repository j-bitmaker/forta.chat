// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

const cardState = { holdsOverlay: false };

vi.mock('@/features/post-player', () => ({
  PostCard: defineComponent({
    name: 'PostCard',
    props: { txid: String, isOwn: Boolean },
    setup(props, { expose }) {
      expose({
        get holdsOverlay() {
          return cardState.holdsOverlay;
        },
      });
      return () => h('div', { class: 'post-card' }, props.txid);
    },
  }),
}));

import ChannelPostSlot from '../ChannelPostSlot.vue';

type Callback = (entries: Array<Partial<IntersectionObserverEntry>>) => void;
let observed: { callback: Callback; options?: IntersectionObserverInit; disconnect: ReturnType<typeof vi.fn> }[] = [];

class FakeIntersectionObserver {
  callback: Callback;
  options?: IntersectionObserverInit;
  disconnect = vi.fn();
  constructor(callback: Callback, options?: IntersectionObserverInit) {
    this.callback = callback;
    this.options = options;
    observed.push(this);
  }
  observe(): void {}
  unobserve(): void {}
}

function setHeight(el: Element, px: number): void {
  Object.defineProperty(el, 'offsetHeight', { configurable: true, value: px });
}

async function report(isIntersecting: boolean): Promise<void> {
  observed.at(-1)!.callback([{ isIntersecting }]);
  await nextTick();
}

beforeEach(() => {
  observed = [];
  cardState.holdsOverlay = false;
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ChannelPostSlot (audit W2D-02)', () => {
  it('shows the card at once, before the observer reports', () => {
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    expect(wrapper.find('.post-card').exists()).toBe(true);
    expect(observed[0].options?.rootMargin).toBe('1500px 0px');
  });

  it('observes against the list scroll container, which would clip a viewport root', () => {
    const list = document.createElement('div');
    list.setAttribute('data-channel-scroll', '');
    document.body.appendChild(list);

    mount(ChannelPostSlot, { props: { txid: 'tx1' }, attachTo: list });

    expect(observed[0].options?.root).toBe(list);
    list.remove();
  });

  it('swaps a card far from the viewport for an empty box of the same height', async () => {
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    setHeight(wrapper.element, 420);

    await report(false);

    expect(wrapper.find('.post-card').exists()).toBe(false);
    expect((wrapper.element as HTMLElement).style.height).toBe('420px');
  });

  it('mounts the card again when it comes near the viewport', async () => {
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    setHeight(wrapper.element, 300);
    await report(false);

    await report(true);

    expect(wrapper.find('.post-card').exists()).toBe(true);
    expect((wrapper.element as HTMLElement).style.height).toBe('');
  });

  it('keeps the old height as a minimum until the card mounted again grows back', async () => {
    const resize: { callback: () => void; disconnect: ReturnType<typeof vi.fn> }[] = [];
    vi.stubGlobal('ResizeObserver', class {
      callback: () => void;
      disconnect = vi.fn();
      constructor(callback: () => void) {
        this.callback = callback;
        resize.push(this);
      }
      observe(): void {}
    });
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    setHeight(wrapper.element, 500);
    await report(false);

    await report(true);
    await nextTick();
    const slot = wrapper.element as HTMLElement;
    const card = slot.firstElementChild!;
    expect(slot.style.minHeight).toBe('500px');

    // Images still loading: the card is short, the reserve stays.
    setHeight(card, 120);
    resize[0].callback();
    await nextTick();
    expect(slot.style.minHeight).toBe('500px');

    setHeight(card, 500);
    resize[0].callback();
    await nextTick();
    expect(slot.style.minHeight).toBe('');
    expect(resize[0].disconnect).toHaveBeenCalled();
  });

  it('keeps a card whose modal, gallery or boost dialog is open', async () => {
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    setHeight(wrapper.element, 300);
    cardState.holdsOverlay = true;

    await report(false);

    expect(wrapper.find('.post-card').exists()).toBe(true);
  });

  it('keeps the card while the list is hidden, so the list does not collapse', async () => {
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    setHeight(wrapper.element, 0);

    await report(false);

    expect(wrapper.find('.post-card').exists()).toBe(true);
  });

  it('stops observing when the slot goes away', () => {
    const wrapper = mount(ChannelPostSlot, { props: { txid: 'tx1' } });
    wrapper.unmount();
    expect(observed[0].disconnect).toHaveBeenCalled();
  });
});
