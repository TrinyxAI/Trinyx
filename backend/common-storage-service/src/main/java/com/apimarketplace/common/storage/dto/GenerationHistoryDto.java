package com.apimarketplace.common.storage.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One entry of the generation history, as the browser reads it.
 *
 * <p>Carries the asset (enough to preview it and to name it) and the recipe it was made from. The
 * two are one storage row, so an entry can never point at a file that no longer exists.
 *
 * <p>{@code provenance} is deliberately an open map rather than a typed shape: its parameters are
 * the chosen model's own, which differ per model and grow with the catalogue. A fixed type would
 * need widening every time a provider gains a parameter and would silently drop the ones it did not
 * know about - which, for a payload whose whole job is to be handed back verbatim to reproduce the
 * asset, means quietly generating something else.
 */
public record GenerationHistoryDto(
    UUID id,
    String fileName,
    String mimeType,
    Integer sizeBytes,
    String formattedSize,
    Instant createdAt,
    String s3Key,
    Map<String, Object> provenance
) {

    /**
     * Map a projection, parsing the recipe out of its JSON text.
     *
     * <p>Returns {@code null} when the recipe cannot be read. The caller drops such a row rather
     * than showing it: an entry with no recipe offers a Regenerate button that cannot regenerate,
     * and there is nothing else in it a reader could not already see in their files.
     */
    public static GenerationHistoryDto from(GenerationHistoryProjection p) {
        Map<String, Object> provenance = GenerationProvenanceReader.fromRecipeJson(p.provenanceJson());
        if (provenance == null || provenance.isEmpty()) {
            return null;
        }
        return new GenerationHistoryDto(
            p.id(),
            p.fileName(),
            p.mimeType(),
            p.sizeBytes(),
            // The file grid's own rendering, not a copy of it: the same row seen from two
            // screens must not report two different sizes.
            StorageExplorerDto.formatBytes(p.sizeBytes()),
            p.createdAt(),
            p.s3Key(),
            provenance
        );
    }

}
