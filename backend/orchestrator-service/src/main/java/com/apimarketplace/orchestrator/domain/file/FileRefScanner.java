package com.apimarketplace.orchestrator.domain.file;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the storage-row ids of the {@link FileRef}s inside a node's output.
 *
 * <p>A tool output is free-form JSON: a FileRef can sit at the top level, under a projected field,
 * inside a list of results, or nested several levels down. This walks the whole structure and
 * collects the {@code id} of every map that looks like a FileRef ({@code _type: "file"}), which is
 * the opaque {@code storage.storage} row handle the file was indexed under.</p>
 *
 * <p>Used to adopt catalog-produced files into the run that produced them: those uploads happen in
 * catalog-service, which knows only the tenant, so their rows carry no workflow and land at the
 * root of the Files browser instead of inside the run folder.</p>
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
        collect(output, ids, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        return new ArrayList<>(ids);
    }

    private static void collect(Object node, Set<String> ids, int depth, Set<Object> visited) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof FileRef ref) {
            // A tool output reaching us over HTTP is deserialized JSON (maps), but a node that
            // builds its result in-process hands back the record itself. Matching only maps would
            // skip it silently, which is the kind of miss nobody notices.
            String id = asString(ref.id());
            if (id != null) {
                ids.add(id);
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            // Identity-based visited set: a self-referencing payload must not spin forever, and
            // two equal-but-distinct sub-maps must both still be scanned.
            if (!visited.add(map)) {
                return;
            }
            if (FileRef.TYPE_FILE.equals(asString(map.get("_type")))) {
                String id = asString(map.get("id"));
                if (id != null) {
                    ids.add(id);
                }
                // Keep walking: a FileRef map is a leaf in practice, but a producer nesting one
                // inside another should not cost us the inner ref.
            }
            for (Object value : map.values()) {
                collect(value, ids, depth + 1, visited);
            }
            return;
        }
        if (node instanceof Collection<?> collection) {
            if (!visited.add(collection)) {
                return;
            }
            for (Object item : collection) {
                collect(item, ids, depth + 1, visited);
            }
        }
        // Scalars carry no refs - nothing to do.
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
