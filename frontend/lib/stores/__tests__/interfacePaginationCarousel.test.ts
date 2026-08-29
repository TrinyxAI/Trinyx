/**
 * The application carousel page, once it stopped being a single number.
 *
 * It was one index for the whole app, which was unambiguous while at most one
 * carousel existed. The side panel keeps every opened tab mounted, so two
 * applications can be on screen at once and the shared number moved them
 * together. Keying it by SURFACE is what separates them, and the run-switch
 * clean-up has to respect that separation or it hands it straight back.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { carouselKeyFor, useInterfacePaginationStore } from '../interface-pagination-store';

const store = () => useInterfacePaginationStore.getState();

beforeEach(() => {
  store().clear();
});

describe('carouselKeyFor', () => {
  it('separates two surfaces of the same workflow on different runs', () => {
    expect(carouselKeyFor('wf-1', 'run-a')).not.toBe(carouselKeyFor('wf-1', 'run-b'));
  });

  it('separates two workflows sharing nothing but a mount', () => {
    expect(carouselKeyFor('wf-1', 'run-a')).not.toBe(carouselKeyFor('wf-2', 'run-a'));
  });

  it('gives a surface with no run yet a key of its own, not a shared blank', () => {
    expect(carouselKeyFor('wf-1', null)).toBe('wf-1:');
    expect(carouselKeyFor('wf-1', null)).not.toBe(carouselKeyFor('wf-2', null));
  });
});

describe('setCarouselIndex', () => {
  it('moves one surface without touching its neighbours', () => {
    store().setCarouselIndex('wf-1:run-a', 2);
    store().setCarouselIndex('wf-2:run-b', 1);

    store().setCarouselIndex('wf-1:run-a', 0);

    expect(store().carouselIndex).toEqual({ 'wf-1:run-a': 0, 'wf-2:run-b': 1 });
  });
});

describe('clearForRunSwitch', () => {
  it('forgets every surface of the run being left', () => {
    store().setCarouselIndex('wf-1:run-a', 2);
    // Same run, another workflow - a sub-workflow tab bound to the same run.
    store().setCarouselIndex('wf-2:run-a', 3);
    store().setCarouselIndex('wf-1:run-b', 1);

    store().clearForRunSwitch('run-a');

    expect(store().carouselIndex).toEqual({ 'wf-1:run-b': 1 });
  });

  it('matches the run, not a run whose id merely ends the same way', () => {
    store().setCarouselIndex('wf-1:run-a', 2);
    // 'a-run-a' ends with 'run-a' as text but is a different run: the key is
    // built with a ':' separator precisely so this cannot collide.
    store().setCarouselIndex('wf-1:a-run-a', 5);

    store().clearForRunSwitch('a-run-a');

    expect(store().carouselIndex).toEqual({ 'wf-1:run-a': 2 });
  });

  it('drops the epoch cursors regardless: those are keyed by interface, which a new run reuses', () => {
    store().setPage('iface-1', 4);
    store().setPlaying('iface-1', true);
    store().setCarouselIndex('wf-1:run-a', 2);

    store().clearForRunSwitch('run-a');

    expect(store().pages).toEqual({});
    expect(store().playing).toEqual({});
  });

  it('leaves every carousel page alone when there is no run to forget', () => {
    store().setPage('iface-1', 4);
    store().setCarouselIndex('wf-1:run-a', 2);

    // A provider that only ever observed SOMEONE ELSE's switch: the surfaces on
    // screen did not move, so their pages must not.
    store().clearForRunSwitch(undefined);

    expect(store().carouselIndex).toEqual({ 'wf-1:run-a': 2 });
    expect(store().pages).toEqual({});
  });
});
