/**
 * Frontend single source of truth for the deployment edition (CE vs Cloud).
 *
 * Resolution is **frozen at build time**: Next.js inlines `process.env.NEXT_PUBLIC_*`
 * into both server and client bundles during `next build`. There is no runtime
 * variance, no per-request variance, no per-user variance.
 *
 * Precedence (matches backend AppEditionProvider semantics):
 *
 *   APP_EDITION  AUTH_MODE         Result
 *   -----------  ----------------  ------
 *   ce           any               ce
 *   cloud        oidc / empty      cloud
 *   cloud        embedded          ce      (warn - auth path wins defensively)
 *   unset/empty  embedded          ce      (legacy shim - kept for one release)
 *   unset/empty  oidc / empty      cloud
 *   invalid str  embedded          ce      (warn on invalid; fall through to AUTH_MODE)
 *   invalid str  other             cloud   (warn)
 *
 * Both env vars are trimmed and lowercased before comparison.
 */

export type Edition = 'ce' | 'cloud';

function resolve(): Edition {
    const explicit = (process.env.NEXT_PUBLIC_APP_EDITION ?? '').trim().toLowerCase();
    const auth = (process.env.NEXT_PUBLIC_AUTH_MODE ?? '').trim().toLowerCase();

    if (explicit === 'ce' || explicit === 'cloud') {
        if (explicit === 'cloud' && auth === 'embedded') {
            // eslint-disable-next-line no-console
            console.warn(
                '[edition] NEXT_PUBLIC_APP_EDITION=cloud conflicts with NEXT_PUBLIC_AUTH_MODE=embedded - '
                + 'resolving to "ce" (auth path wins defensively).',
            );
            return 'ce';
        }
        return explicit;
    }

    if (explicit) {
        // eslint-disable-next-line no-console
        console.warn(
            `[edition] Invalid NEXT_PUBLIC_APP_EDITION="${explicit}" - falling back to NEXT_PUBLIC_AUTH_MODE.`,
        );
    }

    return auth === 'embedded' ? 'ce' : 'cloud';
}

/** Frozen at build time. */
export const EDITION: Edition = resolve();

/** Convenience constants - prefer over inline comparisons. */
export const IS_CE = EDITION === 'ce';
export const IS_CLOUD = EDITION === 'cloud';

/**
 * Deployment values the BACKEND treats as self-hosted, i.e. the ones for which
 * `AppEditionProvider.isSelfHosted()` is true. Aliases mirror
 * `AppEdition.fromConfig` so the two resolvers accept the same spellings.
 */
const SELF_HOSTED_VALUES = new Set([
    'ce', 'ce-free', 'community', 'community-edition',
    'self-hosted-enterprise', 'self-hosted', 'selfhosted-enterprise', 'selfhosted',
    'enterprise-self-hosted', 'paid-monolith', 'monolith-paid', 'paid-self-hosted',
]);

/** Values that mean managed cloud (`AppEditionProvider.isManagedCloud()`). */
const MANAGED_CLOUD_VALUES = new Set([
    'cloud', 'saas', 'dedicated-cloud', 'dedicated', 'dedicated-instance',
]);

/**
 * True only on a MANAGED-cloud deployment (CLOUD / DEDICATED_CLOUD), matching
 * the backend's `AppEditionProvider.isManagedCloud()`.
 *
 * <p>{@link IS_CLOUD} is NOT the same thing: {@link EDITION} is binary, so a
 * SELF-HOSTED ENTERPRISE deployment (`app.edition=self-hosted-enterprise`,
 * `auth.mode=keycloak`) resolves to `'cloud'` there. Any UI that mirrors a
 * backend rule keyed on `isManagedCloud()` must use THIS constant, or it will
 * hide a capability from self-hosted-enterprise customers whose own API allows
 * it (that is exactly what happened with the CE-exclusive install gate).
 */
function resolveManagedCloud(): boolean {
    const explicit = (process.env.NEXT_PUBLIC_APP_EDITION ?? '')
        .trim().toLowerCase().replace(/_/g, '-');
    const auth = (process.env.NEXT_PUBLIC_AUTH_MODE ?? '').trim().toLowerCase();
    if (SELF_HOSTED_VALUES.has(explicit)) return false;
    if (MANAGED_CLOUD_VALUES.has(explicit)) {
        // Same defensive rule as resolve() above: embedded auth means the
        // deployment is self-hosted whatever APP_EDITION claims. Without this,
        // a misconfigured install (APP_EDITION=cloud + AUTH_MODE=embedded)
        // would read as CE everywhere else yet hide capabilities here.
        return auth !== 'embedded';
    }
    // Unset / unrecognized: fall back to the binary resolution above, which
    // reads AUTH_MODE (embedded => CE => not managed cloud).
    return EDITION === 'cloud';
}

export const IS_MANAGED_CLOUD = resolveManagedCloud();


/**
 * Authentication topology is independent from commercial capabilities.
 * Paid monolith keeps the CE embedded JWT flow while enabling local Stripe billing.
 */
export const IS_EMBEDDED_AUTH =
    (process.env.NEXT_PUBLIC_AUTH_MODE ?? '').trim().toLowerCase() === 'embedded';

/**
 * Local billing capability. Managed Cloud always has billing; a self-hosted build
 * opts in explicitly for the paid-monolith deployment.
 */
function resolveBillingEnabled(): boolean {
    const configured = (process.env.NEXT_PUBLIC_BILLING_ENABLED ?? '').trim().toLowerCase();
    if (configured === 'true') return true;
    if (configured === 'false') return false;
    return IS_CLOUD;
}

export const IS_BILLING_ENABLED = resolveBillingEnabled();
export const IS_PAID_MONOLITH = IS_CE && IS_EMBEDDED_AUTH && IS_BILLING_ENABLED;
export const IS_COMMUNITY_EDITION = IS_CE && !IS_BILLING_ENABLED;
