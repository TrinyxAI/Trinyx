// @vitest-environment jsdom
/**
 * The list tests' stand-in for Next: it plays the part Next plays in a browser, where a
 * `history.pushState` for the page already on screen IS a navigation that `useSearchParams`
 * must see. If this bridge is wrong, a whole family of folder tests passes while the feature
 * is broken - so the bridge itself is worth pinning.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { fakeFolderRouter } from '../fakeFolderRouter';

beforeEach(() => fakeFolderRouter.reset('/en/app/workflow'));

describe('fakeFolderRouter - the history bridge', () => {
  it('turns a pushState into a navigation the list can see', () => {
    window.history.pushState(null, '', '/en/app/workflow?folder=f1');

    expect(fakeFolderRouter.search()).toBe('folder=f1');
    expect(fakeFolderRouter.navigations).toEqual([
      { url: '/en/app/workflow?folder=f1', method: 'push' },
    ]);
  });

  it('records a replaceState as a REPLACE, so a test can tell a working Back from a broken one', () => {
    window.history.replaceState(null, '', '/en/app/workflow');

    expect(fakeFolderRouter.navigations).toEqual([{ url: '/en/app/workflow', method: 'replace' }]);
  });

  it('ignores a call that carries no url, instead of navigating to the string "undefined"', () => {
    // `pushState(state, '')` changes only the state object and never the address. Forwarding
    // it would leave the list showing a level called "undefined".
    window.history.pushState({ some: 'state' }, '');

    expect(fakeFolderRouter.navigations).toEqual([]);
    expect(fakeFolderRouter.search()).toBe('');
  });

  it('starts each test from a clean address', () => {
    window.history.pushState(null, '', '/en/app/workflow?folder=f1');
    fakeFolderRouter.reset('/en/app/agent');

    expect(fakeFolderRouter.search()).toBe('');
    expect(fakeFolderRouter.navigations).toEqual([]);
  });
});
