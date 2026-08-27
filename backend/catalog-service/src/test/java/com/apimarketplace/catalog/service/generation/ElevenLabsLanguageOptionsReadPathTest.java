package com.apimarketplace.catalog.service.generation;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.execution.OutputProjector;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole read path for a language dropdown, wired from the SHIPPED seed.
 *
 * <p><b>Why this exists.</b> Every layer of this feature was unit-tested against
 * a fixture the test itself wrote, and the field still came back empty in
 * production: "it loads, then nothing". A fixture proves the code does what the
 * author expected of a shape the author invented. What was never checked is that
 * the descriptor in {@code elevenlabs.json}, the {@code outputSchema} beside it,
 * and the projector that runs between them agree with each other.
 *
 * <p>So this reads the real seed off disk, projects a provider payload through
 * the real {@link OutputProjector} with that endpoint's real schema, and asks
 * the real resolver to read it. Nothing here is written twice, which is the
 * point: a change to the seed that breaks the descriptor fails HERE rather than
 * in front of someone opening the dialog.
 */
class ElevenLabsLanguageOptionsReadPathTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID TTS = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID API = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
    private static final UUID SOURCE_NAME = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");

    /**
     * A response shaped like the provider's, with the two fields its API
     * reference documents on each language entry. The VALUES are illustrative;
     * what is under test is that the declared paths reach them.
     */
    private static final String PROVIDER_PAYLOAD = """
            [
              {"model_id": "eleven_v3", "name": "Eleven v3",
               "languages": [{"language_id": "en", "name": "English"},
                             {"language_id": "ja", "name": "Japanese"}]},
              {"model_id": "eleven_flash_v2_5", "name": "Flash v2.5",
               "languages": [{"language_id": "fr", "name": "French"}]}
            ]""";

    private static JsonNode seed() throws IOException {
        Path p = Path.of("..", "..", "scripts", "api-migrations", "elevenlabs.json");
        if (!Files.exists(p)) p = Path.of("scripts", "api-migrations", "elevenlabs.json");
        return MAPPER.readTree(Files.readString(p));
    }

    private static JsonNode endpoint(JsonNode seed, String name) {
        for (JsonNode ep : seed.get("endpoints")) {
            if (name.equals(ep.path("name").asText())) return ep;
        }
        throw new IllegalStateException("no endpoint " + name);
    }

    @Test
    @DisplayName("the shipped descriptor reads a model's languages out of the shipped schema")
    void theSeedsOwnDescriptorReadsTheSeedsOwnSchema() throws Exception {
        JsonNode seed = seed();
        JsonNode tts = endpoint(seed, "text_to_speech");
        JsonNode listModels = endpoint(seed, "list_models");

        // The descriptor exactly as it ships.
        JsonNode extras = null;
        for (JsonNode p : tts.get("params")) {
            if ("language_code".equals(p.path("name").asText())) extras = p.get("valuesFrom");
        }
        assertThat(extras).as("language_code must declare valuesFrom in the seed").isNotNull();

        // The projection the platform actually performs before anything reads it.
        Object projected = new OutputProjector(MAPPER).project(
                MAPPER.readValue(PROVIDER_PAYLOAD, Object.class),
                MAPPER.writeValueAsString(listModels.get("outputSchema")));

        // The envelope the delegated execute hands back, built the way
        // ToolExecutionManager builds it rather than the way this test found it
        // convenient to assume. That assumption is the whole reason this suite
        // was green while the field was empty in front of someone: the endpoint
        // answers with an ARRAY at the root, which has no object for the
        // httpStatus to be merged into, so execution moves the list under `data`
        // to carry it. Written here as a helper so the test cannot drift back
        // into inventing a shape.
        Object body = executionEnvelope(projected);

        ApiToolParameterRepository params = mock(ApiToolParameterRepository.class);
        ApiToolRepository tools = mock(ApiToolRepository.class);
        var names = mock(com.apimarketplace.catalog.repository.ToolNameRepository.class);
        CredentialClient credentials = mock(CredentialClient.class);
        when(credentials.getCredentialStateVersion(anyString())).thenReturn("v1");

        ApiToolParameterEntity param = new ApiToolParameterEntity();
        param.setName("language_code");
        param.setExtras(MAPPER.writeValueAsString(Map.of("valuesFrom",
                MAPPER.convertValue(extras, Object.class))));
        when(params.findByApiToolId(TTS)).thenReturn(List.of(param));

        when(tools.findById(TTS)).thenReturn(Optional.of(tool(TTS, "elevenlabs-text-to-speech", "POST", null)));
        when(tools.findByApiId(API)).thenReturn(List.of(
                tool(TTS, "elevenlabs-text-to-speech", "POST", null),
                tool(SOURCE, "elevenlabs-list-models", "GET", SOURCE_NAME)));
        ToolNameEntity named = new ToolNameEntity();
        named.setId(SOURCE_NAME);
        named.setName("list_models");
        when(names.findByNameAndIsActiveTrue("list_models")).thenReturn(List.of(named));

        DynamicOptionsResolver resolver =
                new DynamicOptionsResolver(tools, params, names, credentials, MAPPER);

        DynamicOptionsResolver.Resolution r = resolver.resolve(
                TTS, "language_code", "u-1", "user", null,
                // The id the module supplies: the model's UPSTREAM value, which
                // is what the provider itself keys its rows on.
                Map.of("model", "eleven_v3"),
                (id, source, credential) ->
                        new DynamicOptionsResolver.SourceFetcher.Answer(true, body, "user", false));

        assertThat(r.isAvailable()).as("reason when unavailable: %s", r.unavailable()).isTrue();
        assertThat(r.options()).containsExactly(
                new DynamicOptionsResolver.Option("en", "English"),
                new DynamicOptionsResolver.Option("ja", "Japanese"));
    }

    /**
     * What the delegated execute actually hands back, reproduced from
     * {@code ToolExecutionManager}'s own rule: {@code httpStatus} is merged INTO
     * the answer when it is an object, and the answer is moved under
     * {@code data} when there is no object to merge into.
     */
    private static Object executionEnvelope(Object projected) {
        Object result;
        if (projected instanceof Map<?, ?> map) {
            Map<String, Object> merged = new java.util.LinkedHashMap<>(
                    MAPPER.convertValue(map, new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.LinkedHashMap<String, Object>>() {}));
            merged.put("httpStatus", Map.of("code", 200));
            result = merged;
        } else {
            result = Map.of("data", projected, "httpStatus", Map.of("code", 200));
        }
        return Map.of("success", true, "result", result,
                "metadata", Map.of("credentialSource", "user"));
    }

    private static ApiToolEntity tool(UUID id, String slug, String method, UUID nameId) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(id);
        t.setApiId(API);
        t.setToolSlug(slug);
        t.setMethod(method);
        t.setToolNameId(nameId == null ? null : nameId.toString());
        return t;
    }
}
