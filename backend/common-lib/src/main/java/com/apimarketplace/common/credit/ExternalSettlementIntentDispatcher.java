package com.apimarketplace.common.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.LinkedHashMap;
import java.util.Map;

/** Replays producer intents when auth-service was unavailable after provider use. */
public final class ExternalSettlementIntentDispatcher {

    private static final Logger log =
            LoggerFactory.getLogger(ExternalSettlementIntentDispatcher.class);
    private final ExternalSettlementIntentStore store;
    private final CreditConsumptionClient client;

    public ExternalSettlementIntentDispatcher(
            ExternalSettlementIntentStore store, CreditConsumptionClient client) {
        this.store = store;
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${billing.external.producer-outbox-retry-ms:5000}")
    public void dispatch() {
        recoverAmbiguousDispatches();
        for (ExternalSettlementIntentStore.Intent intent : store.claimDue(25)) {
            CreditConsumptionClient.SettlementDelivery result =
                    client.deliverPersistedSettlement(intent);
            switch (result) {
                case ACKNOWLEDGED -> store.acknowledge(intent);
                case RETRYABLE_FAILURE -> store.retry(intent, "authority unavailable");
                case PERMANENT_FAILURE -> {
                    store.dead(intent, "permanent authority rejection");
                    log.error("Producer settlement moved to DEAD operationId={} action={}",
                            intent.operationId(), intent.action());
                }
            }
        }
    }

    private void recoverAmbiguousDispatches() {
        for (ExternalSettlementIntentStore.ProviderOperation operation
                : store.claimStaleProviderDispatches(25)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("requestHash", operation.requestHash());
            body.put("provider", operation.provider());
            body.put("model", operation.model());
            body.put("reason", "producer-restarted-or-provider-still-running-after-dispatch");
            ExternalSettlementIntentStore.Intent intent =
                    new ExternalSettlementIntentStore.Intent(
                            "OUTCOME_UNKNOWN", operation.operationId(),
                            operation.outcomeUnknownUrl(), body, 0,
                            operation.trustedHeaders());
            try {
                // Persist the deliverable intent first. If the process fails
                // after this write, the ordinary outbox still replays it.
                store.persist(intent);
                store.recordUnknown(operation.operationId(), body);
                log.warn("Recovered stale provider dispatch as OUTCOME_UNKNOWN operationId={}",
                        operation.operationId());
            } catch (RuntimeException failure) {
                log.error("Could not recover stale provider dispatch operationId={}: {}",
                        operation.operationId(), failure.getMessage());
            }
        }
    }
}
