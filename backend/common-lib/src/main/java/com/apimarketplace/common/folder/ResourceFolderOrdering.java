package com.apimarketplace.common.folder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a folder sits in a list that is sorted by something the folder itself does not
 * have. A folder has no "last run" and no "last modified" of its own, so it borrows the
 * best one in it: the folder holding the workflow you ran ten minutes ago sits at the top
 * of a "last executed" list, right where you look for it.
 *
 * <p>Folders always come before loose resources (as in any file browser), and an empty
 * folder sorts last among folders on the time keys instead of jumping to the front on a
 * null - the same nulls-last rule the resource lists themselves use.
 */
public final class ResourceFolderOrdering {

    /** The orderings a folder can be given, whatever the resource type underneath. */
    public enum Key {
        /** Folder name, A->Z, case-insensitive. */
        NAME,
        /** Newest change inside the folder first. */
        LAST_MODIFIED,
        /** Most recently used (run / executed) inside the folder first. */
        LAST_ACTIVITY,
        /** Busiest folder first (total runs inside). */
        ACTIVITY_COUNT
    }

    private ResourceFolderOrdering() {
        throw new AssertionError("Utility class");
    }

    /**
     * Map a list page's sort parameter onto a folder ordering. Anything unknown (or
     * absent) falls back to {@link Key#LAST_MODIFIED}, which is what the lists default to.
     */
    public static Key keyOf(String sort) {
        if (sort == null) return Key.LAST_MODIFIED;
        return switch (sort.trim().toLowerCase()) {
            case "name", "title" -> Key.NAME;
            case "lastexecuted", "execution", "lastrun" -> Key.LAST_ACTIVITY;
            case "runcount", "activity", "usage" -> Key.ACTIVITY_COUNT;
            default -> Key.LAST_MODIFIED;
        };
    }

    /** A new list, ordered. Ties keep the incoming order (the sort is stable). */
    public static List<ResourceFolderDto> sort(List<ResourceFolderDto> folders, Key key) {
        List<ResourceFolderDto> ordered = new ArrayList<>(folders);
        ordered.sort(comparator(key));
        return ordered;
    }

    public static Comparator<ResourceFolderDto> comparator(Key key) {
        return switch (key) {
            case NAME -> Comparator.comparing(
                    f -> f.name() == null ? "" : f.name(), String.CASE_INSENSITIVE_ORDER);
            case LAST_ACTIVITY -> byInstantDesc(ResourceFolderDto::lastActivityAt);
            case ACTIVITY_COUNT -> Comparator.comparingLong(
                    (ResourceFolderDto f) -> f.activityCount() == null ? 0L : f.activityCount()).reversed();
            case LAST_MODIFIED -> byInstantDesc(ResourceFolderDto::lastModifiedAt);
        };
    }

    /**
     * Newest first, absent last. Built on {@code nullsLast(reverseOrder())} rather than
     * {@code reversed()}, which would float the empty folders to the very top.
     */
    private static Comparator<ResourceFolderDto> byInstantDesc(
            java.util.function.Function<ResourceFolderDto, Instant> field) {
        return Comparator.comparing(field, Comparator.nullsLast(Comparator.reverseOrder()));
    }
}
