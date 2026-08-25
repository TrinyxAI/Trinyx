package com.apimarketplace.common.credit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable producer-side boundary. Provider dispatch state is recorded before
 * the external call and settlement intent is persisted before auth-service is
 * contacted. This makes an ambiguous provider outcome observable and replayable
 * across application restarts.
 */
public interface ExternalSettlementIntentStore {

    record Intent(String action, UUID operationId, String url,
                  Map<String, Object> body, int attempts,
                  Map<String, String> trustedHeaders,
                  String claimToken) {
        public Intent {
            body = body == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(body));
            trustedHeaders = trustedHeaders == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(trustedHeaders));
        }

        /** Constructor for a durable, not-yet-claimed intent. */
        public Intent(String action, UUID operationId, String url,
                      Map<String, Object> body, int attempts,
                      Map<String, String> trustedHeaders) {
            this(action, operationId, url, body, attempts, trustedHeaders, null);
        }

        /** Backward-compatible constructor for native/tests that do not carry gateway context. */
        public Intent(String action, UUID operationId, String url,
                      Map<String, Object> body, int attempts) {
            this(action, operationId, url, body, attempts, Map.of(), null);
        }

        public Intent claimedBy(String token) {
            return new Intent(action, operationId, url, body, attempts,
                    trustedHeaders, token);
        }

        public String key() { return action + ":" + operationId; }
    }

    record ProviderOperation(UUID operationId, String requestHash,
                             String provider, String model, String outcomeUnknownUrl,
                             Map<String, String> trustedHeaders,
                             String state, Instant updatedAt) {
        public ProviderOperation {
            trustedHeaders = trustedHeaders == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(trustedHeaders));
        }
    }

    /** A stale DISPATCHING recovery item plus its fenced lease ownership. */
    record ClaimedProviderOperation(ProviderOperation operation, String claimToken) {
        public ClaimedProviderOperation {
            if (operation == null || claimToken == null || claimToken.isBlank()) {
                throw new IllegalArgumentException(
                        "claimed provider operation requires operation and claim token");
            }
        }
    }

    boolean durable();
    void persist(Intent intent);

    /**
     * Claims one live intent for immediate delivery. A null result means another
     * worker owns the current lease; callers must leave the durable payload alone.
     */
    default Intent claim(Intent intent) {
        return intent.claimedBy(UUID.randomUUID().toString());
    }

    List<Intent> claimDue(int limit);
    void acknowledge(Intent intent);
    void retry(Intent intent, String error);
    void dead(Intent intent, String error);
    void recordUnknown(UUID operationId, Map<String, Object> details);

    /**
     * Records UNKNOWN from the stale-DISPATCHING recovery worker only while it
     * still owns the lease returned by {@link #claimStaleProviderDispatches(int)}.
     * A false result means the lease was lost and no recovery state was mutated.
     */
    default boolean recordRecoveredUnknown(
            ClaimedProviderOperation claimed, Map<String, Object> details) {
        recordUnknown(claimed.operation().operationId(), details);
        return true;
    }

    /**
     * Registers the immutable producer operation immediately after the
     * authoritative reservation. Implementations must reject a reused operationId
     * whose request hash/provider/model differs.
     */
    default void registerProviderOperation(ProviderOperation operation) {
        // Compatibility for native mode/test stores. External production mode
        // requires a durable implementation and verifies it at startup.
    }

    /**
     * Atomically records DISPATCHING before the provider network call. A false
     * result means the caller must not invoke the provider.
     */
    default boolean markProviderDispatching(UUID operationId) {
        return true;
    }

    /**
     * Operations left DISPATCHING past the bounded safety window. They are not
     * released: the dispatcher turns them into OUTCOME_UNKNOWN authority intents.
     */
    default List<ClaimedProviderOperation> claimStaleProviderDispatches(int limit) {
        return List.of();
    }

    /** Immutable provider metadata captured with the authoritative reservation. */
    default ProviderOperation providerOperation(UUID operationId) {
        return null;
    }

    /** Trusted identity context captured at reservation time for async retries. */
    default Map<String, String> trustedHeaders(UUID operationId) {
        return Map.of();
    }
}
