package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract of the persisted-generation request.
 *
 * <p>This DTO is serialized by one service and deserialized by another, so a
 * rolling deploy always runs one side ahead of the other. These tests pin the
 * two directions that has to survive: a body from an older sender still
 * deserializes, and the field added for the generation shape travels under the
 * name the receiver reads.
 */
@DisplayName("ImageGenerationInterfaceRequest")
class ImageGenerationInterfaceRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("carries the prompt the generation result does not repeat")
    void serializesThePromptField() throws Exception {
        ImageGenerationInterfaceRequest request = new ImageGenerationInterfaceRequest();
        request.setName("a paper boat");
        request.setPrompt("a paper boat drifting down a rain gutter");
        request.setData(Map.of("model", "seedance-2.0-fast",
                "file", Map.of("_type", "file", "path", "tenant-1/gen/clip.mp4")));

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"prompt\":\"a paper boat drifting down a rain gutter\"");
        ImageGenerationInterfaceRequest back =
                mapper.readValue(json, ImageGenerationInterfaceRequest.class);
        assertThat(back.getPrompt()).isEqualTo("a paper boat drifting down a rain gutter");
        assertThat(back.getData()).containsKey("file");
    }

    @Test
    @DisplayName("a body from a sender that predates the prompt field still deserializes")
    void acceptsABodyWithoutThePromptField() throws Exception {
        String legacyBody = "{\"name\":\"a paper boat\",\"conversation_id\":\"conv-1\","
                + "\"message_id\":\"msg-1\",\"agent_id\":\"agent-1\","
                + "\"data\":{\"images\":[{\"path\":\"tenant-1/img/a.png\"}],"
                + "\"provider\":\"openai\",\"billing_model\":\"gpt-image-1.5-medium\"}}";

        ImageGenerationInterfaceRequest request =
                mapper.readValue(legacyBody, ImageGenerationInterfaceRequest.class);

        assertThat(request.getPrompt()).isNull();
        assertThat(request.getConversationId()).isEqualTo("conv-1");
        assertThat(request.getData()).containsKey("images");
    }

    @Test
    @DisplayName("an unknown field from a newer sender is ignored, not a deserialization failure")
    void ignoresUnknownFields() throws Exception {
        String futureBody = "{\"name\":\"clip\",\"prompt\":\"a clip\",\"kind_hint\":\"video\"}";

        ImageGenerationInterfaceRequest request =
                mapper.readValue(futureBody, ImageGenerationInterfaceRequest.class);

        assertThat(request.getName()).isEqualTo("clip");
        assertThat(request.getPrompt()).isEqualTo("a clip");
    }
}
