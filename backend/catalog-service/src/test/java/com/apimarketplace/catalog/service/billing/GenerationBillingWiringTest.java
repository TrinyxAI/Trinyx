package com.apimarketplace.catalog.service.billing;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.CeCatalogRelayRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.CatalogToolQueryService;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.IntentResolutionManager;
import com.apimarketplace.catalog.service.NextActionBuilder;
import com.apimarketplace.catalog.service.ResponseCache;
import com.apimarketplace.catalog.service.ResponseShaper;
import com.apimarketplace.catalog.service.ToolContextService;
import com.apimarketplace.catalog.service.ToolExecutionManager;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.catalog.service.exception.InsufficientCreditsException;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.catalog.service.http.CredentialModeContext;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService.RelayResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.CreditConsumptionClient.ScopeReserveResult;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.FrozenMarkupDto;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.apimarketplace.credential.client.dto.PricingVersionDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who actually gets debited for a generation, through the REAL wiring.
 *
 * <p>The pieces that decide the money question sit in three different classes
 * ({@link CeCatalogRelayService} owns its own reserve/commit, {@link
 * ToolExecutionManager} builds the billing scope, {@link
 * CatalogToolBillingService} decides whether the call may go out at all), and
 * the regression this pins was invisible to each of them alone: the relay
 * reserved correctly, the billing service refused correctly, and the
 * combination charged the customer nothing and delivered nothing. So the chain
 * is assembled here for real, with only the credit ledger and the upstream HTTP
 * call mocked, and the assertions are the debits.
 *
 * <p><b>The invariant:</b> exactly one party charges for a relayed generation,
 * and it is never zero parties.
 */
@DisplayName("generation billing, end to end through the real execution path")
class GenerationBillingWiringTest {

    private static final long CLOUD_USER_ID = 42L;
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String API_SLUG = "seedance";
    private static final String TOOL_SLUG = "create-video-task";
    private static final String COMPOSITE_SLUG = API_SLUG + "/" + TOOL_SLUG;
    private static final UUID API_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final long CREDENTIAL_ID = 77L;
    private static final long PRICING_VERSION_ID = 5L;
    /** What the relay charges for one relayed video, in credits. */
    private static final BigDecimal RELAY_MARKUP = new BigDecimal("300");

    private ApiRepository apiRepository;
    private ApiToolRepository apiToolRepository;
    private CredentialClient credentialClient;
    private CreditConsumptionClient creditClient;
    private ApiService apiService;
    private ToolContextService toolContextService;

    private CatalogV1Service catalogV1Service;
    private CeCatalogRelayService relayService;

    @BeforeEach
    void setUp() {
        apiRepository = mock(ApiRepository.class);
        apiToolRepository = mock(ApiToolRepository.class);
        credentialClient = mock(CredentialClient.class);
        creditClient = mock(CreditConsumptionClient.class);
        apiService = mock(ApiService.class);
        toolContextService = mock(ToolContextService.class);

        CatalogToolBillingService billingService = new CatalogToolBillingService(
                creditClient, credentialClient, apiToolRepository, apiRepository, true);

        ToolExecutionManager manager = new ToolExecutionManager(
                toolContextService,
                apiService,
                new ObjectMapper(),
                new ResponseShaper(),
                mock(NextActionBuilder.class),
                mock(ResponseCache.class),
                mock(ToolNextHintRepository.class),
                mock(ToolResponseService.class),
                mock(ToolExecutionOrchestrator.class),
                mock(BinaryResponseHandler.class),
                billingService,
                credentialClient,
                apiRepository,
                mock(CeCatalogCloudRelay.class));
        // Field-injected in production (@Autowired(required = false)) because the
        // repository is optional there; the composite slug the billing service
        // keys on comes from it, so a null one would silently skip billing.
        ReflectionTestUtils.setField(manager, "apiToolRepoForSlug", apiToolRepository);

        catalogV1Service = new CatalogV1Service(
                mock(CatalogToolQueryService.class), manager, mock(IntentResolutionManager.class));

        relayService = new CeCatalogRelayService(apiRepository, apiToolRepository,
                credentialClient, creditClient, catalogV1Service, new ObjectMapper(), 10, 120);

        stubCatalogRows();
    }

    @AfterEach
    void clearThreadLocals() {
        CredentialModeContext.clear();
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    /** A live, active generation endpoint: a video submit carrying a descriptor. */
    private void stubCatalogRows() {
        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiName("Seedance");
        api.setApiSlug(API_SLUG);
        api.setAuthType("api_key");
        api.setIsActive(true);
        api.setPlatformCredentialName(API_SLUG);
        api.setIconSlug(API_SLUG);

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug(TOOL_SLUG);
        tool.setIsActive(true);
        tool.setGenerationSpec("{\"kind\":\"video\"}");

        lenient().when(apiRepository.findByApiSlug(API_SLUG)).thenReturn(Optional.of(api));
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
        lenient().when(apiToolRepository.findByApiIdAndToolSlug(API_ID, TOOL_SLUG))
                .thenReturn(Optional.of(tool));
        lenient().when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));

        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setToolId(TOOL_ID.toString());
        context.setApiId(API_ID.toString());
        context.setToolName("create_video_task");
        context.setIconSlug(API_SLUG);
        context.setEndpoint("/tasks");
        context.setHttpMethod("POST");
        context.setAllowedParameterNames(Set.of("prompt", "duration"));
        lenient().when(toolContextService.loadToolContext(COMPOSITE_SLUG))
                .thenReturn(Optional.of(context));
    }

    /** A cloud platform credential with a published, positive frozen markup. */
    private void stubPlatformCredentialAndPricing() {
        PlatformCredentialLookupDto credential = new PlatformCredentialLookupDto();
        credential.setFound(true);
        credential.setId(CREDENTIAL_ID);
        credential.setIntegrationName(API_SLUG);
        credential.setProviderKind("cloud");
        lenient().when(credentialClient.findPlatformCredentialByName(API_SLUG))
                .thenReturn(Optional.of(credential));

        PricingVersionDto version = new PricingVersionDto();
        version.setFound(true);
        version.setPricingVersionId(PRICING_VERSION_ID);
        version.setCredentialId(CREDENTIAL_ID);
        version.setDefaultMarkupCredits(RELAY_MARKUP);
        lenient().when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                .thenReturn(Optional.of(version));

        FrozenMarkupDto frozen = new FrozenMarkupDto();
        frozen.setFound(true);
        frozen.setPricingVersionId(PRICING_VERSION_ID);
        frozen.setEffectiveMarkup(RELAY_MARKUP);
        // The relay now measures the generation before pricing it, so the model
        // and size it resolved travel with the lookup. This test is about the
        // billing lifecycle rather than the measurement, so it accepts either.
        lenient().when(credentialClient.resolveFrozenMarkup(
                        org.mockito.ArgumentMatchers.eq(PRICING_VERSION_ID),
                        org.mockito.ArgumentMatchers.eq(TOOL_ID),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(frozen));
    }

    private void stubSuccessfulReserve() {
        when(creditClient.scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                any(), isNull(), anyInt(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ScopeReserveResult(true, null, false, BigDecimal.TEN));
    }

    /** The provider answers, and a video exists from this moment on. */
    private void stubUpstreamSuccess() {
        Map<String, Object> upstream = new LinkedHashMap<>();
        upstream.put("success", true);
        upstream.put("data", Map.of("task_id", "task-1"));
        upstream.put("credentialSource", "platform");
        when(apiService.executeApiTool(eq(API_ID.toString()), eq("create_video_task"),
                any(JsonNode.class), any(), anyString())).thenReturn(upstream);
    }

    private static CeCatalogRelayRequest relayRequest() {
        return CeCatalogRelayRequest.builder()
                .parameters(Map.of("prompt", "a cat", "duration", 10))
                .build();
    }

    /** What CatalogV1Controller builds for a caller with no run and no chat stream. */
    private static ToolExecutionRequest scopelessRequest() {
        return ToolExecutionRequest.builder()
                .parameters(Map.of("prompt", "a cat", "duration", 10))
                .credentialSource("platform")
                .build();
    }

    // ── the relayed generation ──────────────────────────────────────────────

    @Nested
    @DisplayName("a generation relayed from a linked CE install")
    class RelayedGeneration {

        @Test
        @DisplayName("is charged EXACTLY ONCE, by the relay, and the video is delivered")
        void chargedOnceByTheRelay() {
            stubPlatformCredentialAndPricing();
            stubSuccessfulReserve();
            stubUpstreamSuccess();

            RelayResult result = relayService.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            // The customer got what they paid for.
            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.response().isSuccess()).isTrue();
            verify(apiService).executeApiTool(anyString(), anyString(), any(JsonNode.class),
                    any(), anyString());

            // ONE debit, of the relay's markup, on the linked cloud account.
            ArgumentCaptor<String> reservedKey = ArgumentCaptor.forClass(String.class);
            verify(creditClient, times(1)).scopeReserve(eq(CLOUD_USER_ID), reservedKey.capture(),
                    eq("Seedance"), eq(TOOL_SLUG), eq(RELAY_MARKUP), isNull(), eq(10),
                    // The relay's own scope kind: this debit is the relay's, on
                    // the install, and it is the only one taken for this call.
                    eq("CE_RELAY"), eq(INSTALL_ID), eq(false));
            verify(creditClient, times(1)).scopeCommit(
                    eq(reservedKey.getValue()), eq(RELAY_MARKUP), eq("Seedance"), eq(TOOL_SLUG));
            assertThat(result.billedCredits()).isEqualByComparingTo(RELAY_MARKUP);

            // NEVER zero parties: the money was not released back.
            verify(creditClient, never()).scopeRelease(anyString(), anyString());
            // NEVER two parties: the execution layer inside did not price or
            // reserve the same call a second time.
            verify(credentialClient, never()).resolveScopeMarkupRate(any(), any(), any(), any(),
                    any(UUID.class), any(), any());
        }

        @Test
        @DisplayName("is REFUSED, and never dispatched, when the linked account cannot pay for it")
        void refusedWhenTheAccountCannotPay() {
            stubPlatformCredentialAndPricing();
            when(creditClient.scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                    any(), isNull(), anyInt(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new ScopeReserveResult(false, "Insufficient credits", false, null));

            RelayResult result = relayService.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.INSUFFICIENT_CREDITS);
            assertThat(result.billedCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            // No video was produced, so the platform owner owes the provider
            // nothing for it.
            verify(apiService, never()).executeApiTool(anyString(), anyString(), any(JsonNode.class),
                    any(), anyString());
            verify(creditClient, never()).scopeCommit(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("is REFUSED when the platform publishes no positive price for it, so it can never "
                + "be relayed unbilled")
        void refusedWhenNothingIsPublished() {
            PlatformCredentialLookupDto credential = new PlatformCredentialLookupDto();
            credential.setFound(true);
            credential.setId(CREDENTIAL_ID);
            credential.setProviderKind("cloud");
            when(credentialClient.findPlatformCredentialByName(API_SLUG))
                    .thenReturn(Optional.of(credential));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID)).thenReturn(Optional.empty());

            RelayResult result = relayService.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verify(apiService, never()).executeApiTool(anyString(), anyString(), any(JsonNode.class),
                    any(), anyString());
            verify(creditClient, never()).scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                    any(), any(), anyInt(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("releases the reservation when the provider fails, so nothing is charged for "
                + "nothing")
        void releasedWhenUpstreamFails() {
            stubPlatformCredentialAndPricing();
            stubSuccessfulReserve();
            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class),
                    any(), anyString()))
                    .thenReturn(Map.of("success", false, "error", "provider 500"));

            RelayResult result = relayService.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.response().isSuccess()).isFalse();
            assertThat(result.billedCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(creditClient).scopeRelease(anyString(), anyString());
            verify(creditClient, never()).scopeCommit(anyString(), any(), anyString(), anyString());
        }
    }

    // ── the case the fail-closed rule was written for ───────────────────────

    @Nested
    @DisplayName("a generation that is NOT relayed and carries no billing scope")
    class ScopelessGeneration {

        @Test
        @DisplayName("is still REFUSED, and the provider is never called: an API key on the MCP surface "
                + "has no run and no stream, and nobody would be charged")
        void stillRefused() {
            stubPlatformCredentialAndPricing();

            assertThatThrownBy(() -> catalogV1Service.executeTool(
                    COMPOSITE_SLUG, scopelessRequest(), String.valueOf(CLOUD_USER_ID), null, "req-1"))
                    .isInstanceOf(InsufficientCreditsException.class)
                    .hasMessageContaining("PLATFORM_NOT_AVAILABLE");

            verify(apiService, never()).executeApiTool(anyString(), anyString(), any(JsonNode.class),
                    any(), anyString());
            verify(creditClient, never()).scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                    any(), any(), anyInt(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("cannot be turned into a relayed call from the wire: the ownership flag is not "
                + "deserializable, so a posted body cannot claim someone else pays")
        void theOwnershipFlagIsNotReachableFromTheWire() throws Exception {
            ToolExecutionRequest posted = new ObjectMapper().readValue(
                    "{\"parameters\":{},\"billingOwnedByCaller\":true,\"credentialSource\":\"platform\"}",
                    ToolExecutionRequest.class);

            assertThat(posted.getBillingOwnedByCaller()).isNull();
        }
    }
}
