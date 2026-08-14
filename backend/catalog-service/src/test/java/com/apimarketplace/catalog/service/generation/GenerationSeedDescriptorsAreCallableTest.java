package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every descriptor the platform SHIPS can actually be called.
 *
 * <p>Onboarding a generation provider is a JSON block plus an import, with no
 * Java to write. The price of that is that a mistake in the JSON surfaces at
 * the worst possible moment: the authoring gate is a Python script nobody runs
 * on a whim, {@code GenerationRegistry} skips an unparseable row with a log
 * line rather than failing, and a model that cannot state the size of a call is
 * only WARNED about at snapshot time. The reader finds out when their
 * generation is refused.
 *
 * <p>So this reads the artefact a fresh install actually boots with and puts
 * every block through the same parser the import and the runtime use. It is the
 * cheapest possible guard on the whole catalogue at once, and it grows by
 * itself: a provider added to {@code scripts/api-migrations/} lands in the seed
 * and is covered here without anybody remembering to extend a list.
 */
@DisplayName("the shipped generation seed is callable, model by model")
class GenerationSeedDescriptorsAreCallableTest {

    private static final String SEED = "catalog-seeds/generation-seed.json";

    /** One shipped endpoint: where it came from, and what it declares. */
    private record SeededEndpoint(String source, String endpoint, GenerationSpec spec) {}

    private static List<SeededEndpoint> shipped() throws Exception {
        try (InputStream in = GenerationSeedDescriptorsAreCallableTest.class
                .getClassLoader().getResourceAsStream(SEED)) {
            assertThat(in).as("the CE generation seed must ship on the classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            List<SeededEndpoint> out = new ArrayList<>();
            for (JsonNode endpoint : root.path("endpoints")) {
                String source = endpoint.path("sourceFile").asText("?")
                        + ":" + endpoint.path("endpointName").asText("?");
                // Parsing IS the assertion: a malformed block throws with the
                // offending field named, which is the same failure the import
                // would produce, minus the trip to production.
                GenerationSpec spec = GenerationSpec.parse(endpoint.path("generationSpec"), source)
                        .orElseThrow(() -> new AssertionError(
                                source + " is listed in the seed but declares no generation block"));
                out.add(new SeededEndpoint(source, endpoint.path("endpointName").asText("?"), spec));
            }
            return out;
        }
    }

    @Test
    @DisplayName("every block parses, which is what the import would refuse and the runtime would skip")
    void everyBlockParses() throws Exception {
        List<SeededEndpoint> endpoints = shipped();

        assertThat(endpoints)
                .as("the seed must ship at least the endpoints the platform advertises")
                .isNotEmpty();
        assertThat(endpoints).allSatisfy(e ->
                assertThat(e.spec().models()).as(e.source()).isNotEmpty());
    }

    @Test
    @DisplayName("every model can state the size of a call, or its price multiplies a number nobody supplied")
    void everyModelCanStateItsSize() throws Exception {
        // A model priced per second, per image or per character has to be able
        // to say how big each call is: the caller states it, the model defaults
        // it, or the model refuses the call naming it. A model that does none of
        // those is listed, quoted, and then refuses EVERY call that omits the
        // parameter - a failure the registry only writes to a log.
        List<String> unsized = new ArrayList<>();
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                if (!model.canAlwaysStateItsSize()) {
                    unsized.add(endpoint.source() + " -> " + model.id()
                            + " (priced per " + model.price().unit()
                            + ", measured by '" + model.measuringParam()
                            + "', which it neither defaults nor requires)");
                }
            }
        }

        assertThat(unsized)
                .as("give the measuring parameter an 'allowed' list of sizes, or list it as required")
                .isEmpty();
    }

    @Test
    @DisplayName("model ids are unique across the whole catalogue, since one shadows the other in the registry")
    void modelIdsAreGloballyUnique() throws Exception {
        // The registry indexes by model id across every endpoint and keeps the
        // FIRST registration on a clash, so a duplicate does not fail: it
        // silently makes one provider's model unreachable, and the reader sees
        // the other one's price and limits under the id they asked for.
        Map<String, String> owner = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                String previous = owner.putIfAbsent(model.id(), endpoint.source());
                if (previous != null) {
                    clashes.add("'" + model.id() + "' claimed by " + previous
                            + " and " + endpoint.source());
                }
            }
        }

        assertThat(clashes).isEmpty();
    }

    @Test
    @DisplayName("every model builds a real request from a plain call, and the provider gets the model it is priced for")
    void everyModelProjectsAPlainCall() throws Exception {
        // The descriptor is only half a promise until something projects it. A
        // path typo, a required parameter nobody can satisfy or a model selector
        // that never reaches the body all produce the same outcome: the call is
        // dispatched, CHARGED, and comes back with nothing usable. Building each
        // model here costs nothing and is the only check that reads the
        // descriptor the way a real call does.
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                Map<String, Object> unified = new LinkedHashMap<>();
                // What a caller minimally supplies: the instruction, plus
                // whatever this model refuses to run without.
                if (model.accepts("prompt")) {
                    unified.put("prompt", "a paper boat drifting down a rain gutter");
                }
                for (String required : model.required()) {
                    if (unified.containsKey(required)) continue;
                    unified.put(required, plausibleValue(model, required));
                }

                GenerationRequestBuilder.Built built =
                        GenerationRequestBuilder.build(endpoint.spec(), model, unified);

                String where = endpoint.source() + " -> " + model.id();
                assertThat(built.errors()).as(where).isEmpty();
                assertThat(built.params()).as(where).isNotEmpty();

                // The value the caller is BILLED for is the model they asked
                // for, so the selector has to be in the body the provider reads.
                // A modelParam that never lands is how a cheap model is quoted
                // and an expensive one runs.
                if (endpoint.spec().sendsModelParam()) {
                    assertThat(GenerationRequestBuilder.getByPath(
                            built.params(), endpoint.spec().modelParam()))
                            .as(where + " must send its model selector")
                            .isEqualTo(model.upstream());
                }
                if (model.accepts("prompt")) {
                    assertThat(GenerationRequestBuilder.getByPath(
                            built.params(), endpoint.spec().paramMap().get("prompt").path()))
                            .as(where + " must carry the prompt where it says it does")
                            .isEqualTo("a paper boat drifting down a rain gutter");
                }
            }
        }
    }

    /**
     * A value this model would accept for one of its required parameters.
     *
     * <p>Taken from the model's OWN declared constraint whenever it has one, so
     * the test never invents a value the descriptor would refuse and never has
     * to be updated when a provider changes its allowed sizes.
     */
    private static Object plausibleValue(GenerationSpec.Model model, String param) {
        GenerationSpec.Constraint constraint = model.constraints().get(param);
        if (constraint != null && !constraint.allowed().isEmpty()) {
            return constraint.allowed().get(0);
        }
        if (constraint != null && constraint.min() != null) {
            return constraint.min();
        }
        return "1";
    }

    @Test
    @DisplayName("no shipped model lets a caller ask for more than one asset, since only one is ever stored")
    void noModelSellsMoreAssetsThanItReturns() throws Exception {
        // One call fetches and stores exactly ONE asset, while a price unit of
        // 'image' multiplies `n`. A model that accepts a bigger `n` is charged
        // for every one of them and hands back the first, so the customer pays
        // for assets that never existed as far as they can tell.
        //
        // The authoring gate refuses this, but only for descriptors that go
        // through it. This asserts it on the artefact an install actually boots
        // with, which is also the shape a signed catalog bundle carries.
        List<String> oversold = new ArrayList<>();
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                if (!model.accepts("n")) continue;
                GenerationSpec.Constraint limit = model.constraints().get("n");
                boolean cappedAtOne = limit != null
                        && (List.of(1).equals(limit.allowed())
                            || (limit.max() != null && limit.max().intValueExact() == 1));
                if (!cappedAtOne) {
                    oversold.add(endpoint.source() + " -> " + model.id());
                }
            }
        }

        assertThat(oversold)
                .as("cap n at 1, or drop it and price the model per 'call'")
                .isEmpty();
    }

    @Test
    @DisplayName("every declared capability can actually be sent, so an accepted parameter is never dropped")
    void everyCapabilityHasAnUpstreamMapping() throws Exception {
        // A capability with no paramMap entry is a parameter the surfaces offer,
        // the validator accepts and the provider never sees. Parsing already
        // refuses it, so this exists to state the invariant on the SHIPPED set:
        // it is what makes "the model accepts it" mean "the model receives it".
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                assertThat(endpoint.spec().paramMap().keySet())
                        .as(endpoint.source() + " -> " + model.id())
                        .containsAll(model.capabilities());
            }
        }
    }
}
