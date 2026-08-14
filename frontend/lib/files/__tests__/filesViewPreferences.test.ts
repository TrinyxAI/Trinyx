/**
 * Unit tests for the Files browser's preference + URL helpers.
 *
 * These are the pure decisions behind three user-visible behaviours: a folder click that
 * repeats the folder you are already in must do nothing (the bug that stacked
 * "Run 12 / Run 12 / Run 12" into the breadcrumb), a navigation must never drop the other
 * query params, and a preference written by an older build must degrade to the default
 * instead of rendering an unsortable or blank view.
 */
import { describe, it, expect } from 'vitest';
import {
  DEFAULT_SORT_PREFERENCE,
  FILES_FOLDER_PARAM,
  folderQueryString,
  isSameFolder,
  naturalDirectionFor,
  normalizeSortPreference,
  normalizeViewMode,
} from '../filesViewPreferences';

describe('normalizeViewMode', () => {
  it('keeps a stored list view', () => {
    expect(normalizeViewMode('list')).toBe('list');
  });

  it('falls back to the grid for anything it does not recognise', () => {
    // A value from an older build, a hand-edited entry, or nothing stored at all.
    expect(normalizeViewMode('grid')).toBe('grid');
    expect(normalizeViewMode('mosaic')).toBe('grid');
    expect(normalizeViewMode(undefined)).toBe('grid');
    expect(normalizeViewMode(null)).toBe('grid');
    expect(normalizeViewMode(42)).toBe('grid');
  });
});

describe('naturalDirectionFor', () => {
  it('reads text criteria A→Z and quantitative ones biggest/newest first', () => {
    expect(naturalDirectionFor('name')).toBe('asc');
    expect(naturalDirectionFor('type')).toBe('asc');
    expect(naturalDirectionFor('date')).toBe('desc');
    expect(naturalDirectionFor('size')).toBe('desc');
  });
});

describe('normalizeSortPreference', () => {
  it('keeps a complete, known preference', () => {
    expect(normalizeSortPreference({ key: 'size', direction: 'asc' })).toEqual({ key: 'size', direction: 'asc' });
  });

  it('defaults a criterion it no longer offers, rather than sorting by nothing', () => {
    expect(normalizeSortPreference({ key: 'colour', direction: 'asc' }))
      .toEqual({ key: DEFAULT_SORT_PREFERENCE.key, direction: 'asc' });
  });

  it('fills a missing direction with the criterion\'s natural one, not a blanket desc', () => {
    // "sort by name" with no direction must read A→Z, which is why this is not `desc`.
    expect(normalizeSortPreference({ key: 'name' })).toEqual({ key: 'name', direction: 'asc' });
    expect(normalizeSortPreference({ key: 'date' })).toEqual({ key: 'date', direction: 'desc' });
  });

  it('falls back wholesale on a value that is not a preference at all', () => {
    expect(normalizeSortPreference(null)).toEqual(DEFAULT_SORT_PREFERENCE);
    expect(normalizeSortPreference('date')).toEqual(DEFAULT_SORT_PREFERENCE);
    expect(normalizeSortPreference(undefined)).toEqual(DEFAULT_SORT_PREFERENCE);
  });
});

describe('folderQueryString', () => {
  it('sets the folder param', () => {
    expect(folderQueryString(new URLSearchParams(), 'wf:1/r7'))
      .toBe(`${FILES_FOLDER_PARAM}=wf%3A1%2Fr7`);
  });

  it('drops the param at the root, leaving a clean URL', () => {
    expect(folderQueryString(new URLSearchParams('folder=abc'), null)).toBe('');
  });

  it('preserves every other param, so navigating never silently discards one', () => {
    const next = new URLSearchParams(folderQueryString(new URLSearchParams('tab=files&highlight=x'), 'abc'));
    expect(next.get('tab')).toBe('files');
    expect(next.get('highlight')).toBe('x');
    expect(next.get(FILES_FOLDER_PARAM)).toBe('abc');
  });

  it('does not mutate the params it was given', () => {
    const current = new URLSearchParams('folder=a');
    folderQueryString(current, 'b');
    expect(current.get('folder')).toBe('a');
  });
});

describe('isSameFolder', () => {
  it('treats null and undefined as the same place - the root', () => {
    expect(isSameFolder(null, undefined)).toBe(true);
    expect(isSameFolder(undefined, null)).toBe(true);
  });

  it('recognises a repeated folder - the guard against a duplicate history entry', () => {
    expect(isSameFolder('wf:1/r12', 'wf:1/r12')).toBe(true);
  });

  it('does not confuse two different folders', () => {
    expect(isSameFolder('wf:1/r12', 'wf:1/r13')).toBe(false);
    expect(isSameFolder(null, 'wf:1')).toBe(false);
  });
});
