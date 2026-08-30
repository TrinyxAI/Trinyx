package com.apimarketplace.orchestrator.domain.file;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a reference to a file stored in S3/MinIO.
 * This record is serialized in workflow outputs and recognized by the frontend
 * for rendering download buttons and image previews.
 *
 * Example JSON output:
 * {
 *   "_type": "file",
 *   "path": "tenant123/workflow456/run789/step1/output.pdf",
 *   "name": "report.pdf",
 *   "mimeType": "application/pdf",
 *   "size": 102400
 * }
 */
public record FileRef(
    /** Discriminator field for frontend detection */
    @JsonProperty("_type") String type,

    /** Storage path (S3 object key) */
    String path,

    /** Original filename for display/download */
    String name,

    /** MIME type of the file */
    String mimeType,

    /** File size in bytes */
    long size,

    /** storage.storage row UUID - opaque handle for {@code /api/proxy/files/by-id/{id}/raw}.
     *  Null on legacy/old refs (pre opaque-cutover). */
    @JsonInclude(JsonInclude.Include.NON_NULL) String id
) {
    /** Type discriminator value */
    public static final String TYPE_FILE = "file";

    /** Reused: TemplateEngine calls displayUrl once per {@code {{...}}} interpolation. */
    private static final com.fasterxml.jackson.databind.ObjectMapper DISPLAY_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Only a UUID is one of our storage ids. */
    private static final java.util.regex.Pattern OUR_ID = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** {@code /api/proxy/files/by-id/<uuid>/raw} - evidence that a URL addresses one of OUR files. */
    private static final java.util.regex.Pattern BY_ID_URL = java.util.regex.Pattern.compile(
            "/files/by-id/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Creates a new FileRef with the standard type discriminator (no storage id - legacy callers).
     */
    public static FileRef of(String path, String name, String mimeType, long size) {
        return new FileRef(TYPE_FILE, path, name, mimeType, size, null);
    }

    /** Creates a FileRef carrying the storage row UUID (opaque handle). */
    public static FileRef of(String path, String name, String mimeType, long size, String id) {
        return new FileRef(TYPE_FILE, path, name, mimeType, size, id);
    }

    /**
     * Checks if an object is a FileRef based on the _type field.
     * Useful for frontend-side detection in JavaScript.
     */
    @JsonIgnore
    public boolean isFileRef() {
        return TYPE_FILE.equals(type);
    }

    /**
     * The URL to substitute when a file-shaped value is interpolated into a STRING context, such as
     * {@code <img src="{{photo}}">} in an interface template.
     *
     * <p>Without this, a map is JSON-encoded and the attribute becomes
     * {@code <img src="{"url":"/api/..."}">}: a broken image, on every already-published page. That
     * is the exact failure {@code ShowcaseFileRefRewriter} documents, and it is what a table media
     * cell hits the moment it stores a map instead of a bare URL string.
     *
     * <p>Deliberately STRICT: it recognises the FileRef discriminator and the two DB/upload shapes
     * that unambiguously name a file, and nothing else. A "has a url and a name" rule would also
     * match ordinary API objects (a GitHub repo is {@code {name, url}}), and turning one of those
     * into a bare URL inside a JSON body template would make the template unparseable - which is
     * the very thing the JSON encoding below it exists to prevent.
     *
     * <p>Also accepts the value serialized as a JSON string: the CRUD write path stringifies a map
     * before the JSONB insert, so a cell written by an agent arrives here as text, not as a Map.
     */
    public static String displayUrl(Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            // Cheap precondition before paying for a parse: only the discriminated form qualifies.
            if (!trimmed.startsWith("{")) return null;
            // Cheap precondition before paying for a parse: either the discriminator, or the
            // by-id URL that only one of our own files can carry.
            if (!trimmed.contains("\"_type\"") && !BY_ID_URL.matcher(trimmed).find()) return null;
            try {
                return displayUrl(DISPLAY_MAPPER.readValue(trimmed, java.util.Map.class));
            } catch (Exception e) {
                return null;
            }
        }
        if (!(value instanceof java.util.Map<?, ?> map)) return null;

        // A node's step output carries _status / _duration_ms FLAT, alongside the node's own
        // fields, so a download-file envelope has a file_url at its top level without BEING a
        // file. Claiming it would substitute the whole step output with one of its URLs.
        boolean stepEnvelope = map.containsKey("_status");
        boolean fileShaped = TYPE_FILE.equals(map.get("_type"))
                || (!stepEnvelope && map.containsKey("storageKey"))
                || (!stepEnvelope && map.containsKey("file_url"))
                // The shape the previous table file cell wrote: a map with no discriminator, still
                // the commonest media value in production. Recognised only by a by-id URL, which
                // an unrelated API object cannot carry.
                || (!stepEnvelope && map.get("url") instanceof String u && BY_ID_URL.matcher(u).find());
        if (!fileShaped) return null;

        // The id wins over a stored URL, mirroring the frontend: a legacy map can carry a good id
        // and a URL of a dead generation, and interpolating the dead one renders nothing.
        if (map.get("id") instanceof String id && OUR_ID.matcher(id).matches()) {
            return "/api/proxy/files/by-id/" + id + "/raw?disposition=inline";
        }
        for (String key : new String[]{"url", "file_url", "src", "href"}) {
            if (map.get(key) instanceof String url && !url.isBlank()) return url;
        }
        return null;
    }
}
