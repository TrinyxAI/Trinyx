/**
 * The showcase file-strip source.
 *
 * The owner's canvas resolves a node's file from the run's step outputs; every one of those
 * reads is disabled inside a publication preview (anonymous visitor, tenant-scoped endpoints),
 * so a published workflow's file nodes showed nothing at all. This hook is the fallback that
 * feeds them from the publication's own frozen copy.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

let queryOptions: Record<string, unknown> | null = null;
let queryResult: { data: unknown } = { data: undefined };

vi.mock('@tanstack/react-query', () => ({
  useQuery: (options: Record<string, unknown>) => {
    queryOptions = options;
    return queryResult;
  },
}));

let activePreview: { publicationId: string; showcaseRunId: string; remote: boolean; authenticated: boolean } | null = null;
vi.mock('@/contexts/PublicationSnapshotContext', () => ({
  getActivePublicPreview: () => activePreview,
}));

const getShowcaseStepFiles = vi.fn();
vi.mock('@/lib/api/orchestrator/publication.service', () => ({
  publicationService: { getShowcaseStepFiles: (...args: unknown[]) => getShowcaseStepFiles(...args) },
}));

import { useShowcaseStepFiles, selectShowcaseStepFile } from '../useShowcaseStepFiles';

const file = (name: string) => ({ name, mimeType: 'video/mp4', size: 1, url: `/api/files/proxy-signed?key=${name}` });

beforeEach(() => {
  queryOptions = null;
  queryResult = { data: undefined };
  activePreview = null;
  getShowcaseStepFiles.mockClear();
});

describe('useShowcaseStepFiles', () => {
  it('issues no request outside a publication preview - it is a fallback, not a second source', () => {
    useShowcaseStepFiles();
    expect(queryOptions?.enabled).toBe(false);
    expect(getShowcaseStepFiles).not.toHaveBeenCalled();
  });

  it('returns an empty map, never undefined, so every consumer can index it without a guard', () => {
    expect(useShowcaseStepFiles()).toEqual({});
  });

  it('returns the SAME empty map across calls so a consumer memo does not re-run every render', () => {
    // A fresh `{}` per render would invalidate the per-node useMemo on every canvas repaint.
    expect(useShowcaseStepFiles()).toBe(useShowcaseStepFiles());
  });

  it('fetches once per publication and shares it across every node on the canvas', () => {
    activePreview = { publicationId: 'pub-1', showcaseRunId: 'showcase_1', remote: false, authenticated: false };
    useShowcaseStepFiles();
    expect(queryOptions?.enabled).toBe(true);
    // One cache key for the whole canvas: the payload is one entry per file-producing node, so
    // fetching it whole beats a round trip per pill.
    expect(queryOptions?.queryKey).toEqual(['showcase-step-files', 'pub-1', false]);
  });

  it('keys a cloud-linked CE read separately - it routes through a different endpoint', () => {
    activePreview = { publicationId: 'pub-1', showcaseRunId: 'showcase_1', remote: true, authenticated: false };
    useShowcaseStepFiles();
    expect(queryOptions?.queryKey).toEqual(['showcase-step-files', 'pub-1', true]);
    (queryOptions?.queryFn as () => unknown)();
    expect(getShowcaseStepFiles).toHaveBeenCalledWith('pub-1', true);
  });

  it('does not refetch on mount or focus, and goes stale well inside the signed URLs lifetime', () => {
    // The URLs carry an expiry (publication.showcase.presign-expiry-minutes, 4h by default);
    // a canvas held open past it must re-fetch on its next mount rather than keep serving
    // links that have quietly started 403-ing. Anything at or above the expiry defeats that.
    const PRESIGN_EXPIRY_MS = 240 * 60 * 1000;
    activePreview = { publicationId: 'pub-1', showcaseRunId: 'showcase_1', remote: false, authenticated: false };
    useShowcaseStepFiles();
    expect(queryOptions?.refetchOnMount).toBe(false);
    expect(queryOptions?.refetchOnWindowFocus).toBe(false);
    expect(queryOptions?.staleTime as number).toBeGreaterThan(0);
    expect(queryOptions?.staleTime as number).toBeLessThan(PRESIGN_EXPIRY_MS);
  });
});

describe('selectShowcaseStepFile', () => {
  it('picks the file of the node, for the epoch being viewed', () => {
    const map = {
      '1': { download_file: file('one.mp4'), render: file('r1.mp4') },
      '2': { download_file: file('two.mp4') },
    };
    expect(selectShowcaseStepFile(map, 2, 'download_file')?.name).toBe('two.mp4');
  });

  it('answers with the only epoch present when the viewing epoch does not match it', () => {
    // A pinned publication is captured RENUMBERED to a single key, so the visitor's epoch and
    // the snapshot's key legitimately disagree. With one epoch there is nothing to guess.
    const map = { '1': { download_file: file('pinned.mp4') } };
    expect(selectShowcaseStepFile(map, 7, 'download_file')?.name).toBe('pinned.mp4');
  });

  it('refuses to guess when several epochs exist and none matches - a wrong file is worse than none', () => {
    const map = { '1': { download_file: file('one.mp4') }, '2': { download_file: file('two.mp4') } };
    expect(selectShowcaseStepFile(map, 9, 'download_file')).toBeNull();
  });

  it('matches the alias case-insensitively, as every other read of a step alias does', () => {
    const map = { '1': { Download_File: file('one.mp4') } };
    expect(selectShowcaseStepFile(map, 1, 'download_file')?.name).toBe('one.mp4');
  });

  it('prefers the exact alias over a case-insensitive near-match', () => {
    const map = { '1': { Render: file('upper.mp4'), render: file('exact.mp4') } };
    expect(selectShowcaseStepFile(map, 1, 'render')?.name).toBe('exact.mp4');
  });

  it('returns null for a node with no file, an empty map, or a missing alias', () => {
    expect(selectShowcaseStepFile({}, 1, 'download_file')).toBeNull();
    expect(selectShowcaseStepFile({ '1': { render: file('r.mp4') } }, 1, 'download_file')).toBeNull();
    expect(selectShowcaseStepFile({ '1': { render: file('r.mp4') } }, 1, undefined)).toBeNull();
  });

  it('answers with the NEWEST epoch when none is selected - the view every visitor lands on', () => {
    // viewingEpoch starts null ("all epochs") and only an epoch pill sets it, so refusing here
    // left every multi-epoch publication with no pill at all in its default view. The owner's
    // canvas is not strict there either: it loads every epoch and opens on the newest item.
    const map = { '1': { render: file('one.mp4') }, '2': { render: file('two.mp4') } };
    expect(selectShowcaseStepFile(map, null, 'render')?.name).toBe('two.mp4');
    expect(selectShowcaseStepFile(map, undefined, 'render')?.name).toBe('two.mp4');
  });

  it('with no epoch selected, answers with the newest epoch THAT CARRIES THIS NODE, not merely the newest epoch', () => {
    // A node skipped on the last fire would otherwise go blank while its owner still sees the
    // file it produced on the fire before: the owner's navigator pages that node's own items
    // across epochs, so "newest" has to mean newest FOR THIS NODE.
    const map = {
      '4': { download_file: file('older.mp4'), render: file('r4.mp4') },
      '5': { render: file('r5.mp4') },
    };
    expect(selectShowcaseStepFile(map, null, 'download_file')?.name).toBe('older.mp4');
    expect(selectShowcaseStepFile(map, null, 'render')?.name).toBe('r5.mp4');
  });

  it('with no epoch selected, a node in NO epoch is still null - the walk ends, it does not invent', () => {
    const map = { '4': { render: file('r4.mp4') }, '5': { render: file('r5.mp4') } };
    expect(selectShowcaseStepFile(map, null, 'download_file')).toBeNull();
  });

  it('the newest-first walk keeps the case-insensitive alias match at every epoch it visits', () => {
    const map = { '5': { render: file('r5.mp4') }, '4': { Download_File: file('older.mp4') } };
    expect(selectShowcaseStepFile(map, null, 'download_file')?.name).toBe('older.mp4');
  });

  it('compares epoch keys numerically, so the tenth fire beats the ninth', () => {
    // Lexicographically "9" > "10", which is exactly the range a reusable trigger crosses.
    const map = { '9': { render: file('ninth.mp4') }, '10': { render: file('tenth.mp4') } };
    expect(selectShowcaseStepFile(map, null, 'render')?.name).toBe('tenth.mp4');
  });

  it('a non-numeric epoch key is searched LAST, so a malformed section never outranks a real epoch', () => {
    const map = {
      junk: { render: file('junk.mp4'), only_here: file('junky.mp4') },
      '2': { render: file('two.mp4') },
    };
    expect(selectShowcaseStepFile(map, null, 'render')?.name).toBe('two.mp4');
    // It is still SEARCHED, rather than dropped: a node present only under the malformed key
    // still gets its pill, both beside a real epoch and on its own.
    expect(selectShowcaseStepFile(map, null, 'only_here')?.name).toBe('junky.mp4');
    expect(selectShowcaseStepFile({ junk: { render: file('junk.mp4') } }, null, 'render')?.name).toBe('junk.mp4');
  });

  it('still refuses to guess when an epoch IS selected, is absent, and several exist', () => {
    // The visitor asked for one specific run; another run's file is worse than none.
    const map = { '1': { render: file('one.mp4') }, '2': { render: file('two.mp4') } };
    expect(selectShowcaseStepFile(map, 9, 'render')).toBeNull();
  });
});
