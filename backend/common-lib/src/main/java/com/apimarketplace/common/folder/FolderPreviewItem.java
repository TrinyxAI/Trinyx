package com.apimarketplace.common.folder;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One item shown INSIDE a folder tile. The folder tile mimics the card of whatever it
 * holds - a workflow folder shows little node-icon rows on the builder's dotted canvas,
 * an agent folder shows avatars - so this record carries the few fields those cards
 * paint, and each list page reads only the ones its own card style uses.
 *
 * <p>Null fields are omitted from the JSON, so a workflow preview carries no
 * {@code imageUrl} and an agent preview carries no {@code icons}.
 *
 * @param id       the resource id (opaque - UUID for most types, numeric for tables)
 * @param name     the resource name, used as the tile's tooltip
 * @param icons    node-icon descriptors, as stored on the resource (workflows)
 * @param imageUrl avatar / thumbnail / cover URL (agents, applications, interfaces)
 * @param subtitle a short secondary line (e.g. a table's row count)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolderPreviewItem(
        String id,
        String name,
        List<Map<String, Object>> icons,
        String imageUrl,
        String subtitle) {

    public static FolderPreviewItem withIcons(String id, String name, List<Map<String, Object>> icons) {
        return new FolderPreviewItem(id, name, icons, null, null);
    }

    public static FolderPreviewItem withImage(String id, String name, String imageUrl) {
        return new FolderPreviewItem(id, name, null, imageUrl, null);
    }
}
