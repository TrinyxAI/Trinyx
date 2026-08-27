package com.apimarketplace.common.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One entry of the generation history, as the browser reads it.
 *
 * <p>The recipe is parsed rather than passed through as text, because the browser hands it straight
 * back to reproduce the asset. What matters here is the failure case: a row whose recipe cannot be
 * read is DROPPED, not shown with an empty one - an entry offering to reproduce an asset from a
 * recipe nobody can read is a button that quietly makes something else.
 */
@DisplayName("GenerationHistoryDto")
class GenerationHistoryDtoTest {

    private static GenerationHistoryProjection projection(String provenanceJson) {
        return new GenerationHistoryProjection(
                UUID.randomUUID(), "flux.png", "image/png", 2048,
                Instant.parse("2026-08-24T10:00:00Z"),
                "1/general/flux.png", provenanceJson);
    }

    @Test
    @DisplayName("parses the recipe so the browser gets an object, not a string to re-parse")
    void parsesTheRecipe() {
        GenerationHistoryDto dto = GenerationHistoryDto.from(projection(
                "{\"model\":\"flux-1.1-pro\",\"kind\":\"image\",\"prompt\":\"a lighthouse\"}"));

        assertThat(dto).isNotNull();
        assertThat(dto.provenance())
                .containsEntry("model", "flux-1.1-pro")
                .containsEntry("prompt", "a lighthouse");
    }

    @Test
    @DisplayName("keeps a parameter this build has never heard of")
    void keepsUnknownParameters() {
        // The recipe's keys are the model's own and grow with the catalogue. Anything dropped here
        // is a parameter the re-run would silently change.
        GenerationHistoryDto dto = GenerationHistoryDto.from(projection(
                "{\"model\":\"m\",\"params\":{\"brand_new_knob\":7}}"));

        assertThat(dto).isNotNull();
        assertThat(dto.provenance()).containsKey("params");
        assertThat(dto.provenance().get("params").toString()).contains("brand_new_knob");
    }

    @Test
    @DisplayName("drops a row whose recipe cannot be read")
    void dropsAnUnreadableRecipe() {
        assertThat(GenerationHistoryDto.from(projection("not json at all"))).isNull();
        assertThat(GenerationHistoryDto.from(projection("{}"))).isNull();
        assertThat(GenerationHistoryDto.from(projection(null))).isNull();
    }

    @Test
    @DisplayName("renders the size the way every other file row does")
    void formatsTheSizeLikeAFileRow() {
        // One file must not read as "2048" on one screen and "2.0 KB" on another - they are the
        // same storage row. Compared against the file grid's own renderer rather than a literal,
        // which is the property that matters and is what a copy of the formatter would break.
        GenerationHistoryDto dto = GenerationHistoryDto.from(projection("{\"model\":\"m\"}"));

        assertThat(dto).isNotNull();
        assertThat(dto.formattedSize()).isEqualTo(StorageExplorerDto.formatBytes(2048));
    }
}
