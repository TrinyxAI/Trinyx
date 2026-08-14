package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PriceSpec;
import com.apimarketplace.auth.credential.domain.PriceUnit;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.domain.WorkflowRunPricingPin;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PlatformCredentialRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import com.apimarketplace.auth.credential.repository.WorkflowRunPricingPinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialPricingService - publishing, pinning, and cancelling markup pricing versions")
class PlatformCredentialPricingServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformCredentialPricingVersionRepository versionRepo;
    @Mock
    private PricingVersionEntryRepository entryRepo;
    @Mock
    private PlatformCredentialRepository credentialRepo;
    @Mock
    private WorkflowRunPricingPinRepository pinRepo;

    private PlatformCredentialPricingService service;

    private static final Long CRED_ID = 10L;
    private static final Long PRICING_VERSION_ID = 777L;
    private static final String CREATED_BY = "admin@example.com";

    @BeforeEach
    void setUp() {
        // Use a real MarkupPolicy - it's a pure component with no collaborators.
        MarkupPolicy policy = new MarkupPolicy();
        service = new PlatformCredentialPricingService(
                jdbc, versionRepo, entryRepo, credentialRepo, pinRepo, policy);
    }

    // ========== Helpers ==========

    /**
     * Build a PlatformCredential test fixture. Fields in the record order:
     * id, integrationName, displayName, authType, clientId, clientSecret,
     * apiKey, username, password, authUrl, tokenUrl, defaultScopes, iconSlug,
     * category, description, isEnabled, customFields, defaultMarkupCredits,
     * maxCallsPerRun, createdAt, updatedAt, createdBy.
     */
    private PlatformCredential credential(AuthType authType, Integer maxCalls) {
        return new PlatformCredential(
                CRED_ID,
                "test-integration",
                "Test Integration",
                authType,
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                true,
                null,
                BigDecimal.ZERO,
                maxCalls,
                null, null, null, null
        );
    }

    private PlatformCredentialPricingVersion savedVersion(int version) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(PRICING_VERSION_ID);
        v.setPlatformCredentialId(CRED_ID);
        v.setVersion(version);
        v.setDefaultMarkupCredits(new BigDecimal("0.10"));
        return v;
    }

    private WorkflowRunPricingPin livePin(String runId, Long userId) {
        WorkflowRunPricingPin p = new WorkflowRunPricingPin();
        p.setRunId(runId);
        p.setUserId(userId);
        p.setPlatformCredentialId(CRED_ID);
        p.setPricingVersionId(PRICING_VERSION_ID);
        p.setCancelled(false);
        return p;
    }

    // ========== publishNextVersion ==========

    @Nested
    @DisplayName("publishNextVersion")
    class PublishNextVersion {

        @Test
        @DisplayName("first version starts at 1 when no prior versions exist")
        void firstVersionStartsAtOne() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            // Act
            PlatformCredentialPricingVersion result = service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.25"), Collections.emptyList(), CREATED_BY);

            // Assert
            assertThat(result.getVersion()).isEqualTo(1);
            verify(jdbc).execute(contains("pg_advisory_xact_lock"));
            ArgumentCaptor<PlatformCredentialPricingVersion> captor =
                    ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
            verify(versionRepo, times(1)).save(captor.capture());
            assertThat(captor.getValue().getVersion()).isEqualTo(1);
            assertThat(captor.getValue().getPlatformCredentialId()).isEqualTo(CRED_ID);
            assertThat(captor.getValue().getDefaultMarkupCredits())
                    .isEqualByComparingTo(new BigDecimal("0.25"));
            assertThat(captor.getValue().getCreatedBy()).isEqualTo(CREATED_BY);
        }

        @Test
        @DisplayName("increments version from the current maximum")
        void incrementsFromMax() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(3);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            PlatformCredentialPricingVersion result = service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.10"), null, CREATED_BY);

            // Assert
            assertThat(result.getVersion()).isEqualTo(4);
            ArgumentCaptor<PlatformCredentialPricingVersion> captor =
                    ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
            verify(versionRepo).save(captor.capture());
            assertThat(captor.getValue().getVersion()).isEqualTo(4);
        }

        @Test
        @DisplayName("persists per-tool override entries bound to the new pricing-version id")
        void savesPerToolOverrides() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(0);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            UUID toolA = UUID.randomUUID();
            UUID toolB = UUID.randomUUID();
            List<PriceSpec> overrides = List.of(
                    PriceSpec.flat(toolA, new BigDecimal("0.01")),
                    PriceSpec.flat(toolB, new BigDecimal("0.02")));

            // Act
            service.publishNextVersion(CRED_ID, new BigDecimal("0.10"), overrides, CREATED_BY);

            // Assert
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
            verify(entryRepo).saveAll(captor.capture());
            List<PricingVersionEntry> saved = captor.getValue();
            assertThat(saved).hasSize(2);
            assertThat(saved).allSatisfy(entry ->
                    assertThat(entry.getPricingVersionId()).isEqualTo(PRICING_VERSION_ID));
            assertThat(saved).extracting(PricingVersionEntry::getApiToolId)
                    .containsExactlyInAnyOrder(toolA, toolB);
        }

        @Test
        @DisplayName("rejects OAuth2 credential with non-zero markup and never saves a version")
        void rejectsOAuth2WithMarkup() {
            // Arrange
            PlatformCredential cred = credential(AuthType.OAUTH2, 0);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            // Act + Assert
            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.50"), null, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("OAuth2");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects unknown credentialId and never saves a version")
        void rejectsUnknownCredential() {
            // Arrange
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.empty());

            // Act + Assert
            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.10"), null, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("credential not found");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a negative per-tool override and never saves a version")
        void rejectsNegativePerToolOverride() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            UUID toolId = UUID.randomUUID();
            List<PriceSpec> overrides = List.of(PriceSpec.flat(toolId, new BigDecimal("-0.01")));

            // Act + Assert
            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, new BigDecimal("0.10"), overrides, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("per-tool markup must be >= 0");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("publishes with null default when at least one per-tool override is supplied")
        void publishesWithNullDefaultAndOverride() {
            // Arrange
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            UUID toolId = UUID.randomUUID();
            List<PriceSpec> overrides = List.of(PriceSpec.flat(toolId, new BigDecimal("0.25")));

            // Act - null default is legal so long as an override carries the pricing.
            PlatformCredentialPricingVersion result = service.publishNextVersion(
                    CRED_ID, null, overrides, CREATED_BY);

            // Assert: the persisted version has no default; the override was saved.
            ArgumentCaptor<PlatformCredentialPricingVersion> versionCaptor =
                    ArgumentCaptor.forClass(PlatformCredentialPricingVersion.class);
            verify(versionRepo).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getDefaultMarkupCredits()).isNull();
            assertThat(result.getDefaultMarkupCredits()).isNull();
            verify(entryRepo).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a version that has neither a default nor any overrides")
        void rejectsNullDefaultWithEmptyOverrides() {
            // A version that bills zero for every tool is indistinguishable
            // from "not priced" - refuse it so the inspector toggle stays honest.
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, null, Collections.emptyList(), CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("default markup or at least one per-tool override");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a version with null default and null overrides map")
        void rejectsNullDefaultWithNullOverrides() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> service.publishNextVersion(
                    CRED_ID, null, null, CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("default markup or at least one per-tool override");
        }
    }

    // ========== V428: seeding the starting prices declared in the catalog ==========

    @Nested
    @DisplayName("publishNextVersion - V428 unit and per-model prices")
    class PublishUnitPrices {

        private void expectFreshPublish() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });
        }

        @SuppressWarnings("unchecked")
        private List<PricingVersionEntry> capturePersistedEntries() {
            ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
            verify(entryRepo).saveAll(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("a per-second video model is persisted with its unit and rate, not flattened to a per-call amount")
        void persistsUnitAndRate() {
            // This is the defect the whole change exists for: the seed declares
            // "60 credits per second of video" and the published row has to say
            // so, otherwise a 10 s clip bills the same as a 5 s one.
            expectFreshPublish();
            UUID videoTool = UUID.randomUUID();

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "seedance-2.0", "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null)), CREATED_BY);

            PricingVersionEntry saved = capturePersistedEntries().get(0);
            assertThat(saved.getApiToolId()).isEqualTo(videoTool);
            assertThat(saved.getModelId()).isEqualTo("seedance-2.0");
            assertThat(saved.getPriceUnit()).isEqualTo("second");
            assertThat(saved.unit()).isEqualTo(PriceUnit.SECOND);
            assertThat(saved.getUnitCredits()).isEqualByComparingTo("60");
            assertThat(saved.getMarkupCredits()).isEqualByComparingTo("0");
            // A rate that bills per second must price a 10 s clip at 600, which
            // is what proves the unit survived the write.
            assertThat(new MarkupPolicy().resolveEffectivePrice(
                    savedVersion(1), Optional.of(saved), new BigDecimal("10")))
                    .isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("two models on the SAME endpoint keep their own rates, keyed by (apiToolId, modelId)")
        void persistsOneRowPerModelOnTheSameEndpoint() {
            // One submit endpoint backs a standard and a fast variant at half
            // the price. Collapsing them onto the endpoint would resell one of
            // the two at the wrong rate.
            expectFreshPublish();
            UUID videoTool = UUID.randomUUID();

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "seedance-2.0", "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null),
                    new PriceSpec(videoTool, "seedance-2.0-fast", "second",
                            BigDecimal.ZERO, new BigDecimal("30"), null, null)), CREATED_BY);

            List<PricingVersionEntry> saved = capturePersistedEntries();
            assertThat(saved).hasSize(2);
            assertThat(saved).allSatisfy(e -> assertThat(e.getApiToolId()).isEqualTo(videoTool));
            assertThat(saved).extracting(PricingVersionEntry::getModelId)
                    .containsExactly("seedance-2.0", "seedance-2.0-fast");
            assertThat(saved.get(0).getUnitCredits()).isEqualByComparingTo("60");
            assertThat(saved.get(1).getUnitCredits()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("a floor declared in the seed is persisted, so a tiny call still bills the minimum")
        void persistsMinimumCredits() {
            // ElevenLabs speech is 0.3 credits per character with a floor of 1:
            // without the floor a two-character call would be resold for 0.6.
            expectFreshPublish();
            UUID speechTool = UUID.randomUUID();

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(speechTool, "eleven-v3", "character",
                            BigDecimal.ZERO, new BigDecimal("0.3"), BigDecimal.ONE, null)), CREATED_BY);

            PricingVersionEntry saved = capturePersistedEntries().get(0);
            assertThat(saved.getMinCredits()).isEqualByComparingTo("1");
            assertThat(saved.getMaxCredits()).isNull();
            assertThat(new MarkupPolicy().resolveEffectivePrice(
                    savedVersion(1), Optional.of(saved), new BigDecimal("2")))
                    .isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a model with no price block is free: unit call, nothing charged")
        void modelWithoutPriceBlockIsFree() {
            // A provider can be onboarded before its price is decided. The row
            // still exists so the model is visible and overridable, but it must
            // resolve to zero rather than inherit anything.
            expectFreshPublish();
            UUID tool = UUID.randomUUID();

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(tool, "not-yet-priced", null, null, null, null, null)), CREATED_BY);

            PricingVersionEntry saved = capturePersistedEntries().get(0);
            assertThat(saved.getPriceUnit()).isEqualTo("call");
            assertThat(saved.getMarkupCredits()).isEqualByComparingTo("0");
            assertThat(saved.getUnitCredits()).isEqualByComparingTo("0");
            assertThat(new MarkupPolicy().resolveEffectivePrice(
                    savedVersion(1), Optional.of(saved), new BigDecimal("120")))
                    .isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a flat legacy price is written exactly as before: no model, unit call, zero per-unit rate")
        void flatLegacyPriceIsUnchanged() {
            // The admin screens and the V148 image-gen bootstrap publish one
            // amount per endpoint. That amount must keep billing verbatim.
            expectFreshPublish();
            UUID imageTool = UUID.randomUUID();

            service.publishNextVersion(CRED_ID, new BigDecimal("34"),
                    List.of(PriceSpec.flat(imageTool, new BigDecimal("133"))), CREATED_BY);

            PricingVersionEntry saved = capturePersistedEntries().get(0);
            assertThat(saved.getModelId()).isNull();
            assertThat(saved.getPriceUnit()).isEqualTo("call");
            assertThat(saved.getUnitCredits()).isEqualByComparingTo("0");
            assertThat(saved.getMarkupCredits()).isEqualByComparingTo("133");
            assertThat(saved.getMinCredits()).isNull();
            assertThat(saved.getMaxCredits()).isNull();
            // Quantity is irrelevant to a flat price - 133 whatever is passed.
            assertThat(new MarkupPolicy().resolveEffectivePrice(
                    savedVersion(1), Optional.of(saved), new BigDecimal("42")))
                    .isEqualByComparingTo("133");
        }

        @Test
        @DisplayName("a blank model id is stored as NULL so it lands on the endpoint-wide row, not a phantom model")
        void blankModelIdBecomesNull() {
            expectFreshPublish();
            UUID tool = UUID.randomUUID();

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(tool, "   ", "call", BigDecimal.ONE, null, null, null)), CREATED_BY);

            assertThat(capturePersistedEntries().get(0).getModelId()).isNull();
        }

        @Test
        @DisplayName("rejects two prices claiming the same (endpoint, model) instead of letting the unique index fail opaquely")
        void rejectsDuplicateEndpointModelPair() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            UUID tool = UUID.randomUUID();

            assertThatThrownBy(() -> service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(tool, "dup", "second", BigDecimal.ZERO, BigDecimal.ONE, null, null),
                    new PriceSpec(tool, "dup", "second", BigDecimal.ZERO, BigDecimal.TEN, null, null)),
                    CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicate price")
                    .hasMessageContaining("dup");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a negative per-unit rate and never saves a version")
        void rejectsNegativeUnitRate() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(UUID.randomUUID(), "m", "second",
                            BigDecimal.ZERO, new BigDecimal("-1"), null, null)), CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be >= 0");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("rejects a floor above the ceiling and never saves a version")
        void rejectsMinAboveMax() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));

            assertThatThrownBy(() -> service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(UUID.randomUUID(), "m", "second", BigDecimal.ZERO,
                            BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("10"))), CREATED_BY))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("minCredits must be <= maxCredits");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }
    }

    // ========== the full loop: written, read back, priced ==========

    @Nested
    @DisplayName("per-model unit price round trip")
    class RoundTrip {

        @Test
        @DisplayName("a per-second model price is written, read back with its unit and rate, and prices a 10 s clip at 600")
        void writtenReadBackAndPricedForAQuantity() {
            // Three things have to agree for the platform owner to be able to
            // price a generation: the write keeps the unit, the read reports it,
            // and the billing path multiplies by the quantity. Any one of them
            // flattening the price is invisible until the invoice.
            UUID videoTool = UUID.randomUUID();
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            // Write
            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "seedance-2.0", "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null),
                    new PriceSpec(videoTool, "seedance-2.0-fast", "second",
                            BigDecimal.ZERO, new BigDecimal("30"), null, null)), CREATED_BY);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
            verify(entryRepo).saveAll(captor.capture());
            List<PricingVersionEntry> persisted = captor.getValue();

            // Read back: the admin surface sees BOTH models, each with its rate.
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(persisted);
            assertThat(service.findPrices(PRICING_VERSION_ID))
                    .extracting(PricingVersionEntry::getModelId, PricingVersionEntry::getPriceUnit,
                            e -> e.getUnitCredits().stripTrailingZeros().toPlainString())
                    .containsExactly(
                            org.assertj.core.api.Assertions.tuple("seedance-2.0", "second", "60"),
                            org.assertj.core.api.Assertions.tuple("seedance-2.0-fast", "second", "30"));

            // Priced: the same rows quote a 10 second clip at each model's rate.
            PlatformCredentialPricingVersion latest = savedVersion(1);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(
                    PRICING_VERSION_ID, videoTool, "seedance-2.0"))
                    .thenReturn(Optional.of(persisted.get(0)));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(
                    PRICING_VERSION_ID, videoTool, "seedance-2.0-fast"))
                    .thenReturn(Optional.of(persisted.get(1)));

            assertThat(service.quoteLatest(CRED_ID, videoTool, "seedance-2.0", new BigDecimal("10"))
                    .orElseThrow().credits()).isEqualByComparingTo("600");
            assertThat(service.quoteLatest(CRED_ID, videoTool, "seedance-2.0-fast", new BigDecimal("10"))
                    .orElseThrow().credits()).isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("an unmeasured call on a per-MINUTE row is quoted at the same price it is charged, not at a sixtieth of it")
        void quotesAnUnmeasuredCallAtWhatTheBillingPathWouldCharge() {
            // A quote and an invoice reached by two different rules is the one
            // failure this whole surface exists to prevent, and a missing
            // measurement is where the two rules used to part company: the
            // billing path reads a null quantity as ONE PUBLISHED unit, while
            // the quote replaced it with a literal 1 and handed that over as a
            // PLATFORM measurement, i.e. one SECOND. On a per-minute row that is
            // a sixtieth: quoted 8, charged 480.
            //
            // Per-minute is what makes it visible; per-second hides it, because
            // there one second and one unit are the same number.
            UUID musicTool = UUID.randomUUID();
            PricingVersionEntry perMinute = new PricingVersionEntry();
            perMinute.setApiToolId(musicTool);
            perMinute.setModelId("music-v1");
            perMinute.setPriceUnit("minute");
            perMinute.setMarkupCredits(BigDecimal.ZERO);
            perMinute.setUnitCredits(new BigDecimal("480"));

            PlatformCredentialPricingVersion latest = savedVersion(1);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(
                    PRICING_VERSION_ID, musicTool, "music-v1"))
                    .thenReturn(Optional.of(perMinute));

            var quote = service.quoteLatest(CRED_ID, musicTool, "music-v1", null).orElseThrow();

            // What the billing path charges for the same unmeasured call. Read
            // from the policy rather than restated as a literal, so the two can
            // never drift apart again without this failing.
            BigDecimal charged = new MarkupPolicy().resolveEffectivePrice(
                    latest, Optional.of(perMinute), null);
            assertThat(quote.credits()).isEqualByComparingTo(charged);
            assertThat(quote.credits()).isEqualByComparingTo("480");
        }

        @Test
        @DisplayName("an unmeasured call reports NO quantity, so a surface quotes the rate instead of inventing a total")
        void doesNotInventAQuantityForAnUnmeasuredCall() {
            // The credits above are what an unmeasured call would cost. The
            // QUANTITY is a different question, and the surfaces branch on it:
            // a non-null value makes them print "60 credits per second, 1
            // second = 60 credits for this run" beside a node whose duration is
            // a template that will bill thirty. Filling this in with an
            // assumption is the same defect as the one this class was just
            // fixed for, moved one layer up.
            UUID musicTool = UUID.randomUUID();
            PricingVersionEntry perMinute = new PricingVersionEntry();
            perMinute.setApiToolId(musicTool);
            perMinute.setModelId("music-v1");
            perMinute.setPriceUnit("minute");
            perMinute.setMarkupCredits(BigDecimal.ZERO);
            perMinute.setUnitCredits(new BigDecimal("480"));

            PlatformCredentialPricingVersion latest = savedVersion(1);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(
                    PRICING_VERSION_ID, musicTool, "music-v1"))
                    .thenReturn(Optional.of(perMinute));

            assertThat(service.quoteLatest(CRED_ID, musicTool, "music-v1", null)
                    .orElseThrow().quantity()).isNull();
        }

        @Test
        @DisplayName("a measured call is untouched by the unmeasured-call rule")
        void aMeasuredCallStillConvertsItsPlatformMeasurement() {
            // The fix must be a no-op for every caller that DOES measure, which
            // is all of them on the billing path: 60 seconds on a per-minute row
            // is one minute, priced at the rate, not sixty times it.
            UUID musicTool = UUID.randomUUID();
            PricingVersionEntry perMinute = new PricingVersionEntry();
            perMinute.setApiToolId(musicTool);
            perMinute.setModelId("music-v1");
            perMinute.setPriceUnit("minute");
            perMinute.setMarkupCredits(BigDecimal.ZERO);
            perMinute.setUnitCredits(new BigDecimal("480"));

            PlatformCredentialPricingVersion latest = savedVersion(1);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(
                    PRICING_VERSION_ID, musicTool, "music-v1"))
                    .thenReturn(Optional.of(perMinute));

            var quote = service.quoteLatest(CRED_ID, musicTool, "music-v1", new BigDecimal("60"))
                    .orElseThrow();
            assertThat(quote.credits()).isEqualByComparingTo("480");
            assertThat(quote.quantity()).isEqualByComparingTo("1");
        }
    }

    // ========== republish: a new version, seeded from the one being edited ==========

    @Nested
    @DisplayName("publishNextVersion - republishing (carryForwardUnlistedPrices)")
    class Republish {

        /** The version an admin is editing: one flat endpoint + two models on another. */
        private final UUID flatTool = UUID.randomUUID();
        private final UUID videoTool = UUID.randomUUID();

        private PricingVersionEntry entry(UUID tool, String model, String unit,
                                          String base, String rate) {
            PricingVersionEntry e = new PricingVersionEntry();
            e.setPricingVersionId(PRICING_VERSION_ID);
            e.setApiToolId(tool);
            e.setModelId(model);
            e.setPriceUnit(unit);
            e.setMarkupCredits(new BigDecimal(base));
            e.setUnitCredits(new BigDecimal(rate));
            return e;
        }

        /**
         * Only what a publish reads BEFORE it decides to go ahead: the
         * credential and the rows that are live. Used by the cases that must be
         * refused, where stubbing the write side would stub a call that must
         * never happen.
         */
        private void expectLiveRowsOnly(List<PricingVersionEntry> live) {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(savedVersion(4)));
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(live);
        }

        private void expectRepublishOver(List<PricingVersionEntry> live) {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            PlatformCredentialPricingVersion latest = savedVersion(4);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(live);
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(4);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID + 1);
                        return v;
                    });
        }

        @SuppressWarnings("unchecked")
        private List<PricingVersionEntry> capturePersistedEntries() {
            ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
            verify(entryRepo).saveAll(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("a flat-only republish carries the per-model, per-unit rows it cannot express into the new version")
        void carriesForwardRowsTheRequestDoesNotMention() {
            // The whole defect: an admin (or an older client) republishing one
            // flat amount per endpoint must not collapse a 60-credits-per-second
            // video price into a flat one just by not being able to name it.
            expectRepublishOver(List.of(
                    entry(flatTool, null, "call", "5", "0"),
                    entry(videoTool, "seedance-2.0", "second", "0", "60"),
                    entry(videoTool, "seedance-2.0-fast", "second", "0", "30")));

            service.publishNextVersion(CRED_ID, null,
                    List.of(PriceSpec.flat(flatTool, new BigDecimal("7"))), CREATED_BY, true);

            List<PricingVersionEntry> saved = capturePersistedEntries();
            assertThat(saved).hasSize(3);
            assertThat(saved).allSatisfy(e ->
                    assertThat(e.getPricingVersionId()).isEqualTo(PRICING_VERSION_ID + 1));
            // The row the request DID mention took the new amount...
            assertThat(saved.get(0).getApiToolId()).isEqualTo(flatTool);
            assertThat(saved.get(0).getModelId()).isNull();
            assertThat(saved.get(0).getMarkupCredits()).isEqualByComparingTo("7");
            // ...and the two it could not express survived intact, rate and unit.
            assertThat(saved.get(1).getModelId()).isEqualTo("seedance-2.0");
            assertThat(saved.get(1).getPriceUnit()).isEqualTo("second");
            assertThat(saved.get(1).getUnitCredits()).isEqualByComparingTo("60");
            assertThat(saved.get(2).getModelId()).isEqualTo("seedance-2.0-fast");
            assertThat(saved.get(2).getUnitCredits()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("a carried-forward row keeps billing the exact amount it billed in the version it came from")
        void carriedForwardRowPricesIdentically() {
            expectRepublishOver(List.of(entry(videoTool, "seedance-2.0", "second", "0", "60")));

            service.publishNextVersion(CRED_ID, null,
                    List.of(PriceSpec.flat(flatTool, new BigDecimal("7"))), CREATED_BY, true);

            PricingVersionEntry carried = capturePersistedEntries().stream()
                    .filter(e -> "seedance-2.0".equals(e.getModelId())).findFirst().orElseThrow();
            assertThat(new MarkupPolicy().resolveEffectivePrice(
                    savedVersion(5), Optional.of(carried), new BigDecimal("10")))
                    .isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("a request row overwrites the carried row for the same (endpoint, model) instead of duplicating it")
        void requestRowWinsOverTheCarriedOne() {
            expectRepublishOver(List.of(entry(videoTool, "seedance-2.0", "second", "0", "60")));

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "seedance-2.0", "second",
                            BigDecimal.ZERO, new BigDecimal("45"), null, null)), CREATED_BY, true);

            List<PricingVersionEntry> saved = capturePersistedEntries();
            assertThat(saved).singleElement().satisfies(e -> {
                assertThat(e.getModelId()).isEqualTo("seedance-2.0");
                assertThat(e.getUnitCredits()).isEqualByComparingTo("45");
            });
        }

        @Test
        @DisplayName("carried rows alone satisfy the not-degenerate rule: a default-only edit does not have to restate them")
        void carriedRowsSatisfyTheNonDegenerateCheck() {
            expectRepublishOver(List.of(entry(videoTool, "seedance-2.0", "second", "0", "60")));

            PlatformCredentialPricingVersion published =
                    service.publishNextVersion(CRED_ID, null, List.of(), CREATED_BY, true);

            assertThat(published.getVersion()).isEqualTo(5);
            assertThat(capturePersistedEntries()).hasSize(1);
        }

        @Test
        @DisplayName("re-expressing a per-second rate as a per-minute one bills the same money, not sixty times it")
        void republishingPerSecondAsPerMinuteKeepsThePrice() {
            // The reported defect, end to end: 8 credits per second republished
            // as the economically identical 480 credits per minute. The call is
            // measured in seconds whichever unit the row carries, so a one
            // minute clip must cost 480, not 60 x 480.
            expectRepublishOver(List.of(entry(videoTool, "music-v1", "second", "0", "8")));

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "music-v1", "minute",
                            BigDecimal.ZERO, new BigDecimal("480"), null, null)), CREATED_BY, true);

            PricingVersionEntry saved = capturePersistedEntries().get(0);
            assertThat(saved.getPriceUnit()).isEqualTo("minute");
            assertThat(new MarkupPolicy().resolveEffectivePrice(
                    savedVersion(5), Optional.of(saved), new BigDecimal("60")))
                    .isEqualByComparingTo("480");
        }

        @Test
        @DisplayName("a per-call row cannot be republished per second, and nothing is published when it is tried")
        void refusesAUnitNothingMeasuresForThatRow() {
            // The undercharge case. The endpoint reports one call, so a
            // per-second rate would bill a single second on every request. There
            // is no correct amount to charge, so the publish is refused whole:
            // the version that is live keeps billing what it billed.
            expectLiveRowsOnly(List.of(entry(flatTool, null, "call", "5", "0")));

            assertThatThrownBy(() -> service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(flatTool, null, "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null)), CREATED_BY, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot publish")
                    .hasMessageContaining("per second")
                    .hasMessageContaining("this price is per call");

            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("a per-second row cannot be republished per character either: the guard is about the dimension, not one pair")
        void refusesAnyCrossDimensionUnitChange() {
            expectLiveRowsOnly(List.of(entry(videoTool, "seedance-2.0", "second", "0", "60")));

            assertThatThrownBy(() -> service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "seedance-2.0", "character",
                            BigDecimal.ZERO, new BigDecimal("0.2"), null, null)), CREATED_BY, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nothing would measure characters");

            verify(entryRepo, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("a row the live version does not have is published as asked: there is nothing to contradict")
        void aBrandNewRowIsNotConstrained() {
            expectRepublishOver(List.of(entry(flatTool, null, "call", "5", "0")));

            service.publishNextVersion(CRED_ID, null, List.of(
                    new PriceSpec(videoTool, "brand-new-model", "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null)), CREATED_BY, true);

            assertThat(capturePersistedEntries())
                    .anySatisfy(e -> {
                        assertThat(e.getModelId()).isEqualTo("brand-new-model");
                        assertThat(e.getPriceUnit()).isEqualTo("second");
                    });
        }

        /** Publish arrangement that does NOT expect the live rows to be read. */
        private void expectPublishWithoutCarryForward() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(4);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID + 1);
                        return v;
                    });
        }

        @Test
        @DisplayName("replace publishes exactly the rows given, dropping the ones the admin deleted")
        void replaceDropsRowsTheRequestOmits() {
            // The surface that renders EVERY row publishes with replace, so what
            // the admin sees is what goes live - including a deletion, which
            // carry-forward would otherwise resurrect. The live rows are read
            // (a unit cannot be re-dimensioned, so the current one has to be
            // known) but they are NOT published: the request is the row set.
            expectRepublishOver(List.of(
                    entry(flatTool, null, "call", "5", "0"),
                    entry(videoTool, "seedance-2.0", "second", "0", "60")));

            service.publishNextVersion(CRED_ID, null,
                    List.of(PriceSpec.flat(flatTool, new BigDecimal("7"))), CREATED_BY, false);

            assertThat(capturePersistedEntries()).singleElement()
                    .satisfies(e -> {
                        assertThat(e.getApiToolId()).isEqualTo(flatTool);
                        assertThat(e.getMarkupCredits()).isEqualByComparingTo("7");
                    });
        }

        @Test
        @DisplayName("the 4-arg publish stays a full replacement, so the seed bootstrap and existing callers are unchanged")
        void fourArgOverloadReplaces() {
            expectPublishWithoutCarryForward();

            service.publishNextVersion(CRED_ID, new BigDecimal("1"), List.of(), CREATED_BY);

            verify(entryRepo, never()).saveAll(anyList());
            verify(entryRepo, never()).findByPricingVersionId(any());
        }

        @Test
        @DisplayName("carrying forward from a credential that has never been priced publishes just the request")
        void noPriorVersionCarriesNothing() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });

            service.publishNextVersion(CRED_ID, null,
                    List.of(PriceSpec.flat(flatTool, new BigDecimal("7"))), CREATED_BY, true);

            assertThat(capturePersistedEntries()).singleElement()
                    .satisfies(e -> assertThat(e.getMarkupCredits()).isEqualByComparingTo("7"));
        }
    }

    // ========== bootstrapV1IfAbsent - the seed proposes, it never overrules ==========

    @Nested
    @DisplayName("bootstrapV1IfAbsent")
    class BootstrapV1IfAbsent {

        @Test
        @DisplayName("publishes v1 with the seeded starting prices when the credential has never been priced")
        void publishesV1WhenNoVersionExists() {
            PlatformCredential cred = credential(AuthType.API_KEY, 100);
            when(credentialRepo.findById(CRED_ID)).thenReturn(Optional.of(cred));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());
            when(versionRepo.findMaxVersion(CRED_ID)).thenReturn(null);
            when(versionRepo.save(any(PlatformCredentialPricingVersion.class)))
                    .thenAnswer(inv -> {
                        PlatformCredentialPricingVersion v = inv.getArgument(0);
                        v.setId(PRICING_VERSION_ID);
                        return v;
                    });
            UUID tool = UUID.randomUUID();

            PlatformCredentialPricingVersion published = service.bootstrapV1IfAbsent(
                    CRED_ID, null,
                    List.of(new PriceSpec(tool, "seedance-2.0", "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null)),
                    "ApiMigrationImporter");

            assertThat(published.getVersion()).isEqualTo(1);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PricingVersionEntry>> captor = ArgumentCaptor.forClass(List.class);
            verify(entryRepo).saveAll(captor.capture());
            assertThat(captor.getValue()).singleElement().satisfies(e -> {
                assertThat(e.getModelId()).isEqualTo("seedance-2.0");
                assertThat(e.getUnitCredits()).isEqualByComparingTo("60");
            });
        }

        @Test
        @DisplayName("a re-import NEVER overwrites a price the platform owner has already published")
        void secondRunLeavesTunedPricesAlone() {
            // The seed only ever proposes a STARTING price. Once any version
            // exists the owner owns the pricing, so re-running the importer must
            // return that version untouched - no new version, no new entries.
            PlatformCredentialPricingVersion tuned = savedVersion(4);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(tuned));
            UUID tool = UUID.randomUUID();

            PlatformCredentialPricingVersion result = service.bootstrapV1IfAbsent(
                    CRED_ID, null,
                    List.of(new PriceSpec(tool, "seedance-2.0", "second",
                            BigDecimal.ZERO, new BigDecimal("60"), null, null)),
                    "ApiMigrationImporter");

            assertThat(result).isSameAs(tuned);
            assertThat(result.getVersion()).isEqualTo(4);
            verify(versionRepo, never()).save(any());
            verify(entryRepo, never()).saveAll(anyList());
            verifyNoInteractions(jdbc);
            verifyNoInteractions(credentialRepo);
        }
    }

    // ========== resolveLatestMarkupForTool / hasAnyNonZeroMarkup ==========

    @Nested
    @DisplayName("resolveLatestMarkupForTool")
    class ResolveLatestMarkupForTool {

        @Test
        @DisplayName("on a per-MINUTE row it reports the full unit price, the same amount the billing path charges")
        void reportsTheChargedAmountForAUnitPricedRow() {
            // This is the inspector-facing caller of quoteLatest, and it passes
            // no quantity, so it walked straight through the sixtieth bug: the
            // toggle it feeds showed 8 credits for a call billed 480. Every
            // other test in this class uses a flat row, where one second and
            // one unit are the same number and the defect is invisible.
            UUID musicTool = UUID.randomUUID();
            PricingVersionEntry perMinute = new PricingVersionEntry();
            perMinute.setApiToolId(musicTool);
            perMinute.setPriceUnit("minute");
            perMinute.setMarkupCredits(BigDecimal.ZERO);
            perMinute.setUnitCredits(new BigDecimal("480"));

            PlatformCredentialPricingVersion latest = savedVersion(1);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            // No model is named on this path, so it is the endpoint-wide row
            // that answers.
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(
                    PRICING_VERSION_ID, musicTool))
                    .thenReturn(Optional.of(perMinute));

            assertThat(service.resolveLatestMarkupForTool(CRED_ID, musicTool))
                    .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("480"));
        }

        @Test
        @DisplayName("returns empty when the credential has no published version")
        void returnsEmptyWhenNoVersion() {
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(
                    CRED_ID, UUID.randomUUID());

            assertThat(resolved).isEmpty();
            verifyNoInteractions(entryRepo);
        }

        @Test
        @DisplayName("returns the per-tool override when one exists on the latest version")
        void returnsOverrideWhenPresent() {
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(new BigDecimal("0.05"));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            PricingVersionEntry entry = new PricingVersionEntry();
            entry.setMarkupCredits(new BigDecimal("0.42"));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(PRICING_VERSION_ID, toolId))
                    .thenReturn(Optional.of(entry));

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(CRED_ID, toolId);

            assertThat(resolved).isPresent();
            assertThat(resolved.get()).isEqualByComparingTo("0.42");
        }

        @Test
        @DisplayName("falls back to the version default when there is no per-tool override")
        void fallsBackToDefault() {
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(new BigDecimal("0.05"));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(PRICING_VERSION_ID, toolId))
                    .thenReturn(Optional.empty());

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(CRED_ID, toolId);

            assertThat(resolved).isPresent();
            assertThat(resolved.get()).isEqualByComparingTo("0.05");
        }

        @Test
        @DisplayName("returns zero when the version has a null default and no override for this tool")
        void returnsZeroWhenNullDefaultAndNoOverride() {
            UUID toolId = UUID.randomUUID();
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));
            when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(PRICING_VERSION_ID, toolId))
                    .thenReturn(Optional.empty());

            Optional<BigDecimal> resolved = service.resolveLatestMarkupForTool(CRED_ID, toolId);

            assertThat(resolved).isPresent();
            assertThat(resolved.get()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("hasAnyNonZeroMarkup")
    class HasAnyNonZeroMarkup {

        @Test
        @DisplayName("returns false when no pricing version has been published")
        void falseWhenNoVersion() {
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.empty());

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isFalse();
        }

        @Test
        @DisplayName("returns true when the default markup is positive")
        void trueWhenDefaultIsPositive() {
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(new BigDecimal("0.10"));
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isTrue();
        }

        @Test
        @DisplayName("returns true when only one per-tool override is positive")
        void trueWhenAnyOverridePositive() {
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(null);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            PricingVersionEntry zero = new PricingVersionEntry();
            zero.setMarkupCredits(BigDecimal.ZERO);
            PricingVersionEntry positive = new PricingVersionEntry();
            positive.setMarkupCredits(new BigDecimal("0.05"));
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(List.of(zero, positive));

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isTrue();
        }

        @Test
        @DisplayName("returns false when default is null/zero and every override is zero")
        void falseWhenAllRatesAreZero() {
            PlatformCredentialPricingVersion latest = new PlatformCredentialPricingVersion();
            latest.setId(PRICING_VERSION_ID);
            latest.setDefaultMarkupCredits(BigDecimal.ZERO);
            when(versionRepo.findLatest(CRED_ID)).thenReturn(Optional.of(latest));

            PricingVersionEntry zero = new PricingVersionEntry();
            zero.setMarkupCredits(BigDecimal.ZERO);
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(List.of(zero));

            assertThat(service.hasAnyNonZeroMarkup(CRED_ID)).isFalse();
        }
    }

    // ========== cancelActivePinsForUser ==========

    @Nested
    @DisplayName("cancelActivePinsForUser")
    class CancelActivePinsForUser {

        @Test
        @DisplayName("cancels every distinct run for the user exactly once and returns the total")
        void cancelsAllDistinctRuns() {
            // Arrange - 3 live pins across 2 distinct runIds.
            Long userId = 42L;
            WorkflowRunPricingPin p1 = livePin("run-A", userId);
            WorkflowRunPricingPin p2 = livePin("run-A", userId);
            WorkflowRunPricingPin p3 = livePin("run-B", userId);
            when(pinRepo.findByUserIdAndCancelledFalse(userId))
                    .thenReturn(List.of(p1, p2, p3));
            when(pinRepo.cancelByRunId(eq("run-A"), any(Instant.class))).thenReturn(2);
            when(pinRepo.cancelByRunId(eq("run-B"), any(Instant.class))).thenReturn(1);

            // Act
            int total = service.cancelActivePinsForUser(userId);

            // Assert
            assertThat(total).isEqualTo(3);
            verify(pinRepo, times(1)).cancelByRunId(eq("run-A"), any(Instant.class));
            verify(pinRepo, times(1)).cancelByRunId(eq("run-B"), any(Instant.class));
        }

        @Test
        @DisplayName("returns 0 and issues no cancel calls when the user has no live pins")
        void returnsZeroWhenNoLivePins() {
            // Arrange
            Long userId = 42L;
            when(pinRepo.findByUserIdAndCancelledFalse(userId)).thenReturn(List.of());

            // Act
            int total = service.cancelActivePinsForUser(userId);

            // Assert
            assertThat(total).isZero();
            verify(pinRepo, never()).cancelByRunId(anyString(), any(Instant.class));
        }
    }

    // ========== savePin ==========

    @Nested
    @DisplayName("savePin")
    class SavePin {

        @Test
        @DisplayName("returns the existing pin unchanged when one already exists (idempotent)")
        void returnsExistingPin() {
            // Arrange
            WorkflowRunPricingPin existing = livePin("run-1", 1L);
            existing.setId(99L);
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-1", CRED_ID))
                    .thenReturn(Optional.of(existing));

            // Act
            WorkflowRunPricingPin result = service.savePin(
                    "run-1", 1L, CRED_ID, PRICING_VERSION_ID);

            // Assert
            assertThat(result).isSameAs(existing);
            verify(pinRepo, never()).save(any(WorkflowRunPricingPin.class));
        }

        @Test
        @DisplayName("creates a new pin with the supplied run/user/credential/pricing version when none exists")
        void createsNewPin() {
            // Arrange
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-2", CRED_ID))
                    .thenReturn(Optional.empty());
            when(pinRepo.save(any(WorkflowRunPricingPin.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.savePin("run-2", 5L, CRED_ID, PRICING_VERSION_ID);

            // Assert
            ArgumentCaptor<WorkflowRunPricingPin> captor =
                    ArgumentCaptor.forClass(WorkflowRunPricingPin.class);
            verify(pinRepo).save(captor.capture());
            WorkflowRunPricingPin saved = captor.getValue();
            assertThat(saved.getRunId()).isEqualTo("run-2");
            assertThat(saved.getUserId()).isEqualTo(5L);
            assertThat(saved.getPlatformCredentialId()).isEqualTo(CRED_ID);
            assertThat(saved.getPricingVersionId()).isEqualTo(PRICING_VERSION_ID);
        }

        @Test
        @DisplayName("race: concurrent inserter wins - second caller's save throws unique-constraint, we re-read and return the winning row (no lost pin)")
        void concurrentRaceReReadsWinner() {
            // Arrange - first findBy returns empty (both threads see empty),
            // our save loses the race and the unique constraint throws,
            // then the recovery findBy returns the winner's row.
            WorkflowRunPricingPin winner = livePin("run-race", 1L);
            winner.setId(101L);
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-race", CRED_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            when(pinRepo.save(any(WorkflowRunPricingPin.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                            "uk_wrpp_run_cred"));

            // Act
            WorkflowRunPricingPin result = service.savePin(
                    "run-race", 1L, CRED_ID, PRICING_VERSION_ID);

            // Assert
            assertThat(result).isSameAs(winner);
            verify(pinRepo, times(2)).findByRunIdAndPlatformCredentialId("run-race", CRED_ID);
            verify(pinRepo).save(any(WorkflowRunPricingPin.class));
        }

        @Test
        @DisplayName("race recovery fails - if the winning row is gone on re-read, surface the integrity exception instead of silently losing the pin")
        void concurrentRaceSurfacesExceptionWhenReReadEmpty() {
            // Arrange - both findBy calls return empty, save throws.
            // This should not happen in practice (unique constraint implies
            // a winning row) but we must not return null or a phantom pin.
            when(pinRepo.findByRunIdAndPlatformCredentialId("run-race-2", CRED_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(pinRepo.save(any(WorkflowRunPricingPin.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));

            // Act + Assert
            assertThatThrownBy(() -> service.savePin(
                    "run-race-2", 1L, CRED_ID, PRICING_VERSION_ID))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("boom");
        }
    }

    // ========== cancelPinsForRun ==========

    @Nested
    @DisplayName("cancelPinsForRun")
    class CancelPinsForRun {

        @Test
        @DisplayName("delegates to repo.cancelByRunId with the current instant and returns its result")
        void delegatesToRepo() {
            // Arrange
            when(pinRepo.cancelByRunId(eq("run-9"), any(Instant.class))).thenReturn(4);

            // Act
            int result = service.cancelPinsForRun("run-9");

            // Assert
            assertThat(result).isEqualTo(4);
            verify(pinRepo).cancelByRunId(eq("run-9"), any(Instant.class));
        }
    }

    // ========== findAllVersions / findOverrides (admin-history read path) ==========

    @Nested
    @DisplayName("findAllVersions")
    class FindAllVersions {

        @Test
        @DisplayName("returns every version for the credential, newest-first, via repo ordering")
        void delegatesToRepoDesc() {
            PlatformCredentialPricingVersion v3 = savedVersion(3);
            PlatformCredentialPricingVersion v1 = savedVersion(1);
            when(versionRepo.findByPlatformCredentialIdOrderByVersionDesc(CRED_ID))
                    .thenReturn(List.of(v3, v1));

            List<PlatformCredentialPricingVersion> result = service.findAllVersions(CRED_ID);

            assertThat(result).extracting(PlatformCredentialPricingVersion::getVersion)
                    .containsExactly(3, 1);
        }
    }

    @Nested
    @DisplayName("findOverrides")
    class FindOverrides {

        @Test
        @DisplayName("returns an empty map when a version has no per-tool overrides")
        void emptyWhenNoEntries() {
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(Collections.emptyList());

            Map<UUID, BigDecimal> result = service.findOverrides(PRICING_VERSION_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("maps each entry to {apiToolId -> markupCredits} preserving every row")
        void mapsEachEntry() {
            UUID toolA = UUID.randomUUID();
            UUID toolB = UUID.randomUUID();
            PricingVersionEntry a = new PricingVersionEntry();
            a.setPricingVersionId(PRICING_VERSION_ID);
            a.setApiToolId(toolA);
            a.setMarkupCredits(new BigDecimal("0.25"));
            PricingVersionEntry b = new PricingVersionEntry();
            b.setPricingVersionId(PRICING_VERSION_ID);
            b.setApiToolId(toolB);
            b.setMarkupCredits(new BigDecimal("0.05"));
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(List.of(a, b));

            Map<UUID, BigDecimal> result = service.findOverrides(PRICING_VERSION_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(toolA)).isEqualByComparingTo("0.25");
            assertThat(result.get(toolB)).isEqualByComparingTo("0.05");
        }

        @Test
        @DisplayName("omits per-model rows instead of letting two models on one endpoint collide on the key")
        void omitsPerModelRowsRatherThanCollidingOnTheEndpoint() {
            // A map keyed by endpoint alone cannot hold two models, so whichever
            // row was read last used to win and the admin was shown a price that
            // was never published for either model.
            UUID videoTool = UUID.randomUUID();
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(List.of(
                    unitEntry(videoTool, "seedance-2.0", "second", "0", "60"),
                    unitEntry(videoTool, "seedance-2.0-fast", "second", "0", "30")));

            assertThat(service.findOverrides(PRICING_VERSION_ID)).isEmpty();
        }

        @Test
        @DisplayName("omits a unit-priced endpoint row rather than reporting its zero base as a free call")
        void omitsUnitPricedRowRatherThanReportingItFree() {
            // markup_credits is only the FIXED part of a unit price. Reporting it
            // alone turns "60 credits per second" into "0", i.e. into free.
            UUID videoTool = UUID.randomUUID();
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(List.of(unitEntry(videoTool, null, "second", "0", "60")));

            assertThat(service.findOverrides(PRICING_VERSION_ID))
                    .doesNotContainKey(videoTool)
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("findPrices")
    class FindPrices {

        @Test
        @DisplayName("returns every published row, so two models on one endpoint keep their own rate through the read")
        void keepsBothModelsOfAnEndpoint() {
            UUID videoTool = UUID.randomUUID();
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(List.of(
                    unitEntry(videoTool, "seedance-2.0", "second", "0", "60"),
                    unitEntry(videoTool, "seedance-2.0-fast", "second", "0", "30")));

            List<PricingVersionEntry> rows = service.findPrices(PRICING_VERSION_ID);

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(PricingVersionEntry::getModelId)
                    .containsExactly("seedance-2.0", "seedance-2.0-fast");
            assertThat(rows.get(0).getUnitCredits()).isEqualByComparingTo("60");
            assertThat(rows.get(1).getUnitCredits()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("lists an endpoint's own row before the models that override it")
        void endpointRowSortsBeforeItsModels() {
            UUID tool = UUID.randomUUID();
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID)).thenReturn(List.of(
                    unitEntry(tool, "zulu", "second", "0", "1"),
                    unitEntry(tool, null, "call", "5", "0"),
                    unitEntry(tool, "alpha", "second", "0", "2")));

            assertThat(service.findPrices(PRICING_VERSION_ID))
                    .extracting(PricingVersionEntry::getModelId)
                    .containsExactly(null, "alpha", "zulu");
        }

        @Test
        @DisplayName("returns an empty list when the version prices nothing per endpoint")
        void emptyWhenNoRows() {
            when(entryRepo.findByPricingVersionId(PRICING_VERSION_ID))
                    .thenReturn(Collections.emptyList());

            assertThat(service.findPrices(PRICING_VERSION_ID)).isEmpty();
        }
    }

    private static PricingVersionEntry unitEntry(UUID tool, String model, String unit,
                                                  String base, String rate) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setPricingVersionId(PRICING_VERSION_ID);
        e.setApiToolId(tool);
        e.setModelId(model);
        e.setPriceUnit(unit);
        e.setMarkupCredits(new BigDecimal(base));
        e.setUnitCredits(new BigDecimal(rate));
        return e;
    }
}
