package com.apimarketplace.common.storage.url;

/**
 * Shape rules for a storage key, in one place because two different guards depend on them
 * meaning the same thing.
 *
 * <p>Keys are namespaced {@code "<tenantId>/..."} (or {@code "_publications/<pubId>/..."}),
 * and the platform's authorization boundary is a prefix test on that first segment
 * ({@code InternalFileController.isKeyOwnedByTenant}, {@code ShowcaseFileRefRewriter}).
 * A prefix test is only a boundary while the rest of the key cannot walk back out of it:
 * {@code 8/../3/private.pdf} starts with {@code 8/} and names tenant 3. Nothing downstream
 * normalizes - not the copy endpoint, not the signed proxy - and whether the escape lands
 * depends on the storage backend, which is not a question worth leaving open.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    /**
     * @return true when the key is safe to prefix-test: non-blank, relative, no empty
     *         segment, and no {@code .} or {@code ..} segment
     */
    public static boolean isWellFormed(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.startsWith("/") || key.contains("//")) return false;
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    /**
     * The owning namespace: the first path segment, which is a tenant id for a user file and
     * the literal {@code _publications} for a publication-owned one.
     *
     * @return the first segment, or {@code null} when the key is not well formed
     */
    public static String namespaceOf(String key) {
        if (!isWellFormed(key)) return null;
        int slash = key.indexOf('/');
        return slash <= 0 ? null : key.substring(0, slash);
    }
}
