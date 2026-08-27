package com.apimarketplace.common.storage.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One generated asset, with the recipe it was made from.
 *
 * <p>A row of the generation history: the file itself (enough of it to preview and to name), plus
 * the raw {@code generation} object out of the row's {@code metadata}. The recipe stays JSON text
 * here rather than being parsed into a type: its shape is the model's parameter set, which differs
 * per model and grows with the catalogue, so a fixed type would have to be widened every time a
 * provider gains a parameter and would silently drop the ones it did not know about.
 */
public record GenerationHistoryProjection(
    UUID id,
    String fileName,
    String mimeType,
    Integer sizeBytes,
    Instant createdAt,
    String s3Key,
    /**
     * The {@code generation} object, verbatim JSON.
     *
     * <p>The query only selects rows that carry the key, so this is normally present - but the key
     * can hold a JSON null or a value no reader can use, and the mapper does not second-guess the
     * database. A row whose recipe cannot be read is dropped one layer up, in
     * {@link GenerationHistoryDto#from}.
     */
    String provenanceJson
) {}
