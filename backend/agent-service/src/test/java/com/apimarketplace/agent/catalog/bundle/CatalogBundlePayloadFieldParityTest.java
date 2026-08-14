package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundle must CARRY every field the bundle apply WRITES.
 *
 * <p>The apply runs with {@code partialUpdate=false}, whose contract is stated
 * in {@link CatalogMergeService} itself: "a field absent from the payload
 * overwrites the row to null". So a field this producer forgets is not merely
 * missing on the other side, it is actively CLEARED there, on every apply, for
 * as long as the omission lasts. Eighteen of them were, silently, and the
 * install's own feed enrichment was the thing being cleared.
 *
 * <p>The failure is invisible by construction: absence means "the cloud has no
 * value for this", which is an ordinary and legal thing for it to mean, so the
 * wire carries no error, the apply reports success, and only the data is gone.
 * Nothing but a parity check can see it.
 *
 * <p>The check is derived rather than enumerated ON PURPOSE. A hand-written
 * list of expected keys would have exactly the property that caused the bug: it
 * would need updating by the same person who forgot the field, at the same
 * moment they forgot it. Here the expectation IS
 * {@link CatalogMergeService#APPLIED_FIELD_NAMES}, and the entity is populated
 * by reflection, so a field added to the reader tomorrow is covered tonight.
 */
@DisplayName("the bundle carries every field its apply would otherwise null")
class CatalogBundlePayloadFieldParityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * An entity with EVERY settable property populated, built by reflection.
     *
     * <p>Reflection rather than 36 hand-written setters for the same reason the
     * assertion is derived: a fixture that has to be extended by hand cannot
     * prove anything about the field somebody forgot to add.
     */
    private static ModelConfigOverrideEntity fullyPopulated() throws Exception {
        ModelConfigOverrideEntity m = new ModelConfigOverrideEntity();
        m.setProvider("openai");
        m.setModelId("gpt-test");
        for (Method setter : ModelConfigOverrideEntity.class.getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) continue;
            Object value = sampleFor(setter.getParameterTypes()[0]);
            if (value == null) continue;
            try {
                setter.invoke(m, value);
            } catch (Exception ignored) {
                // A setter that rejects the sample is not this test's business.
            }
        }
        // Re-assert identity after the sweep, which overwrote them.
        m.setProvider("openai");
        m.setModelId("gpt-test");
        return m;
    }

    private static Object sampleFor(Class<?> type) {
        if (type == String.class) return "x";
        if (type == Boolean.class || type == boolean.class) return Boolean.TRUE;
        if (type == Integer.class || type == int.class) return 1;
        if (type == Long.class || type == long.class) return 1L;
        if (type == BigDecimal.class) return new BigDecimal("1.5");
        if (type == LocalDate.class) return LocalDate.of(2026, 1, 1);
        if (type == Instant.class) return Instant.parse("2026-01-01T00:00:00Z");
        if (type == String[].class) return new String[]{"a", "b"};
        if (type == Map.class) return Map.of("k", "v");
        if (type == List.class) return List.of("a");
        return null;
    }

    private static JsonNode firstModel(ModelConfigOverrideEntity m) throws Exception {
        byte[] bytes = CatalogBundlePayload.canonicalBytes(
                1L, 2, "cloud", Instant.parse("2026-01-01T00:00:00Z"), List.of(m));
        return MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8)).path("models").path(0);
    }

    @Test
    @DisplayName("every field the apply writes is emitted, or the apply CLEARS it on the other side")
    void everyAppliedFieldIsCarried() throws Exception {
        JsonNode model = firstModel(fullyPopulated());

        List<String> missing = new ArrayList<>();
        for (String field : new TreeSet<>(CatalogMergeService.APPLIED_FIELD_NAMES)) {
            if (!model.has(field)) {
                missing.add(field);
            }
        }

        assertThat(missing)
                .as("CatalogMergeService applies these fields but CatalogBundlePayload never emits them. "
                        + "The apply runs with partialUpdate=false, so on every cloud-linked install each "
                        + "of them is overwritten to null, on the sync schedule, with no error anywhere. "
                        + "Emit them in toCanonicalMap, or take them out of APPLIED_FIELD_NAMES.")
                .isEmpty();
    }

    @Test
    @DisplayName("the fixture really is populated, so an empty sweep cannot make the test vacuous")
    void theFixtureIsNotEmpty() throws Exception {
        // Without this, a reflection sweep that silently set nothing would leave
        // every putIfNotNull skipped and the assertion above would pass on a
        // producer that emits almost nothing.
        JsonNode model = firstModel(fullyPopulated());

        assertThat(model.size())
                .as("the reflective fixture must actually populate the entity")
                .isGreaterThanOrEqualTo(CatalogMergeService.APPLIED_FIELD_NAMES.size());
    }

    @Test
    @DisplayName("a field the cloud has no value for is still ABSENT, which is what makes the apply clear it")
    void anEmptyModelEmitsOnlyIdentity() throws Exception {
        // The other half of the contract, stated so the reason for the parity
        // rule is visible: absence is how the cloud says "no value", so it can
        // never also be allowed to mean "this producer forgot".
        ModelConfigOverrideEntity bare = new ModelConfigOverrideEntity();
        bare.setProvider("openai");
        bare.setModelId("gpt-test");

        JsonNode model = firstModel(bare);

        assertThat(model.has("supportsReasoning")).isFalse();
        assertThat(model.path("provider").asText()).isEqualTo("openai");
    }
}
