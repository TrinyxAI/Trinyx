package com.apimarketplace.common.web;

import java.time.Duration;

/**
 * One-time nonce store used after signature validation. Implementations must make
 * consume atomic across every replica that accepts the same gateway credential.
 */
public interface GatewayNonceStore {

    /**
     * @return true only for the first observation of the provider/nonce tuple.
     */
    boolean consume(String providerId, String nonce, Duration ttl);

    /**
     * Production Cloud requires a distributed implementation.
     */
    default boolean distributed() {
        return false;
    }

    /**
     * Verifies that the backing store can actually accept replay state.
     * Called during startup when Cloud requires a distributed store.
     *
     * <p>Every implementation must define this contract explicitly. A future
     * distributed backend must not inherit a successful no-op check.</p>
     */
    void assertAvailable();
}
