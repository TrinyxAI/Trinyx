package com.apimarketplace.common.credit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CreditConsumptionClientAmbiguousOutcomeTest {

    @Test
    void ambiguousProviderOutcomeIsPersistedAndNeverConvertedToRelease() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth", false);
        FakeStore store = new FakeStore();
        client.setSettlementIntentStore(store);
        UUID operationId = UUID.randomUUID();

        client.recordExternalOutcomeUnknown(operationId, "hash", "openai", "gpt", "timeout");

        assertThat(store.unknownOperation).isEqualTo(operationId);
        assertThat(store.details)
                .containsEntry("requestHash", "hash")
                .containsEntry("reason", "timeout");
        assertThat(store.persistedDelivery).isTrue();
        assertThat(store.intent.action()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(store.intent.operationId()).isEqualTo(operationId);
        assertThat(store.intent.url()).endsWith(
                "/" + operationId + "/outcome-unknown");
    }

    private static final class FakeStore implements ExternalSettlementIntentStore {
        UUID unknownOperation;
        Map<String, Object> details;
        boolean persistedDelivery;
        Intent intent;
        public boolean durable() { return true; }
        public void persist(Intent intent) {
            persistedDelivery = true;
            this.intent = intent;
        }
        public List<Intent> claimDue(int limit) { return List.of(); }
        public void acknowledge(Intent intent) {}
        public void retry(Intent intent, String error) {}
        public void dead(Intent intent, String error) {}
        public void recordUnknown(UUID operationId, Map<String, Object> value) {
            unknownOperation = operationId;
            details = value;
        }
    }
}
