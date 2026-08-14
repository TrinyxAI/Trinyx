package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code resolveScopeMarkup} is the single funnel every catalog debit passes
 * through: it decides what a customer is charged for one call. It had no test
 * anywhere in the repository, which is why this one exists.
 *
 * <p>The cases here are the four decisions it makes: which price row applies
 * (the model's, or the endpoint's), how the quantity scales it, which VERSION
 * it reads, and what happens when there is nothing to read.
 */
class ResolveScopeMarkupTest {

    private static final Long CREDENTIAL_ID = 42L;
    private static final Long USER_ID = 7L;
    private static final Long LATEST_VERSION_ID = 200L;
    private static final Long PINNED_VERSION_ID = 100L;
    private static final UUID TOOL_ID = UUID.randomUUID();

    private PlatformCredentialPricingVersionRepository versionRepo;
    private PricingVersionEntryRepository entryRepo;
    private WorkflowRunPricingPinRepository pinRepo;
    private PlatformCredentialPricingService service;

    private static PlatformCredentialPricingVersion version(Long id, String defaultMarkup) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(id);
        v.setPlatformCredentialId(CREDENTIAL_ID);
        v.setDefaultMarkupCredits(defaultMarkup == null ? null : new BigDecimal(defaultMarkup));
        return v;
    }

    private static PricingVersionEntry entry(String modelId, String base, String unit, String perUnit) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setApiToolId(TOOL_ID);
        e.setModelId(modelId);
        e.setMarkupCredits(new BigDecimal(base));
        e.setPriceUnit(unit);
        e.setUnitCredits(new BigDecimal(perUnit));
        return e;
    }

    @BeforeEach
    void setUp() {
        versionRepo = mock(PlatformCredentialPricingVersionRepository.class);
        entryRepo = mock(PricingVersionEntryRepository.class);
        pinRepo = mock(WorkflowRunPricingPinRepository.class);
        service = new PlatformCredentialPricingService(
                mock(JdbcTemplate.class), versionRepo, entryRepo,
                mock(PlatformCredentialRepository.class), pinRepo, new MarkupPolicy());

        // A run already pinned to an OLDER version than the latest, which is the
        // realistic state: publishing a new price must not reprice a live run.
        when(versionRepo.findLatest(CREDENTIAL_ID)).thenReturn(Optional.of(version(LATEST_VERSION_ID, "0")));
        when(versionRepo.findById(PINNED_VERSION_ID)).thenReturn(Optional.of(version(PINNED_VERSION_ID, "0")));

        WorkflowRunPricingPin pin = new WorkflowRunPricingPin();
        pin.setId(9L);
        pin.setRunId("run-1");
        pin.setUserId(USER_ID);
        pin.setPlatformCredentialId(CREDENTIAL_ID);
        pin.setPricingVersionId(PINNED_VERSION_ID);
        when(pinRepo.findByRunIdAndPlatformCredentialId("run-1", CREDENTIAL_ID))
                .thenReturn(Optional.of(pin));
        when(pinRepo.save(any())).thenReturn(pin);
    }

    private Optional<PlatformCredentialPricingService.ResolvedMarkup> resolve(String modelId,
                                                                              BigDecimal quantity) {
        return service.resolveScopeMarkup("RUN", "run-1", USER_ID, CREDENTIAL_ID,
                TOOL_ID, modelId, quantity);
    }

    private void givenRows(PricingVersionEntry perModel, PricingVersionEntry toolLevel) {
        when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelId(anyLong(), any(), anyString()))
                .thenReturn(Optional.ofNullable(perModel));
        when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(anyLong(), any()))
                .thenReturn(Optional.ofNullable(toolLevel));
    }

    @Nested
    @DisplayName("which row applies")
    class RowSelection {

        @Test
        @DisplayName("the model's own row wins over the endpoint-wide one")
        void perModelRowWins() {
            givenRows(entry("seedance-2.0-fast", "0", "second", "30"),
                      entry(null, "0", "second", "60"));

            assertThat(resolve("seedance-2.0-fast", new BigDecimal("10")).orElseThrow().effectiveMarkup())
                    .isEqualByComparingTo("300");
        }

        @Test
        @DisplayName("falls back to the endpoint-wide row when the model has none")
        void fallsBackToToolLevel() {
            // This is what lets an admin price a whole endpoint in one gesture
            // and override only the model that deserves a different rate.
            givenRows(null, entry(null, "0", "second", "60"));

            assertThat(resolve("some-unpriced-model", new BigDecimal("10")).orElseThrow().effectiveMarkup())
                    .isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("uses the endpoint-wide row when no model was named at all")
        void noModelUsesToolLevel() {
            givenRows(null, entry(null, "12", "call", "0"));

            assertThat(resolve(null, null).orElseThrow().effectiveMarkup()).isEqualByComparingTo("12");
        }
    }

    @Nested
    @DisplayName("how the quantity scales it")
    class Quantity {

        @Test
        @DisplayName("a per-second row bills rate times seconds")
        void scalesWithQuantity() {
            givenRows(null, entry(null, "0", "second", "60"));

            assertThat(resolve(null, new BigDecimal("10")).orElseThrow().effectiveMarkup())
                    .isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("a per-unit row read with NO quantity bills one unit, never zero")
        void noQuantityNeverBillsZero() {
            // The hole this closes: a caller with no notion of size (every path
            // that predates unit pricing) would otherwise charge only the fixed
            // part, which for a pure rate is nothing, and the platform's own
            // provider key would go out free.
            givenRows(null, entry(null, "0", "second", "60"));

            assertThat(resolve(null, null).orElseThrow().effectiveMarkup())
                    .isEqualByComparingTo("60");
        }

        @Test
        @DisplayName("a per-minute row converts the seconds it is sent: a 60 s call bills the rate ONCE")
        void perMinuteRowConvertsTheMeasurement() {
            // The caller always measures in seconds. When the row is published
            // per minute, multiplying the two directly charged 60x the rate for
            // a one minute call; the row's own unit is what scales it now.
            givenRows(null, entry(null, "0", "minute", "480"));

            var resolved = resolve(null, new BigDecimal("60")).orElseThrow();

            assertThat(resolved.effectiveMarkup()).isEqualByComparingTo("480");
            // and it reports the quantity it charged for, in the row's unit
            assertThat(resolved.quantity()).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("the quantity reported back is the number of units charged, not the raw measurement")
        void reportsTheBilledQuantity() {
            givenRows(null, entry(null, "0", "second", "60"));

            assertThat(resolve(null, new BigDecimal("10")).orElseThrow().quantity())
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a flat row ignores the quantity entirely, so nothing pre-V428 changes")
        void flatRowIgnoresQuantity() {
            givenRows(null, entry(null, "12", "call", "0"));

            assertThat(resolve(null, new BigDecimal("999")).orElseThrow().effectiveMarkup())
                    .isEqualByComparingTo("12");
            assertThat(resolve(null, null).orElseThrow().effectiveMarkup()).isEqualByComparingTo("12");
        }
    }

    @Nested
    @DisplayName("which version it reads")
    class VersionPinning {

        @Test
        @DisplayName("reads the PINNED version, so republishing mid-run never reprices a live call")
        void readsPinnedNotLatest() {
            givenRows(null, entry(null, "12", "call", "0"));

            var resolved = resolve(null, null).orElseThrow();

            assertThat(resolved.pricingVersionId()).isEqualTo(PINNED_VERSION_ID);
            assertThat(resolved.pinId()).isEqualTo(9L);
        }
    }

    @Nested
    @DisplayName("when there is nothing to read")
    class Missing {

        @Test
        @DisplayName("no published version yields empty, which the caller reads as unpriced")
        void noVersionYieldsEmpty() {
            when(versionRepo.findLatest(CREDENTIAL_ID)).thenReturn(Optional.empty());

            assertThat(resolve("seedance-2.0", new BigDecimal("10"))).isEmpty();
        }

        @Test
        @DisplayName("a stale pin pointing at a deleted version yields empty rather than a wrong price")
        void stalePinYieldsEmpty() {
            when(versionRepo.findById(PINNED_VERSION_ID)).thenReturn(Optional.empty());

            assertThat(resolve(null, null)).isEmpty();
        }

        @Test
        @DisplayName("no row at all falls back to the version default")
        void noRowUsesVersionDefault() {
            when(versionRepo.findById(PINNED_VERSION_ID))
                    .thenReturn(Optional.of(version(PINNED_VERSION_ID, "5")));
            givenRows(null, null);

            assertThat(resolve(null, null).orElseThrow().effectiveMarkup()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("and reports NO entry, which is how a caller tells a default from a published price")
        void versionDefaultCarriesNoEntry() {
            // The amount above is positive and flat, exactly like a price an
            // owner published for the endpoint. A caller that must not sell a
            // generation nobody priced separates the two on this and nothing
            // else, so the absence has to be part of the contract rather than
            // an implementation detail of the resolver.
            when(versionRepo.findById(PINNED_VERSION_ID))
                    .thenReturn(Optional.of(version(PINNED_VERSION_ID, "5")));
            givenRows(null, null);

            assertThat(resolve(null, null).orElseThrow().entry()).isNull();
        }

        @Test
        @DisplayName("a published row is reported back, so the flag names the source and not the shape")
        void publishedRowIsReported() {
            when(versionRepo.findById(PINNED_VERSION_ID))
                    .thenReturn(Optional.of(version(PINNED_VERSION_ID, "5")));
            givenRows(null, entry(null, "12", "call", "0"));

            assertThat(resolve(null, null).orElseThrow().entry()).isNotNull();
        }

        @Test
        @DisplayName("a PER-MODEL row counts as published, which is the shape every seeded generation ships")
        void perModelRowCountsAsPublished() {
            // The seeds price each model, not the endpoint, so this is the only
            // shape that matters in production. A caller reading "no row" here
            // would refuse every generation the platform actually sells.
            when(versionRepo.findById(PINNED_VERSION_ID))
                    .thenReturn(Optional.of(version(PINNED_VERSION_ID, "5")));
            givenRows(entry("seedance-2.0", "0", "second", "60"), null);

            assertThat(resolve("seedance-2.0", new BigDecimal("10")).orElseThrow().entry()).isNotNull();
        }
    }
}
