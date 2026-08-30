// @vitest-environment jsdom
/**
 * The channel between a resource list and the app header, and the URL that actually carries
 * the open folder. The rule worth pinning is that navigating into a folder must not drop the
 * rest of the address - a list can be on a tab, a search, a page number at the same time.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  emitResourceFolderTrail,
  folderUrl,
  onResourceFolderTrail,
  showFolderLevel,
  FOLDER_QUERY_PARAM,
} from '../foldersHeaderBus';

afterEach(() => vi.restoreAllMocks());

describe('folderUrl', () => {
  it('adds the folder to the address', () => {
    expect(folderUrl('/en/app/workflow', new URLSearchParams(), 'f1'))
      .toBe('/en/app/workflow?folder=f1');
  });

  it('drops it at the top level, leaving a clean path', () => {
    expect(folderUrl('/en/app/workflow', new URLSearchParams('folder=f1'), null))
      .toBe('/en/app/workflow');
  });

  it('keeps every other parameter the page carries', () => {
    const url = folderUrl('/en/app/agent', new URLSearchParams('view=skills&q=bot'), 'f1');

    const params = new URLSearchParams(url.split('?')[1]);
    expect(params.get('view')).toBe('skills');
    expect(params.get('q')).toBe('bot');
    expect(params.get(FOLDER_QUERY_PARAM)).toBe('f1');
  });

  it('replaces a folder already in the address rather than appending a second one', () => {
    const url = folderUrl('/en/app/tables', new URLSearchParams('folder=f1'), 'f2');

    expect(url).toBe('/en/app/tables?folder=f2');
  });

  it('leaves the other parameters alone when returning to the top level', () => {
    expect(folderUrl('/en/app/agent', new URLSearchParams('view=skills&folder=f1'), null))
      .toBe('/en/app/agent?view=skills');
  });
});

describe('the trail channel', () => {
  it('delivers what a list broadcasts', () => {
    const heard: unknown[] = [];
    const stop = onResourceFolderTrail((state) => heard.push(state));

    emitResourceFolderTrail({ view: 'workflow', trail: [{ id: 'f1', name: 'Marketing' }] });

    expect(heard).toEqual([{ view: 'workflow', trail: [{ id: 'f1', name: 'Marketing' }] }]);
    stop();
  });

  it('stops delivering once unsubscribed, so a header that left a list hears nothing', () => {
    const heard: unknown[] = [];
    const stop = onResourceFolderTrail((state) => heard.push(state));
    stop();

    emitResourceFolderTrail({ view: 'agent', trail: [] });

    expect(heard).toEqual([]);
  });

  it('says WHICH list is speaking, so a header can ignore another one\'s path', () => {
    const heard: Array<{ view: string }> = [];
    const stop = onResourceFolderTrail((state) => heard.push(state));

    emitResourceFolderTrail({ view: 'table', trail: [{ id: 'f1', name: 'Sales' }] });
    emitResourceFolderTrail({ view: 'interface', trail: [] });

    expect(heard.map((s) => s.view)).toEqual(['table', 'interface']);
    stop();
  });
});

/**
 * Changing the level is a change of `?folder=` on the page ALREADY on screen, so it goes
 * through the history API rather than the router. The bug that forced this: from a page loaded
 * directly on `?folder=<id>` - a shared link, a reload, the browser restoring a tab - a router
 * push of the bare pathname is dropped, and every way out of the folder does nothing at all.
 */
describe('showFolderLevel', () => {
  const push = vi.fn();
  const replace = vi.fn();
  const originalPush = window.history.pushState;
  const originalReplace = window.history.replaceState;

  beforeEach(() => {
    push.mockClear();
    replace.mockClear();
    window.history.pushState = push as unknown as typeof window.history.pushState;
    window.history.replaceState = replace as unknown as typeof window.history.replaceState;
  });
  afterEach(() => {
    window.history.pushState = originalPush;
    window.history.replaceState = originalReplace;
  });

  it('opens a folder as a history STEP, so Back walks back out of it', () => {
    showFolderLevel('/en/app/workflow', new URLSearchParams(), 'f1');

    expect(push).toHaveBeenCalledWith(null, '', '/en/app/workflow?folder=f1');
    expect(replace).not.toHaveBeenCalled();
  });

  it('leaves a folder for the top level, which no router push could do', () => {
    showFolderLevel('/en/app/workflow', new URLSearchParams('folder=f1'), null);

    expect(push).toHaveBeenCalledWith(null, '', '/en/app/workflow');
  });

  it('keeps every other parameter the page carries', () => {
    showFolderLevel('/en/app/agent', new URLSearchParams('view=skills&folder=f1'), 'f2');

    expect(push).toHaveBeenCalledWith(null, '', '/en/app/agent?view=skills&folder=f2');
  });

  it('corrects a dead folder in the address in place, so Back cannot return to it', () => {
    showFolderLevel('/en/app/workflow', new URLSearchParams('folder=gone'), null, 'replace');

    expect(replace).toHaveBeenCalledWith(null, '', '/en/app/workflow');
    expect(push).not.toHaveBeenCalled();
  });

  it('does nothing on the server, where there is no history to write to', () => {
    vi.stubGlobal('window', undefined);
    try {
      expect(() => showFolderLevel('/en/app/workflow', new URLSearchParams(), 'f1')).not.toThrow();
    } finally {
      vi.unstubAllGlobals();
    }
    expect(push).not.toHaveBeenCalled();
  });

  it('does nothing for the level already shown, so a crumb cannot stack a duplicate step', () => {
    showFolderLevel('/en/app/workflow', new URLSearchParams('folder=f1'), 'f1');
    showFolderLevel('/en/app/workflow', new URLSearchParams(), null);

    expect(push).not.toHaveBeenCalled();
    expect(replace).not.toHaveBeenCalled();
  });
});
