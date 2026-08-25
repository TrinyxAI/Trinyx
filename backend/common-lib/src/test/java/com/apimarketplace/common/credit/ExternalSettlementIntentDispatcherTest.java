package com.apimarketplace.common.credit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSettlementIntentDispatcherTest {

    @Test
    void retryableDeliveryRemainsDurableAndAcknowledgedDeliveryIsRemoved() {
        UUID operation = UUID.randomUUID();
        var intent = new ExternalSettlementIntentStore.Intent(
                "COMMIT_LLM", operation, "http://auth/commit",
                Map.of("requestHash", "h"), 0);
        FakeStore store = new FakeStore(intent, null);
        StubClient client = new StubClient(
                CreditConsumptionClient.SettlementDelivery.RETRYABLE_FAILURE);
        new ExternalSettlementIntentDispatcher(store, client).dispatch();
        assertThat(store.retried).isTrue();
        assertThat(store.acknowledged).isFalse();

        store = new FakeStore(intent, null);
        client = new StubClient(CreditConsumptionClient.SettlementDelivery.ACKNOWLEDGED);
        new ExternalSettlementIntentDispatcher(store, client).dispatch();
        assertThat(store.acknowledged).isTrue();
    }

    @Test
    void staleDispatchBecomesDurableUnknownAndIsNeverReleased() {
        UUID operationId = UUID.randomUUID();
        var operation = new ExternalSettlementIntentStore.ProviderOperation(
                operationId, "request-hash", "openai", "gpt-5",
                "http://auth/proxy/" + operationId + "/outcome-unknown",
                Map.of("X-Principal-ID", UUID.randomUUID().toString()),
                "DISPATCHING", Instant.now().minusSeconds(600));
        FakeStore store = new FakeStore(null, operation);

        new ExternalSettlementIntentDispatcher(store,
                new StubClient(CreditConsumptionClient.SettlementDelivery.RETRYABLE_FAILURE))
                .dispatch();

        assertThat(store.unknownOperation).isEqualTo(operationId);
        assertThat(store.persisted).isNotNull();
        assertThat(store.persisted.action()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(store.persisted.body())
                .containsEntry("requestHash", "request-hash")
                .containsEntry("provider", "openai");
        assertThat(store.persisted.trustedHeaders())
                .containsEntry("X-Principal-ID",
                        operation.trustedHeaders().get("X-Principal-ID"));
        assertThat(store.released).isFalse();
    }

    private static final class StubClient extends CreditConsumptionClient {
        private final SettlementDelivery result;
        StubClient(SettlementDelivery result) {
            super("http://auth", false);
            this.result = result;
        }
        @Override SettlementDelivery deliverPersistedSettlement(
                ExternalSettlementIntentStore.Intent intent) { return result; }
    }

    private static final class FakeStore implements ExternalSettlementIntentStore {
        private final Intent intent;
        private final ProviderOperation staleOperation;
        boolean acknowledged;
        boolean retried;
        boolean released;
        UUID unknownOperation;
        Intent persisted;

        FakeStore(Intent intent, ProviderOperation staleOperation) {
            this.intent = intent;
            this.staleOperation = staleOperation;
        }

        public boolean durable() { return true; }
        public void persist(Intent value) { persisted = value; }
        public List<Intent> claimDue(int limit) {
            return intent == null ? List.of() : List.of(intent);
        }
        public List<ProviderOperation> claimStaleProviderDispatches(int limit) {
            return staleOperation == null ? List.of() : List.of(staleOperation);
        }
        public void acknowledge(Intent value) { acknowledged = true; }
        public void retry(Intent value, String error) { retried = true; }
        public void dead(Intent value, String error) {}
        public void recordUnknown(UUID operationId, Map<String, Object> details) {
            unknownOperation = operationId;
        }
    }
}
