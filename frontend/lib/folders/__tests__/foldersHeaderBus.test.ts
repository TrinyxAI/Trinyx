// @vitest-environment jsdom
/**
 * The channel between a resource list and the app header, and the URL that actually carries
 * the open folder. The rule worth pinning is that navigating into a folder must not drop the
 * rest of the address - a list can be on a tab, a search, a page number at the same time.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  emitResourceFolderTrail,
  folderUrl,
  onResourceFolderTrail,
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
