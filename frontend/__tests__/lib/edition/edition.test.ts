/**
 * Tests for the build-time edition resolver.
 *
 * The resolver evaluates env vars **once at module load** (via the `EDITION`
 * top-level const). Each test must therefore: (a) stub the env vars, (b) reset
 * Vitest's module cache, (c) dynamically import the module so the resolver
 * re-runs against the stubbed values.
 */

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

async function importEdition() {
    return await import('../../../lib/edition/edition');
}

describe('edition resolver - precedence', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        vi.resetModules();
        warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.unstubAllEnvs();
        warnSpy.mockRestore();
    });

    it('APP_EDITION=ce → ce, no warn', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION, IS_CE, IS_CLOUD } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(IS_CE).toBe(true);
        expect(IS_CLOUD).toBe(false);
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=paid-monolith + AUTH_MODE=embedded → ce, no warn', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'paid-monolith');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=cloud + AUTH_MODE=oidc → cloud, no warn', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('cloud');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=cloud + AUTH_MODE=embedded → ce (conflict warn)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).toHaveBeenCalledTimes(1);
        expect((warnSpy.mock.calls[0][0] as string)).toContain('conflicts');
    });

    it('APP_EDITION unset + AUTH_MODE=embedded → ce (legacy shim)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION unset + AUTH_MODE unset → cloud (default)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('cloud');
        expect(warnSpy).not.toHaveBeenCalled();
    });

    it('APP_EDITION=invalid + AUTH_MODE=embedded → ce + warn on invalid', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'enterprise');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
        expect(warnSpy).toHaveBeenCalledTimes(1);
        expect((warnSpy.mock.calls[0][0] as string)).toContain('Invalid');
    });

    it('APP_EDITION=CE (uppercase) → ce (case-insensitive)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'CE');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
    });

    it('APP_EDITION="  ce  " (whitespace) → ce (trimmed)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '  ce  ');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { EDITION } = await importEdition();
        expect(EDITION).toBe('ce');
    });
});

/**
 * IS_MANAGED_CLOUD mirrors the backend's AppEditionProvider.isManagedCloud(),
 * which is NOT the complement of IS_CE: a self-hosted-enterprise deployment runs
 * keycloak auth, so the binary EDITION resolves to 'cloud' while the deployment
 * is self-hosted. Any UI gate copied from a backend isManagedCloud() rule must
 * read this constant, or it hides a capability from self-hosted-enterprise
 * customers whose own API allows it.
 */
describe('IS_MANAGED_CLOUD', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        vi.resetModules();
        warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.unstubAllEnvs();
        warnSpy.mockRestore();
    });

    it('APP_EDITION=cloud → managed cloud', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { IS_MANAGED_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(true);
    });

    it('APP_EDITION=dedicated-cloud → managed cloud (the backend treats it the same)', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'dedicated-cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { IS_MANAGED_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(true);
    });

    it('APP_EDITION=ce → NOT managed cloud', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { IS_MANAGED_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(false);
    });

    it('APP_EDITION=self-hosted-enterprise → NOT managed cloud, even though IS_CLOUD is true', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'self-hosted-enterprise');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { IS_MANAGED_CLOUD, IS_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(false);
        // The binary EDITION cannot express this deployment, hence the separate constant.
        expect(IS_CLOUD).toBe(true);
    });

    it('accepts the same aliases as the backend AppEdition.fromConfig, including underscores', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'SELF_HOSTED');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { IS_MANAGED_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(false);
    });

    it('APP_EDITION=cloud + AUTH_MODE=embedded → NOT managed cloud (auth path wins, as in resolve())', async () => {
        // A misconfigured self-hosted install reads as CE everywhere else; this
        // constant must not be the one place that still calls it cloud and hides
        // capabilities its own backend allows.
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { IS_MANAGED_CLOUD, IS_CE } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(false);
        expect(IS_CE).toBe(true);
    });

    it('unset APP_EDITION falls back to AUTH_MODE: embedded → not managed cloud', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        const { IS_MANAGED_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(false);
    });

    it('unset APP_EDITION falls back to AUTH_MODE: oidc → managed cloud', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', '');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        const { IS_MANAGED_CLOUD } = await importEdition();
        expect(IS_MANAGED_CLOUD).toBe(true);
    });
});

describe('useEdition hook', () => {
    beforeEach(() => {
        vi.resetModules();
    });

    afterEach(() => {
        vi.unstubAllEnvs();
    });

    it('returns the resolved EDITION constant synchronously', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        const { useEdition } = await import('../../../lib/edition/useEdition');
        expect(useEdition()).toBe('ce');
    });
});


describe('billing capability', () => {
    let warnSpy: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        vi.resetModules();
        warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    });

    afterEach(() => {
        vi.unstubAllEnvs();
        warnSpy.mockRestore();
    });

    it('keeps legacy CE on embedded auth when AUTH_MODE is absent', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', '');
        vi.stubEnv('NEXT_PUBLIC_BILLING_ENABLED', '');

        const { IS_CE, IS_EMBEDDED_AUTH, IS_COMMUNITY_EDITION } =
            await importEdition();

        expect(IS_CE).toBe(true);
        expect(IS_EMBEDDED_AUTH).toBe(true);
        expect(IS_COMMUNITY_EDITION).toBe(true);
    });

    it('keeps embedded authentication while enabling paid-monolith billing', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'paid-monolith');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        vi.stubEnv('NEXT_PUBLIC_BILLING_ENABLED', 'true');

        const {
            IS_BILLING_ENABLED,
            IS_COMMUNITY_EDITION,
            IS_EMBEDDED_AUTH,
            IS_PAID_MONOLITH,
        } = await importEdition();

        expect(IS_EMBEDDED_AUTH).toBe(true);
        expect(IS_BILLING_ENABLED).toBe(true);
        expect(IS_PAID_MONOLITH).toBe(true);
        expect(IS_COMMUNITY_EDITION).toBe(false);
    });

    it('keeps free CE billing disabled by default', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'ce');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'embedded');
        vi.stubEnv('NEXT_PUBLIC_BILLING_ENABLED', '');

        const { IS_BILLING_ENABLED, IS_COMMUNITY_EDITION, IS_PAID_MONOLITH } =
            await importEdition();

        expect(IS_BILLING_ENABLED).toBe(false);
        expect(IS_PAID_MONOLITH).toBe(false);
        expect(IS_COMMUNITY_EDITION).toBe(true);
    });

    it('keeps managed cloud billing enabled when no explicit flag is provided', async () => {
        vi.stubEnv('NEXT_PUBLIC_APP_EDITION', 'cloud');
        vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'oidc');
        vi.stubEnv('NEXT_PUBLIC_BILLING_ENABLED', '');

        const { IS_BILLING_ENABLED, IS_EMBEDDED_AUTH, IS_PAID_MONOLITH } =
            await importEdition();

        expect(IS_EMBEDDED_AUTH).toBe(false);
        expect(IS_BILLING_ENABLED).toBe(true);
        expect(IS_PAID_MONOLITH).toBe(false);
    });
});
