package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.catalog.CatalogDefaults;
import com.apimarketplace.agent.config.ModelPricingConfig;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import com.apimarketplace.agent.service.AuthPricingSyncClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The generic catalog rate-limit fallback must not overwrite a curated
 * {@code ai.agent.rate-limits} entry.
 *
 * <p>Why this is not cosmetic. {@code CachedModelRateLimitProvider} resolves a
 * model as {@code coalesce(DB column, YAML seed, provider bucket)}, so a DB
 * value wins per-field. Stamping the blanket 60000/500 on a curated model
 * therefore silently replaces a researched limit with a generic one. Measured
 * on production before this fix, that had killed 44 of the 50 curated entries:
 * {@code openai/gpt-5.4-mini} was enforcing 60k TPM against a curated 10M.
 *
 * <p>The opposite mistake is equally bad and is pinned here too: skipping the
 * fallback for a model that has NO curated entry drops it onto
 * {@code RateLimitConfig.defaults} (10M TPM / 10K RPM, "very permissive"),
 * i.e. no ceiling at all. Both directions are asserted.
 */
@DisplayName("CatalogMergeService - curated rate limits are not overwritten by the fallback")
class CatalogMergeServiceCuratedRateLimitTest {

    private ModelConfigOverrideRepository modelRepo;
    private CatalogMergeService merge;

    @BeforeEach
    void setUp() {
        modelRepo = mock(ModelConfigOverrideRepository.class);
        when(modelRepo.findMaxRanking()).thenReturn(0);
        when(modelRepo.findByProviderAndModelId(any(), any())).thenReturn(Optional.empty());
        when(modelRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogDefaults defaults = new CatalogDefaults();
        defaults.setRateLimitTpm(60000);
        defaults.setRateLimitRpm(500);
        defaults.setRateLimitTpmPerTenant(20000);
        defaults.setRateLimitRpmPerTenant(200);

        merge = new CatalogMergeService(modelRepo, null, mock(AuthPricingSyncClient.class), defaults);
        injectCuratedTable(curatedWith("gpt-5.4-mini", "openrouter:openai/gpt-5.4"));
    }

    /** The field is @Autowired(required=false); tests set it directly. */
    private void injectCuratedTable(ModelPricingConfig config) {
        try {
            Field f = CatalogMergeService.class.getDeclaredField("modelPricingConfig");
            f.setAccessible(true);
            f.set(merge, config);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("field renamed? keep this test in step with it", e);
        }
    }

    private static ModelPricingConfig curatedWith(String... keys) {
        ModelPricingConfig cfg = new ModelPricingConfig();
        Map<String, ModelPricingConfig.ModelRateLimitInfo> table = new HashMap<>();
        for (String k : keys) {
            ModelPricingConfig.ModelRateLimitInfo info = new ModelPricingConfig.ModelRateLimitInfo();
            info.setTpm(10_000_000);
            info.setRpm(10_000);
            table.put(k, info);
        }
        cfg.setRateLimits(table);
        return cfg;
    }

    private ModelConfigOverrideEntity mergeOne(String provider, String modelId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("modelId", modelId);
        row.put("displayName", modelId);
        merge.merge(List.of(row), MergeOptions.forSync());

        org.mockito.ArgumentCaptor<ModelConfigOverrideEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("A curated model keeps NULL columns, so the curated value stays in force")
    void curatedModelIsLeftNull() {
        ModelConfigOverrideEntity saved = mergeOne("openai", "gpt-5.4-mini");

        assertThat(saved.getRateLimitTpm())
                .as("stamping 60000 here would override the curated 10,000,000")
                .isNull();
        assertThat(saved.getRateLimitRpm()).isNull();
        assertThat(saved.getRateLimitTpmPerTenant()).isNull();
        assertThat(saved.getRateLimitRpmPerTenant()).isNull();
    }

    @Test
    @DisplayName("A model with no curated entry still gets the fallback - never left without a ceiling")
    void uncuratedModelStillGetsTheFallback() {
        ModelConfigOverrideEntity saved = mergeOne("zai", "glm-5.3");

        assertThat(saved.getRateLimitTpm()).isEqualTo(60000);
        assertThat(saved.getRateLimitRpm()).isEqualTo(500);
        assertThat(saved.getRateLimitTpmPerTenant()).isEqualTo(20000);
        assertThat(saved.getRateLimitRpmPerTenant()).isEqualTo(200);
    }

    @Test
    @DisplayName("The scoped provider:modelId key is honoured, like the resolver does")
    void scopedKeyCounts() {
        // The curated table keys OpenRouter rows by their full prefixed id.
        // If the merge only checked the bare modelId the two layers would
        // disagree about which models are curated.
        ModelConfigOverrideEntity saved = mergeOne("openrouter", "openai/gpt-5.4");
        assertThat(saved.getRateLimitTpm()).isNull();
    }

    @Test
    @DisplayName("A feed-supplied limit is still respected for a curated model")
    void feedSuppliedLimitStillWins() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", "openai");
        row.put("modelId", "gpt-5.4-mini");
        row.put("displayName", "gpt-5.4-mini");
        row.put("rateLimitTpm", 123456);
        merge.merge(List.of(row), MergeOptions.forSync());

        org.mockito.ArgumentCaptor<ModelConfigOverrideEntity> cap =
                org.mockito.ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(modelRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getRateLimitTpm())
                .as("skipping the fallback must not also discard a real feed value")
                .isEqualTo(123456);
    }

    @Test
    @DisplayName("No curated table injected: the previous always-stamp behaviour is preserved")
    void nullConfigKeepsLegacyBehaviour() {
        injectCuratedTable(null);
        ModelConfigOverrideEntity saved = mergeOne("openai", "gpt-5.4-mini");
        assertThat(saved.getRateLimitTpm()).isEqualTo(60000);
    }
}
