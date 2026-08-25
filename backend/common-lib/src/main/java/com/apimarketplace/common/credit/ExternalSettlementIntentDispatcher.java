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
        try {
            recoverAmbiguousDispatches();
        } catch (RuntimeException failure) {
            log.error("Could not scan stale provider dispatches; continuing settlement outbox: {}",
                    failure.getMessage());
        }
        for (ExternalSettlementIntentStore.Intent intent : store.claimDue(25)) {
            try {
                CreditConsumptionClient.SettlementDelivery result =
                        client.deliverPersistedSettlement(intent);
                switch (result) {
                    case ACKNOWLEDGED -> store.acknowledge(intent);
                    case DURABLY_QUEUED -> store.retry(intent,
                            "auth-service accepted durable responsibility; awaiting authority decision");
                    case RETRYABLE_FAILURE -> store.retry(intent, "authority unavailable");
                    case PERMANENT_FAILURE -> {
                        store.dead(intent, "permanent authority rejection");
                        log.error("Producer settlement moved to DEAD operationId={} action={}",
                                intent.operationId(), intent.action());
                    }
                }
            } catch (RuntimeException failure) {
                // One corrupted/stale message must not starve later terminal
                // reconciliation intents that were claimed in the same batch.
                log.error("Could not process producer settlement intent operationId={} action={}: {}",
                        intent.operationId(), intent.action(), failure.getMessage());
            }
        }
    }

    private void recoverAmbiguousDispatches() {
        for (ExternalSettlementIntentStore.ClaimedProviderOperation claimed
                : store.claimStaleProviderDispatches(25)) {
            ExternalSettlementIntentStore.ProviderOperation operation =
                    claimed.operation();
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
                // The Redis store atomically verifies this recovery lease,
                // persists the deliverable intent and records UNKNOWN.
                if (store.recordRecoveredUnknown(claimed, intent)) {
                    log.warn("Recovered stale provider dispatch as OUTCOME_UNKNOWN operationId={}",
                            operation.operationId());
                } else {
                    log.debug("Skipped stale provider recovery after losing lease operationId={}",
                            operation.operationId());
                }
            } catch (RuntimeException failure) {
                log.error("Could not recover stale provider dispatch operationId={}: {}",
                        operation.operationId(), failure.getMessage());
            }
        }
    }
}
