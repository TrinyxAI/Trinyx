package com.apimarketplace.common.credit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ExternalSettlementIntentDispatcherTest {

    @Test
    void retryableDeliveryRemainsDurableAndAcknowledgedDeliveryIsRemoved() {
        UUID operation = UUID.randomUUID();
        var intent = new ExternalSettlementIntentStore.Intent(
                "COMMIT_LLM", operation, "http://auth/commit", Map.of("requestHash", "h"), 0);
        FakeStore store = new FakeStore(intent);
        StubClient client = new StubClient(CreditConsumptionClient.SettlementDelivery.RETRYABLE_FAILURE);
        new ExternalSettlementIntentDispatcher(store, client).dispatch();
        assertThat(store.retried).isTrue();
        assertThat(store.acknowledged).isFalse();

        store = new FakeStore(intent);
        client = new StubClient(CreditConsumptionClient.SettlementDelivery.ACKNOWLEDGED);
        new ExternalSettlementIntentDispatcher(store, client).dispatch();
        assertThat(store.acknowledged).isTrue();
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
        boolean acknowledged;
        boolean retried;
        FakeStore(Intent intent) { this.intent = intent; }
        public boolean durable() { return true; }
        public void persist(Intent value) {}
        public List<Intent> claimDue(int limit) { return List.of(intent); }
        public void acknowledge(Intent value) { acknowledged = true; }
        public void retry(Intent value, String error) { retried = true; }
        public void dead(Intent value, String error) {}
        public void recordUnknown(UUID operationId, Map<String, Object> details) {}
    }
}
