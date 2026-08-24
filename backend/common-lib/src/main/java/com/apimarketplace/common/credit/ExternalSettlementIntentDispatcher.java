package com.apimarketplace.common.credit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

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
}
