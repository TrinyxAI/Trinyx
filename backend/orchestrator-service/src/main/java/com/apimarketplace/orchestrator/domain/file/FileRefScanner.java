package com.apimarketplace.orchestrator.domain.file;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the {@link FileRef}s inside a node's output - either their storage-row ids
 * ({@link #collectFileIds}) or the refs themselves ({@link #collectRefs}).
 *
 * <p>A tool output is free-form JSON: a FileRef can sit at the top level, under a projected field,
 * inside a list of results, or nested several levels down. This walks the whole structure and
 * collects the {@code id} of every map that looks like a FileRef ({@code _type: "file"}), which is
 * the opaque {@code storage.storage} row handle the file was indexed under.</p>
 *
 * <p>Used to adopt catalog-produced files into the run that produced them: those uploads happen in
 * catalog-service, which knows only the tenant, so their rows carry no workflow and land at the
 * root of the Files browser instead of inside the run folder. Also used to freeze a run's file
 * results into a publication's showcase snapshot, which needs the display fields, not just ids.</p>
 *
 * <p>Deliberately forgiving. A malformed ref, a ref with no id (legacy, pre opaque-handle), or a
 * cycle in the structure yields fewer ids, never an exception - this runs on the output of a step
 * that has ALREADY succeeded, and nothing it discovers is worth failing that step over.</p>
 */
public final class FileRefScanner {

    /** Depth cap: deep enough for any real tool payload, shallow enough to bound a pathological one. */
    private static final int MAX_DEPTH = 12;

    private FileRefScanner() {
    }

    /**
     * Every distinct FileRef storage id in {@code output}, in encounter order.
     *
     * @return an empty list when the output holds no FileRef, or none of them carries an id
     */
    public static List<String> collectFileIds(Object output) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> ref : collectRefs(output)) {
            String id = asString(ref.get("id"));
            if (id != null) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Every FileRef in {@code output}, in encounter order, normalized to the wire map
     * ({@code _type}, {@code path}, {@code name}, {@code mimeType}, {@code size}, {@code id}).
     *
     * <p>Where {@link #collectFileIds} answers "which storage rows did this step touch", this
     * answers "what would the UI show for them": it keeps the display fields, and it keeps a ref
     * that carries no {@code id} (legacy, pre opaque-handle) because such a ref still has a
     * {@code path} and still renders.</p>
     *
     * <p>Refs are NOT deduplicated: two identical refs at two places in the payload are two
     * encounters, and the caller decides what that means. Same forgiving contract as the rest of
     * this class - a malformed ref yields fewer entries, never an exception.</p>
     */
    public static List<Map<String, Object>> collectRefs(Object output) {
        List<Map<String, Object>> refs = new ArrayList<>();
        collect(output, refs, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return refs;
    }

    private static void collect(Object node, List<Map<String, Object>> refs, int depth, Set<Object> visited) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof FileRef ref) {
            // A tool output reaching us over HTTP is deserialized JSON (maps), but a node that
            // builds its result in-process hands back the record itself. Matching only maps would
            // skip it silently, which is the kind of miss nobody notices.
            refs.add(refMap(FileRef.TYPE_FILE, ref.path(), ref.name(), ref.mimeType(), ref.size(), ref.id()));
            return;
        }
        if (node instanceof Map<?, ?> map) {
            // Identity-based visited set: a self-referencing payload must not spin forever, and
            // two equal-but-distinct sub-maps must both still be scanned.
            if (!visited.add(map)) {
                return;
            }
            if (FileRef.TYPE_FILE.equals(asString(map.get("_type")))) {
                refs.add(refMap(FileRef.TYPE_FILE, map.get("path"), map.get("name"),
                        map.get("mimeType"), map.get("size"), map.get("id")));
                // Keep walking: a FileRef map is a leaf in practice, but a producer nesting one
                // inside another should not cost us the inner ref.
            }
            for (Object value : map.values()) {
                collect(value, refs, depth + 1, visited);
            }
            return;
        }
        if (node instanceof Collection<?> collection) {
            if (!visited.add(collection)) {
                return;
            }
            for (Object item : collection) {
                collect(item, refs, depth + 1, visited);
            }
        }
        // Scalars carry no refs - nothing to do.
    }

    /**
     * The wire shape of a FileRef: {@code _type}, {@code path}, {@code name}, {@code mimeType}
     * and {@code size} are ALWAYS present, {@code id} only when the ref carries one.
     *
     * <p>The four display fields are present-but-null on a ref that omitted them, rather than
     * absent, so a consumer can read them positionally without a {@code containsKey} dance.
     * {@code id} is the exception because its absence is meaningful: it distinguishes a legacy
     * ref (no storage row to address) from one that has a handle.</p>
     *
     * <p>{@code size} is normalized to a {@code long} because it arrives either as the record's
     * primitive or as whatever number type the JSON parser chose, and a consumer comparing it
     * across the two would otherwise see an Integer and a Long that are never equal. A missing
     * or non-numeric size becomes {@code 0}, which is what a size label renders as anyway.</p>
     */
    private static Map<String, Object> refMap(String type, Object path, Object name,
                                              Object mimeType, Object size, Object id) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_type", type);
        out.put("path", asString(path));
        out.put("name", asString(name));
        out.put("mimeType", asString(mimeType));
        out.put("size", size instanceof Number n ? n.longValue() : 0L);
        String idText = asString(id);
        if (idText != null) {
            out.put("id", idText);
        }
        return out;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
