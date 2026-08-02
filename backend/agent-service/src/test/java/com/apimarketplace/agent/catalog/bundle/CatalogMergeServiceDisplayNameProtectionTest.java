package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the display-name clobber exposed by widening
 * {@code LiteLlmFeedParser.PROVIDER_MAP} to Moonshot / Qwen / Z.AI.
 *
 * <p>Neither feed publishes a human display name: the LiteLLM parser stamps
 * the RAW model id. Five rows seeded by V384/V386 carry curated names
 * ({@code Kimi K2.6}, {@code Qwen Max}, ...) and, until V416, an empty
 * {@code user_modified_fields} - so the first apply-sync that could finally
 * reach those providers would rename them to {@code kimi-k2.6},
 * {@code qwen-max} in the model picker.
 *
 * <p>These tests pin the mechanism V416 relies on: {@code displayName} listed
 * in {@code user_modified_fields} survives a sync merge, and is still
 * overwritable by the paths that are supposed to own it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogMergeService - curated display-name protection from sync overwrite")
class CatalogMergeServiceDisplayNameProtectionTest {

    @Mock private ModelConfigOverrideRepository modelRepo;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        merge = new CatalogMergeService(modelRepo, null, authPricingSyncClient, null);
    }

    @Test
    @DisplayName("Sync keeps the curated name when displayName is in userModifiedFields (what V416 backfills)")
    void syncPreservesCuratedDisplayNameWhenProtected() {
        ModelConfigOverrideEntity row = curatedRow("moonshot", "kimi-k2.6", "Kimi K2.6");
        row.setUserModifiedFields(new String[]{"displayName"});
        when(modelRepo.findByProviderAndModelId("moonshot", "kimi-k2.6"))
                .thenReturn(Optional.of(row));

        merge.merge(List.of(liteLlmPayload("moonshot", "kimi-k2.6")), MergeOptions.forSync());

        assertThat(row.getDisplayName()).isEqualTo("Kimi K2.6");
    }

    @Test
    @DisplayName("Unprotected curated name IS clobbered by the feed - the exact bug V416 prevents")
    void unprotectedCuratedDisplayNameIsClobbered() {
        // Pins the failure mode itself, so nobody later drops V416 believing
        // the merge protects these names on its own. This is the pre-V416
        // state of the five V384/V386 rows.
        ModelConfigOverrideEntity row = curatedRow("qwen", "qwen-max", "Qwen Max");
        row.setUserModifiedFields(new String[0]);
        when(modelRepo.findByProviderAndModelId("qwen", "qwen-max"))
                .thenReturn(Optional.of(row));

        merge.merge(List.of(liteLlmPayload("qwen", "qwen-max")), MergeOptions.forSync());

        assertThat(row.getDisplayName()).isEqualTo("qwen-max");
    }

    @Test
    @DisplayName("Protection is path-independent: even a signed bundle cannot rename a protected model")
    void bundleAlsoRespectsProtectedDisplayName() {
        // Documented consequence of V416, verified rather than assumed:
        // `protect` is built from user_modified_fields alone
        // (CatalogMergeService L233-237), identically for the sync, bundle and
        // seed paths. So protecting these five names also costs the cloud the
        // ability to rename them via a bundle; renaming then requires an admin
        // edit, which is exactly what user_modified_fields is meant to express.
        ModelConfigOverrideEntity row = curatedRow("qwen", "qwen-max", "Qwen Max");
        row.setUserModifiedFields(new String[]{"displayName"});
        when(modelRepo.findByProviderAndModelId("qwen", "qwen-max"))
                .thenReturn(Optional.of(row));

        Map<String, Object> bundleRow = liteLlmPayload("qwen", "qwen-max");
        bundleRow.put("displayName", "Qwen 3 Max");

        merge.merge(List.of(bundleRow), MergeOptions.forBundle(7L));

        assertThat(row.getDisplayName()).isEqualTo("Qwen Max");
    }

    @Test
    @DisplayName("Protection is scoped to displayName - the feed still refreshes prices on a protected row")
    void protectedDisplayNameDoesNotFreezeTheRestOfTheRow() {
        // The whole point of the fix is a fresher catalog; protecting the name
        // must not turn these rows into stale islands.
        ModelConfigOverrideEntity row = curatedRow("moonshot", "kimi-k2.6", "Kimi K2.6");
        row.setUserModifiedFields(new String[]{"displayName"});
        when(modelRepo.findByProviderAndModelId("moonshot", "kimi-k2.6"))
                .thenReturn(Optional.of(row));

        Map<String, Object> feedRow = liteLlmPayload("moonshot", "kimi-k2.6");
        feedRow.put("priceInput", "1.20");
        feedRow.put("priceOutput", "5.00");

        merge.merge(List.of(feedRow), MergeOptions.forSync());

        assertThat(row.getDisplayName()).isEqualTo("Kimi K2.6");
        assertThat(row.getPriceInput()).isEqualByComparingTo(new BigDecimal("1.20"));
        assertThat(row.getPriceOutput()).isEqualByComparingTo(new BigDecimal("5.00"));
    }

    // --- helpers ---

    /** A V384/V386-shaped curated row: human display name, real prices. */
    private static ModelConfigOverrideEntity curatedRow(String provider, String modelId,
                                                        String displayName) {
        ModelConfigOverrideEntity e = new ModelConfigOverrideEntity();
        e.setId(1L);
        e.setProvider(provider);
        e.setModelId(modelId);
        e.setDisplayName(displayName);
        e.setSource("curated");
        e.setPriceInput(new BigDecimal("0.95"));
        e.setPriceOutput(new BigDecimal("4.00"));
        e.setEnabled(true);
        e.setUserModifiedFields(new String[0]);
        return e;
    }

    /**
     * What LiteLlmFeedParser emits: displayName is the raw model id, because
     * the feed carries no name of its own.
     */
    private static Map<String, Object> liteLlmPayload(String provider, String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("displayName", modelId);
        m.put("priceInput", "0.95");
        m.put("priceOutput", "4.00");
        m.put("mode", "chat");
        m.put("supportsTools", true);
        m.put("source", "litellm");
        return m;
    }
}
