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
                  Map<String, String> trustedHeaders) {
        public Intent {
            body = body == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(body));
            trustedHeaders = trustedHeaders == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(
                            new java.util.LinkedHashMap<>(trustedHeaders));
        }

        /** Backward-compatible constructor for native/tests that do not carry gateway context. */
        public Intent(String action, UUID operationId, String url,
                      Map<String, Object> body, int attempts) {
            this(action, operationId, url, body, attempts, Map.of());
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

    boolean durable();
    void persist(Intent intent);
    List<Intent> claimDue(int limit);
    void acknowledge(Intent intent);
    void retry(Intent intent, String error);
    void dead(Intent intent, String error);
    void recordUnknown(UUID operationId, Map<String, Object> details);

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
    default List<ProviderOperation> claimStaleProviderDispatches(int limit) {
        return List.of();
    }

    /** Persist the producer-side state transition retained for audit/recovery. */
    default void markProviderState(UUID operationId, String state) {
        // Compatibility no-op.
    }

    /** Trusted identity context captured at reservation time for async retries. */
    default Map<String, String> trustedHeaders(UUID operationId) {
        return Map.of();
    }
}
