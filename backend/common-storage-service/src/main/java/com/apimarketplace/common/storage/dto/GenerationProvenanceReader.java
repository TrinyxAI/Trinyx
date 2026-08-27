package com.apimarketplace.common.storage.dto;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Read a generated asset's recipe out of the JSON it is stored in.
 *
 * <p>One reader, because there are two callers with the same job and one of them holds the whole
 * {@code metadata} column while the other holds the {@code generation} object already extracted by
 * the query. Written twice they would drift on the part that matters: how much a malformed value is
 * allowed to break.
 *
 * <p><b>Everything unreadable answers null.</b> The column is shared with any other producer that
 * annotates a storage row, a legacy row may hold anything, and the callers are a file viewer and a
 * list: neither may be taken down by one odd row. A null says "this asset has no recipe I can use",
 * which is what both callers already do something sensible with.
 *
 * <p>The mapper is this class's own, at default settings, deliberately NOT an injected bean: an
 * application mapper configured to fail on unknown properties would turn "this row carries metadata
 * I do not recognise" into a 500 on the screen showing the file.
 */
public final class GenerationProvenanceReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GenerationProvenanceReader() {
    }

    /**
     * The recipe, from the {@code generation} object on its own.
     *
     * @return the recipe, or null when it is absent, empty or unreadable
     */
    public static Map<String, Object> fromRecipeJson(String recipeJson) {
        if (recipeJson == null || recipeJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> recipe = MAPPER.readValue(recipeJson, new TypeReference<>() {});
            return recipe == null || recipe.isEmpty() ? null : recipe;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The recipe, from the whole {@code metadata} column.
     *
     * @return the recipe, or null when the row carries none (the ordinary case: almost every file
     *         in a workspace was uploaded or written by a workflow, not generated)
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromMetadataJson(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            Object parsed = MAPPER.readValue(metadataJson, Map.class);
            if (!(parsed instanceof Map<?, ?> metadata)) {
                return null;
            }
            Object recipe = metadata.get(GenerationProvenanceFields.METADATA_KEY);
            // A non-object under the key is not a recipe. It cannot reproduce anything, and reading
            // it as one would put a number or a string in front of a Regenerate button.
            return recipe instanceof Map<?, ?> map && !map.isEmpty()
                    ? (Map<String, Object>) map
                    : null;
        } catch (Exception e) {
            return null;
        }
    }
}
