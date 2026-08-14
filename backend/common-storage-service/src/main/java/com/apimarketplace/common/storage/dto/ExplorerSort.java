package com.apimarketplace.common.storage.dto;

/**
 * User-chosen ordering for a Storage Explorer listing.
 *
 * <p>The Files browser exposes four sort criteria; everything else about the listing (folders
 * before files, the virtual workflow tree's own sequence) is unchanged by the choice. The key is
 * a closed enum precisely so the ORDER BY fragment the repository builds is a CONSTANT string
 * chosen by a switch - no request text ever reaches SQL.</p>
 *
 * @param key       what to order by
 * @param ascending {@code true} for A→Z / oldest-first / smallest-first, {@code false} for the
 *                  reverse. The default listing is {@code DATE} descending (newest first), which
 *                  is the ordering every Files surface had before sorting was configurable.
 */
public record ExplorerSort(Key key, boolean ascending) {

    /** The orderable criteria offered to the user. */
    public enum Key {
        /** {@code created_at} - for a folder, its last activity. */
        DATE,
        /** {@code file_name}, case-insensitive. */
        NAME,
        /** {@code size_bytes}. Folders have none and sort last. */
        SIZE,
        /** {@code mime_type} (falling back to {@code content_type}). Folders have none and sort last. */
        TYPE
    }

    /** Newest first - the historical, and still default, ordering. */
    public static final ExplorerSort DEFAULT = new ExplorerSort(Key.DATE, false);

    public ExplorerSort {
        if (key == null) {
            throw new IllegalArgumentException("sort key is required");
        }
    }

    /**
     * Parse the request params into a sort, falling back to {@link #DEFAULT} for anything
     * unrecognised. Both params are optional and case-insensitive: {@code key} is one of
     * {@code date|name|size|type}, {@code direction} one of {@code asc|desc}.
     *
     * <p>An unknown key degrades to the default sort (date) rather than failing the request -
     * a stale bookmark with a dropped criterion must still list files. An unknown direction
     * degrades to the natural default for the key: descending for date/size (newest, biggest
     * first), ascending for name/type (A→Z), which is what a user expects from "sort by name".</p>
     */
    public static ExplorerSort parse(String key, String direction) {
        Key parsedKey = parseKey(key);
        Boolean explicit = parseDirection(direction);
        boolean ascending = explicit != null ? explicit : naturalAscending(parsedKey);
        return new ExplorerSort(parsedKey, ascending);
    }

    private static Key parseKey(String key) {
        if (key == null || key.isBlank()) {
            return DEFAULT.key();
        }
        return switch (key.trim().toLowerCase()) {
            case "name" -> Key.NAME;
            case "size" -> Key.SIZE;
            case "type" -> Key.TYPE;
            case "date" -> Key.DATE;
            default -> DEFAULT.key();
        };
    }

    /** {@code TRUE}/{@code FALSE} for an explicit direction, {@code null} when unspecified. */
    private static Boolean parseDirection(String direction) {
        if (direction == null || direction.isBlank()) {
            return null;
        }
        return switch (direction.trim().toLowerCase()) {
            case "asc" -> Boolean.TRUE;
            case "desc" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** A→Z reads naturally for text keys; newest/biggest first for date and size. */
    private static boolean naturalAscending(Key key) {
        return key == Key.NAME || key == Key.TYPE;
    }
}
