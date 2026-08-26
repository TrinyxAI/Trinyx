import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative, sep } from 'path';

/**
 * Repo-wide guard: production code takes its token from `apiClient.getAuthToken()`, never by
 * calling the provider that `apiClient.getTokenProvider()` hands back.
 *
 * <p><strong>Why this is a test and not just a rule.</strong> The two are interchangeable on a
 * good day and differ only during the async auth bootstrap in `smart-providers.tsx`: until it
 * finishes, `getTokenProvider()` is `undefined`, so a caller reading it sends the request with no
 * `Authorization` header at all. In the 7 days to 2026-08-25 that was the gateway's single most
 * frequent error, 98 x 401 on `GET /api/files/by-id/<id>/raw`, refused in 0 ms; the effects that
 * made those requests were one-shot, so the media stayed broken until a reload. `getAuthToken()`
 * waits, which is the whole difference.
 *
 * <p>The migration touched ~15 call sites. Most are plain fetch plumbing that no unit test
 * exercises, so a revert on any of them would go unnoticed - mutation testing confirmed seven
 * could be reverted with the entire suite still green. A per-site behavioural test for each is not
 * the right shape; this scan is, and it is why AGENTS.md can state the rule as FORBIDDEN rather
 * than as advice.
 *
 * <p>Two files are allowed to read the provider. `smart-providers.tsx` saves and restores the
 * provider FUNCTION around a share-token session, so it never wants a token at all. `useModels.ts`
 * calls a PUBLIC endpoint that the landing and marketplace hit signed out, where the wait would
 * only delay a request that was always going to be anonymous; the cost of not waiting is a known
 * gap documented at that call site.
 */

const FRONTEND_ROOT = join(__dirname, '..');

/**
 * Files permitted to name getTokenProvider, each for a reason documented at the call site.
 *
 * <p>Note what this guard does NOT catch: a new raw-fetch site that takes its token from
 * `useAppAuth().token`, which is also null until the bootstrap finishes, reproduces the same bug
 * by a different route. The scan closes the spelling that caused the incident, not the whole
 * space of ways to read a token too early.
 */
const ALLOWED = new Set([
  // Declares the method. Matching the declaration is the scan working, not a violation.
  join('lib', 'api', 'api-client.ts'),
  // Saves and restores the provider FUNCTION around a share-token session; it never wants a token.
  join('lib', 'providers', 'smart-providers.tsx'),
  // Calls a PUBLIC endpoint that the landing and marketplace hit signed out, where waiting would
  // only delay a request that was always going to be anonymous. The cost of not waiting is a
  // known gap, documented at that call site.
  join('hooks', 'useModels.ts'),
]);

/** Not production code: tests legitimately mock or assert on the provider. */
const SKIP_DIRS = new Set([
  'node_modules', '.next', '__tests__', 'e2e', 'coverage', 'public', 'messages', 'test-results',
]);

const SOURCE_EXTENSIONS = ['.ts', '.tsx'];

/**
 * The name itself, not a call shape.
 *
 * <p>This started as a match on `getTokenProvider(` and was widened twice, because every call
 * shape it added was still one spelling away from a bypass: `?.()` (the idiomatic call on a method
 * typed `… | undefined`, and what two pre-fix sites actually wrote), `!()`, bracket access,
 * `.bind`/`.call`, then a renamed destructure, then storing the method in a local first. That is
 * an argument the guard cannot win, so it stops playing: **any mention of the identifier outside
 * the allow-list is a failure**, whatever the syntax around it.
 *
 * <p>The cost is that a comment naming the method also trips it. That is the right trade: the
 * three explanatory comments that used to say "getAuthToken, not getTokenProvider" now say
 * "rather than reading the provider", which reads the same and cannot be confused for a call.
 */
const NAME = 'getTokenProvider';
const PROVIDER_REACH = { test: (source: string) => source.includes(NAME) };

function collectSourceFiles(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, out);
    } else if (SOURCE_EXTENSIONS.some((ext) => entry.endsWith(ext)) && !entry.includes('.test.')) {
      out.push(full);
    }
  }
  return out;
}

describe('auth token source', () => {
  it('is apiClient.getAuthToken() everywhere outside the documented allow-list', () => {
    const offenders = collectSourceFiles(FRONTEND_ROOT)
      .filter((file) => PROVIDER_REACH.test(readFileSync(file, 'utf8')))
      .map((file) => relative(FRONTEND_ROOT, file))
      .filter((rel) => !ALLOWED.has(rel));

    expect(
      offenders,
      'These files reach for the token provider directly. During the auth bootstrap it is '
        + 'undefined, so the request goes out with no Authorization header and the gateway '
        + 'answers 401. Use apiClient.getAuthToken(), which waits. If a new file genuinely needs '
        + 'the provider FUNCTION rather than a token, add it to ALLOWED with the reason at its '
        + `call site. Offenders:\n  ${offenders.join('\n  ')}`,
    ).toEqual([]);
  });

  it('catches every spelling, because it matches the name and not a call shape', () => {
    // Each of these was verified to bypass an earlier, call-shape version of this guard.
    for (const spelling of [
      'const t = await apiClient.getTokenProvider()?.();',
      "apiClient['getTokenProvider']()",
      'apiClient["getTokenProvider"]()',
      'await apiClient.getTokenProvider?.()',
      'apiClient.getTokenProvider!()',
      'apiClient.getTokenProvider.bind(apiClient)()',
      'const { getTokenProvider: read } = apiClient as any;',
      'const p = apiClient.getTokenProvider; await p.call(apiClient)();',
    ]) {
      expect(PROVIDER_REACH.test(spelling), `not caught: ${spelling}`).toBe(true);
    }
    // ...and does not fire on the method this change tells everyone to use instead.
    expect(PROVIDER_REACH.test('await apiClient.getAuthToken()')).toBe(false);
  });

  it('finds the allow-listed files, so a rename cannot silently empty the allow-list', () => {
    // Without this, renaming smart-providers.tsx would leave a stale entry that matches nothing
    // and the guard above would keep passing while the real file went unchecked.
    for (const allowed of ALLOWED) {
      const contents = readFileSync(join(FRONTEND_ROOT, allowed), 'utf8');
      expect(PROVIDER_REACH.test(contents), `${allowed} is allow-listed but no longer names the provider`)
        .toBe(true);
    }
  });

  it('scans a realistic number of files, so a broken walker cannot pass by finding nothing', () => {
    const files = collectSourceFiles(FRONTEND_ROOT);
    expect(files.length).toBeGreaterThan(500);
    expect(files.some((f) => f.endsWith(join('hooks', 'useAuthedObjectUrl.ts').replace(/\//g, sep)))).toBe(true);
  });
});
