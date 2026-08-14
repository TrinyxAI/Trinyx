// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest';
import {
  FILE_STRIP_EXPANDED_STORAGE_KEY,
  readFileStripPreference,
  writeFileStripPreference,
} from '../fileStripPreference';

/**
 * The stored "do file previews open by themselves" flag.
 *
 * Two things are worth pinning here rather than through the provider: the read must
 * never COERCE (the classic version of this helper ships `Boolean(raw)`, and
 * `Boolean('false')` is true, which silently inverts the preference), and neither
 * function may throw. Storage throws on access in Safari private mode and when the
 * quota is full, and the provider calls the read during mount - an exception there
 * takes the whole canvas down, which is a far worse outcome than losing a preference.
 */

/** A store that throws on everything, like a locked-down browser. */
const throwingStorage = {
  getItem: () => { throw new DOMException('denied'); },
  setItem: () => { throw new DOMException('denied'); },
  removeItem: () => { throw new DOMException('denied'); },
  clear: () => { throw new DOMException('denied'); },
  key: () => null,
  length: 0,
} as unknown as Storage;

beforeEach(() => {
  window.localStorage.clear();
});

describe('readFileStripPreference', () => {
  it('reads back exactly what was written', () => {
    writeFileStripPreference(true);
    expect(readFileStripPreference()).toBe(true);

    writeFileStripPreference(false);
    expect(readFileStripPreference()).toBe(false);
  });

  it('returns null when nothing is stored, so the caller keeps its own default', () => {
    expect(readFileStripPreference()).toBeNull();
  });

  it('does NOT coerce: a stored "false" is false, not a truthy string', () => {
    window.localStorage.setItem(FILE_STRIP_EXPANDED_STORAGE_KEY, 'false');
    expect(readFileStripPreference()).toBe(false);
  });

  it('treats any other value as "nothing stored" rather than guessing', () => {
    for (const junk of ['1', '0', 'yes', 'TRUE', '', '{}']) {
      window.localStorage.setItem(FILE_STRIP_EXPANDED_STORAGE_KEY, junk);
      expect(readFileStripPreference(), `junk value ${JSON.stringify(junk)}`).toBeNull();
    }
  });

  it('survives a storage that throws, instead of taking the canvas down on mount', () => {
    expect(() => readFileStripPreference(throwingStorage)).not.toThrow();
    expect(readFileStripPreference(throwingStorage)).toBeNull();
  });

  it('returns null with NO storage at all, the way a server render sees it', () => {
    // Explicit null, not `undefined`: undefined means "use the browser's", so passing
    // it would read the jsdom store and pass for the wrong reason, leaving the
    // no-storage branch uncovered while claiming to cover it.
    window.localStorage.setItem(FILE_STRIP_EXPANDED_STORAGE_KEY, 'true');
    expect(readFileStripPreference(null)).toBeNull();
  });
});

describe('writeFileStripPreference', () => {
  it('stores the two values the read understands', () => {
    writeFileStripPreference(true);
    expect(window.localStorage.getItem(FILE_STRIP_EXPANDED_STORAGE_KEY)).toBe('true');

    writeFileStripPreference(false);
    expect(window.localStorage.getItem(FILE_STRIP_EXPANDED_STORAGE_KEY)).toBe('false');
  });

  it('survives a storage that throws: the choice just does not outlive the session', () => {
    expect(() => writeFileStripPreference(true, throwingStorage)).not.toThrow();
  });

  it('is a no-op with NO storage at all, rather than reaching for a global', () => {
    expect(() => writeFileStripPreference(true, null)).not.toThrow();
    expect(window.localStorage.getItem(FILE_STRIP_EXPANDED_STORAGE_KEY)).toBeNull();
  });
});
