package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import com.apimarketplace.auth.credential.repository.PlatformCredentialPricingVersionRepository;
import com.apimarketplace.auth.credential.repository.PricingVersionEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The quantity-less markup path must report the SHAPE of the row, not only the
 * amount.
 *
 * <p>This path cannot measure a call, so a per-unit row yields the price of ONE
 * unit. A caller handed only that number cannot tell it apart from a flat price
 * an administrator chose, and would charge a ten second video as one second.
 * Carrying the unit is what lets the caller refuse instead of undercharging.
 */
class FrozenMarkupCarriesRowShapeTest {

    private static final Long VERSION_ID = 100L;
    private static final UUID TOOL_ID = UUID.randomUUID();

    private PricingVersionEntryRepository entryRepo;
    private PricingVersionService service;

    @BeforeEach
    void setUp() {
        PlatformCredentialPricingVersionRepository versionRepo =
                mock(PlatformCredentialPricingVersionRepository.class);
        entryRepo = mock(PricingVersionEntryRepository.class);
        PlatformCredentialPricingVersion version = new PlatformCredentialPricingVersion();
        version.setId(VERSION_ID);
        version.setPlatformCredentialId(42L);
        version.setVersion(3);
        version.setDefaultMarkupCredits(BigDecimal.ZERO);
        when(versionRepo.findById(VERSION_ID)).thenReturn(Optional.of(version));
        service = new PricingVersionService(versionRepo, entryRepo, new MarkupPolicy());
    }

    private void givenRow(String base, String unit, String perUnit) {
        PricingVersionEntry e = new PricingVersionEntry();
        e.setApiToolId(TOOL_ID);
        e.setMarkupCredits(new BigDecimal(base));
        e.setPriceUnit(unit);
        e.setUnitCredits(new BigDecimal(perUnit));
        when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(anyLong(), any()))
                .thenReturn(Optional.of(e));
    }

    @Test
    @DisplayName("a per-unit row reports its unit, so the caller can see the amount covers ONE of them")
    void perUnitRowReportsItsShape() {
        givenRow("0", "second", "60");

        PricingVersionService.FrozenMarkup f =
                service.resolveFrozenMarkup(VERSION_ID, TOOL_ID).orElseThrow();

        assertThat(f.priceUnit()).isEqualTo("second");
        assertThat(f.unitCredits()).isEqualByComparingTo("60");
        // The amount itself stays the one-unit fallback; the point is that the
        // caller can now tell that is what it is.
        assertThat(f.effectiveMarkup()).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("a flat row reports a zero rate, so it is not mistaken for an unmeasured one")
    void flatRowIsDistinguishable() {
        givenRow("12", "call", "0");

        PricingVersionService.FrozenMarkup f =
                service.resolveFrozenMarkup(VERSION_ID, TOOL_ID).orElseThrow();

        assertThat(f.priceUnit()).isEqualTo("call");
        assertThat(f.unitCredits()).isEqualByComparingTo("0");
        assertThat(f.effectiveMarkup()).isEqualByComparingTo("12");
        assertThat(f.pricedByPublishedRow()).isTrue();
    }

    @Test
    @DisplayName("no row at all reports a flat shape, since the version default is a flat amount")
    void versionDefaultIsFlat() {
        when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(anyLong(), any()))
                .thenReturn(Optional.empty());

        PricingVersionService.FrozenMarkup f =
                service.resolveFrozenMarkup(VERSION_ID, TOOL_ID).orElseThrow();

        assertThat(f.priceUnit()).isEqualTo("call");
        assertThat(f.unitCredits()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the version default says so, because its flat shape is otherwise a published flat price")
    void versionDefaultSaysItIsNotARow() {
        // versionDefaultIsFlat above pins the ambiguity this resolves: the
        // default and a deliberate flat price report the SAME unit and the SAME
        // zero rate. Only this flag separates them, and a caller that must not
        // sell an endpoint nobody priced has nothing else to read.
        when(entryRepo.findByPricingVersionIdAndApiToolIdAndModelIdIsNull(anyLong(), any()))
                .thenReturn(Optional.empty());

        PricingVersionService.FrozenMarkup f =
                service.resolveFrozenMarkup(VERSION_ID, TOOL_ID).orElseThrow();

        assertThat(f.pricedByPublishedRow()).isFalse();
    }

    @Test
    @DisplayName("a published row says so, so the flag names the source and not the shape")
    void publishedRowSaysItIsARow() {
        givenRow("0", "second", "60");

        PricingVersionService.FrozenMarkup f =
                service.resolveFrozenMarkup(VERSION_ID, TOOL_ID).orElseThrow();

        assertThat(f.pricedByPublishedRow()).isTrue();
    }
}
