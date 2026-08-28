package com.apimarketplace.common.credit;

import com.apimarketplace.common.web.GatewaySignatureV2;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("CreditConsumptionClient org scope propagation")
class CreditConsumptionClientOrgScopeTest {

    private static final String GATEWAY_SECRET = "test-gateway-secret";
    private static final String SERVICE_SECRET =
            "orchestrator-service-test-secret-at-least-32-characters";

    @Mock
    private RestTemplate restTemplate;

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void consumeCreditsForwardsActiveWorkspaceHeaders() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        bindWorkspace("org-acme", "MEMBER");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("success", true), HttpStatus.OK));

        client.consumeCredits("99", "CHAT_CONVERSATION", "conv-1",
                "deepseek", "deepseek-chat", 100, 50);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-acme");
        assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
    }

    @Test
    void consumeCreditsSignsExactSerializedBodyWithGatewayHmacV2() {
        CreditConsumptionClient client = new CreditConsumptionClient(
                "http://auth:8083", true, GATEWAY_SECRET);
        RestTemplate real = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(real).build();
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        bindWorkspace("org-acme", "MEMBER");
        server.expect(requestTo("http://auth:8083/api/credits/consume"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(request.getHeaders()))
                .andRespond(withSuccess("{\"success\":true}", MediaType.APPLICATION_JSON));

        client.consumeCredits("99", "CHAT_CONVERSATION", "conv-1",
                "deepseek", "deepseek-chat", 100, 50);

        assertV2(captured.get(), "POST", "/api/credits/consume", "99", "org-acme", "MEMBER");
        server.verify();
    }

    @Test
    void requestlessWorkflowWorkerSignsUserAndOrganizationForBindingResolution() {
        CreditConsumptionClient client = new CreditConsumptionClient(
                "http://auth:8083", true, GATEWAY_SECRET,
                "orchestrator-service", SERVICE_SECRET);
        client.setBillingAuthorityMode("external-paid-monolith");
        RestTemplate real = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(real).build();
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        UUID organizationId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        server.expect(requestTo("http://auth:8083/api/internal/cloud-credit-proxy/reserve-llm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(request.getHeaders()))
                .andRespond(withSuccess("{\"requestHash\":\"" + "a".repeat(64) + "\"}",
                        MediaType.APPLICATION_JSON));

        AtomicReference<CreditConsumptionClient.ExternalReservationResult> result =
                new AtomicReference<>();
        TenantResolver.runWithOrgScope(organizationId.toString(), () ->
                result.set(client.reserveExternalLlm(99L, operationId,
                        "cloudWebSearchRelay", "BROWSER_AGENT_EXECUTION",
                        "openai", "gpt", 100, 50)));

        assertThat(result.get().success()).isTrue();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
        assertThat(captured.get().getFirst("X-User-ID")).isEqualTo("99");
        assertThat(captured.get().getFirst("X-Organization-ID"))
                .isEqualTo(organizationId.toString());
        assertThat(captured.get().getFirst("X-Principal-ID")).isNull();
        assertServiceV2(captured.get(), "POST",
                "/api/internal/cloud-credit-proxy/reserve-llm",
                "99", organizationId.toString(), "");
        server.verify();
    }

    @Test
    void scopeCommitSignsGatewayHmacV2WithoutUserHeader() {
        CreditConsumptionClient client = new CreditConsumptionClient(
                "http://auth:8083", true, GATEWAY_SECRET);
        RestTemplate real = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(real).build();
        AtomicReference<HttpHeaders> captured = new AtomicReference<>();
        server.expect(requestTo("http://auth:8083/api/credits/markup/scope-commit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> captured.set(request.getHeaders()))
                .andRespond(withSuccess("{\"outcome\":\"COMMITTED\"}", MediaType.APPLICATION_JSON));

        assertThat(client.scopeCommit("reservation-1", BigDecimal.ONE, "openai", "gpt-4.1"))
                .isEqualTo("COMMITTED");

        assertV2(captured.get(), "POST", "/api/credits/markup/scope-commit", "", "", "");
        server.verify();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void checkChatBudgetCacheIsIsolatedByActiveWorkspace() {
        CreditConsumptionClient client = clientWithMockRestTemplate();
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity(Map.of("allowed", true), HttpStatus.OK))
                .thenThrow(new ResourceAccessException("auth-service down"));

        bindWorkspace("org-acme", "MEMBER");
        boolean acmeAllowed = client.checkChatBudget("99", "deepseek", "deepseek-chat", 100, 50);
        bindWorkspace("org-personal", "OWNER");
        boolean personalAllowed = client.checkChatBudget("99", "deepseek", "deepseek-chat", 100, 50);

        assertThat(acmeAllowed).isTrue();
        assertThat(personalAllowed).isFalse();
    }

    private void assertV2(HttpHeaders headers, String method, String target,
                          String userId, String orgId, String orgRole) {
        assertThat(headers.getFirst("X-Gateway-Signature-Version")).isEqualTo("2");
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                headers.getFirst("X-Gateway-Timestamp"),
                headers.getFirst("X-Gateway-Nonce"),
                method,
                target,
                headers.getFirst("X-Gateway-Body-SHA256"),
                "internal-credit-client",
                userId,
                "",
                "",
                orgId,
                orgRole,
                "",
                "");
        assertThat(headers.getFirst("X-Gateway-Secret"))
                .isEqualTo(GatewaySignatureV2.sign(GATEWAY_SECRET, context));
    }

    private void assertServiceV2(HttpHeaders headers, String method, String target,
                                 String userId, String orgId, String orgRole) {
        assertThat(headers.getFirst("X-Gateway-Signature-Version")).isEqualTo("2");
        assertThat(headers.getFirst("X-Provider-ID")).isEqualTo("orchestrator-service");
        GatewaySignatureV2.Context context = new GatewaySignatureV2.Context(
                headers.getFirst("X-Gateway-Timestamp"),
                headers.getFirst("X-Gateway-Nonce"),
                method,
                target,
                headers.getFirst("X-Gateway-Body-SHA256"),
                "orchestrator-service",
                userId,
                "",
                "",
                orgId,
                orgRole,
                "",
                "");
        assertThat(headers.getFirst("X-Gateway-Secret"))
                .isEqualTo(GatewaySignatureV2.sign(SERVICE_SECRET, context));
    }

    private CreditConsumptionClient clientWithMockRestTemplate() {
        CreditConsumptionClient client = new CreditConsumptionClient("http://auth:8083", true);
        ReflectionTestUtils.setField(client, "restTemplate", restTemplate);
        return client;
    }

    private void bindWorkspace(String orgId, String orgRole) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Organization-ID", orgId);
        request.addHeader("X-Organization-Role", orgRole);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
