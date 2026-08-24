package com.apimarketplace.common.credit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable producer-side boundary. The provider result is persisted here before
 * auth-service is contacted, closing the agent/orchestrator -> auth-service gap.
 */
public interface ExternalSettlementIntentStore {

    record Intent(String action, UUID operationId, String url,
                  Map<String, Object> body, int attempts) {
        public Intent {
            body = body == null ? Map.of() : Map.copyOf(body);
        }
        public String key() { return action + ":" + operationId; }
    }

    boolean durable();
    void persist(Intent intent);
    List<Intent> claimDue(int limit);
    void acknowledge(Intent intent);
    void retry(Intent intent, String error);
    void dead(Intent intent, String error);
    void recordUnknown(UUID operationId, Map<String, Object> details);
}
