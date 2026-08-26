package com.apimarketplace.common.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place a recipe is read out of the JSON it is stored in.
 *
 * <p>Its callers are a file viewer and a list, and its input is a column shared with any other
 * producer that annotates a storage row - so the property that matters is not what it parses but
 * what it REFUSES to be broken by. Everything unreadable answers null, which both callers already
 * handle as "this asset has no recipe": the viewer draws no card, the list drops the row. A throw
 * here would take down a screen over one odd row written by something else entirely.
 */
@DisplayName("GenerationProvenanceReader")
class GenerationProvenanceReaderTest {

    @Nested
    @DisplayName("from the generation object alone")
    class FromRecipe {

        @Test
        @DisplayName("reads the recipe")
        void readsTheRecipe() {
            assertThat(GenerationProvenanceReader.fromRecipeJson(
                    "{\"model\":\"flux-1.1-pro\",\"prompt\":\"a lighthouse\"}"))
                    .containsEntry("model", "flux-1.1-pro")
                    .containsEntry("prompt", "a lighthouse");
        }

        @Test
        @DisplayName("answers null for anything it cannot use")
        void nullForAnythingUnusable() {
            assertThat(GenerationProvenanceReader.fromRecipeJson(null)).isNull();
            assertThat(GenerationProvenanceReader.fromRecipeJson("  ")).isNull();
            assertThat(GenerationProvenanceReader.fromRecipeJson("not json at all")).isNull();
            // An empty object reproduces nothing, so it is not a recipe.
            assertThat(GenerationProvenanceReader.fromRecipeJson("{}")).isNull();
        }
    }

    @Nested
    @DisplayName("from the whole metadata column")
    class FromMetadata {

        @Test
        @DisplayName("picks the recipe out and leaves the rest alone")
        void picksTheRecipeOut() {
            assertThat(GenerationProvenanceReader.fromMetadataJson(
                    "{\"screenshot\":{\"width\":1080},\"generation\":{\"model\":\"flux-1.1-pro\"}}"))
                    .containsEntry("model", "flux-1.1-pro")
                    .doesNotContainKey("screenshot");
        }

        @Test
        @DisplayName("answers null for a file that simply was not generated")
        void nullForAnUngeneratedFile() {
            // The ordinary case: almost every file in a workspace was uploaded or written by a
            // workflow, and the viewer asks about all of them.
            assertThat(GenerationProvenanceReader.fromMetadataJson(null)).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("{}")).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("{\"screenshot\":{\"width\":1080}}")).isNull();
        }

        @Test
        @DisplayName("refuses a value under the key that is not a recipe")
        void refusesANonObjectRecipe() {
            // Nothing stops another writer, or a hand-edited row, from putting a scalar there. Read
            // as a recipe it would put a number in front of a Regenerate button.
            assertThat(GenerationProvenanceReader.fromMetadataJson("{\"generation\":5}")).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("{\"generation\":null}")).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("{\"generation\":[]}")).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("{\"generation\":{}}")).isNull();
        }

        @Test
        @DisplayName("survives metadata that is not an object at all")
        void survivesNonObjectMetadata() {
            assertThat(GenerationProvenanceReader.fromMetadataJson("\"legacy string\"")).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("[1,2,3]")).isNull();
            assertThat(GenerationProvenanceReader.fromMetadataJson("broken {")).isNull();
        }
    }
}
