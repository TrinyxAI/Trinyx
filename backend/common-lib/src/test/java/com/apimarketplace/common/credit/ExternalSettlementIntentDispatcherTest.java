package com.apimarketplace.common.credit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
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
    void durableHandoffIsRetriedUntilAFinalResponseAcknowledgesIt() {
        UUID operation = UUID.randomUUID();
        var intent = new ExternalSettlementIntentStore.Intent(
                "COMMIT_LLM", operation, "http://auth/commit",
                Map.of("requestHash", "h"), 0);
        FakeStore store = new FakeStore(intent, null);

        new ExternalSettlementIntentDispatcher(store,
                new StubClient(CreditConsumptionClient.SettlementDelivery.DURABLY_QUEUED))
                .dispatch();

        assertThat(store.retried).isTrue();
        assertThat(store.acknowledged).isFalse();
        assertThat(store.dead).isFalse();

        store.retried = false;
        new ExternalSettlementIntentDispatcher(store,
                new StubClient(CreditConsumptionClient.SettlementDelivery.ACKNOWLEDGED))
                .dispatch();

        assertThat(store.acknowledged).isTrue();
        assertThat(store.retried).isFalse();
    }

    @Test
    void onePoisonIntentCannotStarveALaterTerminalInTheSameBatch() {
        UUID operation = UUID.randomUUID();
        var unknown = new ExternalSettlementIntentStore.Intent(
                "OUTCOME_UNKNOWN", operation, "http://auth/outcome-unknown",
                Map.of("requestHash", "h"), 0);
        var release = new ExternalSettlementIntentStore.Intent(
                "RELEASE", operation, "http://auth/release",
                Map.of("requestHash", "h"), 0);
        FakeStore store = new FakeStore(List.of(unknown, release), null, true);
        store.throwOnUnknownAcknowledgement = true;

        new ExternalSettlementIntentDispatcher(store,
                new StubClient(CreditConsumptionClient.SettlementDelivery.ACKNOWLEDGED))
                .dispatch();

        assertThat(store.acknowledgementAttempts).isEqualTo(2);
        assertThat(store.acknowledgedActions).containsExactly("RELEASE");
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
        private final List<Intent> intents;
        private final ProviderOperation staleOperation;
        boolean acknowledged;
        boolean retried;
        boolean released;
        boolean dead;
        boolean throwOnUnknownAcknowledgement;
        int acknowledgementAttempts;
        final List<String> acknowledgedActions = new ArrayList<>();
        UUID unknownOperation;
        Intent persisted;

        FakeStore(Intent intent, ProviderOperation staleOperation) {
            this(intent == null ? List.of() : List.of(intent), staleOperation, true);
        }

        FakeStore(List<Intent> intents, ProviderOperation staleOperation, boolean batch) {
            this.intents = intents;
            this.staleOperation = staleOperation;
        }

        public boolean durable() { return true; }
        public void persist(Intent value) { persisted = value; }
        public List<Intent> claimDue(int limit) {
            return intents;
        }
        public List<ProviderOperation> claimStaleProviderDispatches(int limit) {
            return staleOperation == null ? List.of() : List.of(staleOperation);
        }
        public void acknowledge(Intent value) {
            acknowledgementAttempts++;
            if (throwOnUnknownAcknowledgement
                    && "OUTCOME_UNKNOWN".equals(value.action())) {
                throw new IllegalStateException("simulated poison intent");
            }
            acknowledged = true;
            acknowledgedActions.add(value.action());
        }
        public void retry(Intent value, String error) { retried = true; }
        public void dead(Intent value, String error) { dead = true; }
        public void recordUnknown(UUID operationId, Map<String, Object> details) {
            unknownOperation = operationId;
        }
    }
}
