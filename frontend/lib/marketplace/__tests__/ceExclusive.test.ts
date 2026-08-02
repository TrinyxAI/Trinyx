/**
 * CE-exclusive helper: the single place the UI decides whether an install is
 * blocked. The edition is frozen at build time (`IS_CLOUD` is a module const),
 * so each edition is exercised with a fresh module registry.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

/** The three deployment shapes the backend distinguishes. */
const MANAGED_CLOUD = { IS_MANAGED_CLOUD: true, IS_CLOUD: true, IS_CE: false };
const COMMUNITY_EDITION = { IS_MANAGED_CLOUD: false, IS_CLOUD: false, IS_CE: true };
/**
 * Self-hosted enterprise: keycloak auth, so the binary EDITION resolves to
 * 'cloud' (IS_CLOUD true) while the deployment is self-hosted. The gate must
 * follow IS_MANAGED_CLOUD, not IS_CLOUD.
 */
const SELF_HOSTED_ENTERPRISE = { IS_MANAGED_CLOUD: false, IS_CLOUD: true, IS_CE: false };

async function loadWith(edition: { IS_MANAGED_CLOUD: boolean; IS_CLOUD: boolean; IS_CE: boolean }) {
  vi.resetModules();
  vi.doMock('@/lib/edition', () => edition);
  return import('../ceExclusive');
}

afterEach(() => {
  vi.doUnmock('@/lib/edition');
  vi.resetModules();
});

describe('isCeExclusive', () => {
  it('reads the backend flag regardless of the running edition', async () => {
    const { isCeExclusive } = await loadWith(MANAGED_CLOUD);

    expect(isCeExclusive({ ceExclusive: true })).toBe(true);
    expect(isCeExclusive({ ceExclusive: false })).toBe(false);
    expect(isCeExclusive({})).toBe(false);
    expect(isCeExclusive(null)).toBe(false);
    expect(isCeExclusive(undefined)).toBe(false);
  });
});

describe('isCeExclusiveBlocked', () => {
  it('blocks a CE-exclusive publication on managed cloud', async () => {
    const { isCeExclusiveBlocked } = await loadWith(MANAGED_CLOUD);

    expect(isCeExclusiveBlocked({ ceExclusive: true })).toBe(true);
  });

  it('never blocks a normal publication, even on cloud', async () => {
    const { isCeExclusiveBlocked } = await loadWith(MANAGED_CLOUD);

    expect(isCeExclusiveBlocked({ ceExclusive: false })).toBe(false);
    expect(isCeExclusiveBlocked({})).toBe(false);
  });

  it('never blocks on a self-hosted build - that is the whole point of the label', async () => {
    const { isCeExclusiveBlocked } = await loadWith(COMMUNITY_EDITION);

    expect(isCeExclusiveBlocked({ ceExclusive: true })).toBe(false);
  });

  it('does NOT block self-hosted enterprise, whose IS_CLOUD is true but whose backend allows the install', async () => {
    const { isCeExclusiveBlocked } = await loadWith(SELF_HOSTED_ENTERPRISE);

    expect(isCeExclusiveBlocked({ ceExclusive: true })).toBe(false);
  });
});

describe('ceExclusiveFeatureKeys', () => {
  it('maps known backend codes to their i18n keys', async () => {
    const { ceExclusiveFeatureKeys } = await loadWith(MANAGED_CLOUD);

    expect(ceExclusiveFeatureKeys({ ceExclusive: true, ceExclusiveFeatures: ['CLI_AGENT', 'VECTOR_SEARCH'] }))
      .toEqual(['ceExclusiveFeatureCliAgent', 'ceExclusiveFeatureVectorSearch']);
  });

  it('drops unknown codes instead of leaking a raw backend token into the UI', async () => {
    const { ceExclusiveFeatureKeys } = await loadWith(MANAGED_CLOUD);

    expect(ceExclusiveFeatureKeys({ ceExclusive: true, ceExclusiveFeatures: ['SOME_FUTURE_CODE'] })).toEqual([]);
  });

  it('returns an empty list when the backend sent no features', async () => {
    const { ceExclusiveFeatureKeys } = await loadWith(MANAGED_CLOUD);

    expect(ceExclusiveFeatureKeys({ ceExclusive: true })).toEqual([]);
    expect(ceExclusiveFeatureKeys(null)).toEqual([]);
  });
});
