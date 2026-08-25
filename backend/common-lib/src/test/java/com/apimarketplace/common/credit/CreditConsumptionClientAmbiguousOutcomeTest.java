package com.apimarketplace.common.credit;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

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
    void providerCallIsBlockedUnlessLocalJournalAndPaidAuthorityBothAcknowledge() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth", true);
        FakeStore store = new FakeStore();
        client.setSettlementIntentStore(store);
        client.setBillingAuthorityMode("external-paid-monolith");
        UUID operationId = UUID.randomUUID();
        store.operation = new ExternalSettlementIntentStore.ProviderOperation(
                operationId, "a".repeat(64), "openai", "gpt",
                "http://auth/api/internal/cloud-credit-proxy/" + operationId
                        + "/outcome-unknown",
                Map.of("X-Principal-ID", UUID.randomUUID().toString()),
                "RESERVED", Instant.now());

        store.dispatchAccepted = false;
        assertThat(client.markExternalProviderDispatching(operationId)).isFalse();

        RestTemplate http = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(once(), requestTo("http://auth/api/internal/cloud-credit-proxy/"
                        + operationId + "/dispatching"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        store.dispatchAccepted = true;
        assertThat(client.markExternalProviderDispatching(operationId)).isTrue();
        assertThat(store.dispatchedOperation).isEqualTo(operationId);
        server.verify();
    }

    private static final class FakeStore implements ExternalSettlementIntentStore {
        UUID unknownOperation;
        Map<String, Object> details;
        boolean persistedDelivery;
        Intent intent;
        boolean dispatchAccepted;
        UUID dispatchedOperation;
        Map<String, String> trustedHeaders = Map.of();
        ProviderOperation operation;
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
        public ProviderOperation providerOperation(UUID operationId) {
            return operation;
        }
        public Map<String, String> trustedHeaders(UUID operationId) {
            return trustedHeaders;
        }
    }
}
