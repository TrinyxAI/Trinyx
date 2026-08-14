package com.apimarketplace.catalog.tools.generation;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A format is advertised only while a model of that format exists.
 *
 * <p>An agent reads the tool description and the {@code kind} parameter, calls
 * {@code action='models'} with the format it was offered, and gets nothing
 * back. It has no way to find out that the format was retired: the tool told it
 * the format exists, and an empty list looks like a transient catalogue.
 * Advertising a format nobody serves therefore costs a turn every time, and it
 * is a lie the platform tells about itself.
 *
 * <p>That is exactly what happened when the legacy image tool was deleted: the
 * only image models on the platform went with it, while three separate places
 * here kept naming {@code image}. This pins the invariant instead of the
 * incident - the advertised set and the shipped set are the SAME set, in both
 * directions, whichever format is added or removed next.
 *
 * <p>The shipped set is read from the CE generation seed, which is the artefact
 * a fresh install actually boots with, generated verbatim from the
 * {@code generation} blocks in {@code scripts/api-migrations/*.json}. So a
 * format cannot be advertised on the strength of an authoring file that never
 * reached an install.
 */
@DisplayName("the generation tool advertises exactly the formats the platform ships")
class GenerationAdvertisedKindsMatchTheSeedTest {

    private static final String SEED = "catalog-seeds/generation-seed.json";

    /** Every {@code kind} the shipped seed carries at least one model for. */
    private static Set<String> shippedKinds() throws Exception {
        try (InputStream in = GenerationAdvertisedKindsMatchTheSeedTest.class
                .getClassLoader().getResourceAsStream(SEED)) {
            assertThat(in).as("the CE generation seed must ship on the classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            Set<String> kinds = new LinkedHashSet<>();
            for (JsonNode endpoint : root.path("endpoints")) {
                JsonNode spec = endpoint.path("generationSpec");
                if (spec.path("models").isEmpty()) {
                    continue;
                }
                kinds.add(spec.path("kind").asText());
            }
            return kinds;
        }
    }

    @Test
    @DisplayName("the tool does not advertise `n` as a way to get several assets, because one call returns one")
    void nIsNotOfferedAsAWayToGetSeveral() {
        // The sentence this replaces - "Models priced per image bill on this
        // value" - survived in two help layers after the DB documentation row
        // was corrected, in the very commit that corrected it. It is the shape
        // of drift this file exists for: the platform charges `n` times and
        // stores one asset, so an agent acting on that sentence is overbilled
        // and told nothing.
        String description = tool().parameters().stream()
                .filter(p -> "n".equals(p.name()))
                .map(ToolParameter::description)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the tool no longer has an 'n' parameter"));

        assertThat(description).doesNotContain("priced per image bill on this");
        assertThat(description).containsIgnoringCase("one");

        // And no DEFAULT: a client that materialises schema defaults would
        // send n on every call, and since no model accepts it the platform
        // would refuse every generation for a value it supplied itself.
        ToolParameter n = tool().parameters().stream()
                .filter(p -> "n".equals(p.name())).findFirst().orElseThrow();
        assertThat(n.defaultValue()).isNull();
    }

    private static AgentToolDefinition tool() {
        return new GenerationToolsProvider(mock(GenerationModule.class)).getTools().get(0);
    }

    private static String kindParameterDescription() {
        return tool().parameters().stream()
                .filter(p -> "kind".equals(p.name()))
                .map(ToolParameter::description)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the tool no longer has a 'kind' parameter"));
    }

    static List<String> theShippedKinds() throws Exception {
        return List.copyOf(shippedKinds());
    }

    @Test
    @DisplayName("the seed still ships every format the tool is written around")
    void theSeedShipsAllFiveFormats() throws Exception {
        // Stated as a literal so a format silently disappearing from the seed
        // fails HERE, naming it, rather than making the checks below vacuous by
        // shrinking the set they iterate over.
        assertThat(shippedKinds())
                .containsExactlyInAnyOrder("image", "video", "audio", "voice", "music");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("theShippedKinds")
    @DisplayName("every shipped format is named by the description, the kind parameter and the tags")
    void everyShippedKindIsAdvertised(String kind) {
        AgentToolDefinition tool = tool();
        assertThat(tool.description().toLowerCase(Locale.ROOT))
                .as("the tool description must name the '%s' format an agent can ask for", kind)
                .contains(kind);
        assertThat(kindParameterDescription().toLowerCase(Locale.ROOT))
                .as("the kind parameter must list '%s' among the values it accepts", kind)
                .contains(kind);
        assertThat(tool.tags())
                .as("discovery searches the tags, so '%s' must be findable there", kind)
                .contains(kind);
    }

    /**
     * The formats the {@code kind} parameter OFFERS, read out of the sentence
     * that offers them ("... to one format: image, video, audio, voice, music.
     * Omit to list everything.").
     *
     * <p>Read rather than restated, because a restated list is the thing this
     * file exists to catch. An earlier version of the check below walked a
     * hardcoded list of the five known formats and skipped every shipped one,
     * so with all five shipped its body never executed: it could not fail, in
     * any direction, for any edit.
     */
    private static Set<String> advertisedKinds() {
        String description = kindParameterDescription();
        int colon = description.indexOf(':');
        int stop = colon < 0 ? -1 : description.indexOf('.', colon);
        assertThat(colon).as("the kind parameter must list its formats after a colon, "
                + "which is how this test reads them: <%s>", description).isGreaterThan(-1);
        assertThat(stop).as("the format list must end in a full stop, "
                + "which is how this test reads them: <%s>", description).isGreaterThan(colon);
        Set<String> advertised = new LinkedHashSet<>();
        for (String word : description.substring(colon + 1, stop).split(",")) {
            String kind = word.trim().toLowerCase(Locale.ROOT);
            if (!kind.isEmpty()) {
                advertised.add(kind);
            }
        }
        assertThat(advertised).as("no format could be read out of the kind parameter, so the check "
                + "below would pass while advertising anything: <%s>", description).isNotEmpty();
        return advertised;
    }

    @Test
    @DisplayName("and nothing else is advertised, so no agent is sent after a format nobody serves")
    void nothingElseIsAdvertised() throws Exception {
        // Set equality, both directions, over the formats actually READ off the
        // parameter. A sixth format offered here without a model in the seed
        // fails, which is the lie this file is about; a format dropped from the
        // seed and left offered fails too.
        assertThat(advertisedKinds())
                .as("the kind parameter must offer exactly the formats the seed ships a model for")
                .containsExactlyInAnyOrderElementsOf(shippedKinds());
    }
}
