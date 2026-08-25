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

    @Test
    void asyncSettlementUsesIdentityCapturedWithTheOperation() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth", false);
        FakeStore store = new FakeStore();
        UUID principalId = UUID.randomUUID();
        store.trustedHeaders = Map.of("X-Principal-ID", principalId.toString(),
                "X-Install-ID", UUID.randomUUID().toString());
        client.setSettlementIntentStore(store);
        UUID operationId = UUID.randomUUID();

        client.recordExternalOutcomeUnknown(operationId, "hash", "openai", "gpt", "timeout");

        assertThat(store.intent.trustedHeaders())
                .containsEntry("X-Principal-ID", principalId.toString())
                .containsKey("X-Install-ID");
    }

    @Test
    void providerCallIsBlockedUnlessDispatchStateWasPersisted() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth", false);
        FakeStore store = new FakeStore();
        client.setSettlementIntentStore(store);
        client.setBillingAuthorityMode("external-paid-monolith");
        UUID operationId = UUID.randomUUID();

        store.dispatchAccepted = false;
        assertThat(client.markExternalProviderDispatching(operationId)).isFalse();

        store.dispatchAccepted = true;
        assertThat(client.markExternalProviderDispatching(operationId)).isTrue();
        assertThat(store.dispatchedOperation).isEqualTo(operationId);
    }

    private static final class FakeStore implements ExternalSettlementIntentStore {
        UUID unknownOperation;
        Map<String, Object> details;
        boolean persistedDelivery;
        Intent intent;
        boolean dispatchAccepted;
        UUID dispatchedOperation;
        Map<String, String> trustedHeaders = Map.of();
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
        public boolean markProviderDispatching(UUID operationId) {
            dispatchedOperation = operationId;
            return dispatchAccepted;
        }
        public Map<String, String> trustedHeaders(UUID operationId) {
            return trustedHeaders;
        }
    }
}
