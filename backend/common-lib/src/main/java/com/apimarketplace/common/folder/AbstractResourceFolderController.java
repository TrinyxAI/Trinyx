package com.apimarketplace.common.folder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The folder lifecycle as a REST surface, written once for every list page that has
 * folders. A service subclasses it with its own {@code @RestController} +
 * {@code @RequestMapping("/api/<resource>-folders")} and supplies three things: its folder
 * service, the name its list gives to a batch of ids, and how to file them.
 *
 * <ul>
 *   <li>{@code GET    /}            - every folder of the workspace (flat; the caller builds the tree)</li>
 *   <li>{@code POST   /}            - create {name, parentFolderId?}</li>
 *   <li>{@code PUT    /{id}}        - rename {name}</li>
 *   <li>{@code PUT    /{id}/move}   - re-parent {parentFolderId|null}</li>
 *   <li>{@code DELETE /{id}}        - delete; what it held goes back to the top level</li>
 *   <li>{@code POST   /items}       - file resources {folderId|null, &lt;itemIdsField&gt;}</li>
 * </ul>
 *
 * <p>The tiles themselves (count + preview for one level) are NOT here: they ride with each
 * list's own response, which is where the scoped resources they are computed from already
 * are.
 *
 * @param <E> the service's concrete folder entity
 */
public abstract class AbstractResourceFolderController<E extends AbstractResourceFolderEntity> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractResourceFolderController.class);

    /** The folder rules for this list. */
    protected abstract ResourceFolderCoreService<E> folders();

    /**
     * What the list calls a batch of its ids in the {@code /items} payload
     * ({@code "workflowIds"}, {@code "agentIds"}, ...) - each list speaks its own language.
     */
    protected abstract String itemIdsField();

    /**
     * File those resources into the folder ({@code null} = the top level), inside the
     * caller's workspace only. Ids arrive as RAW STRINGS because the lists do not agree on
     * what an id is - most are UUIDs, a table is a number - so each subclass converts with
     * {@link #toUuids} or its own parse.
     *
     * @return how many were actually re-filed
     */
    protected abstract int assignItems(FolderScope scope, UUID folderId, List<String> resourceIds);

    /** For logs: the kind of list these folders belong to. */
    protected abstract String resourceLabel();

    @GetMapping
    public ResponseEntity<?> listFolders(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        List<Map<String, Object>> rows = folders().listAll(new FolderScope(userId, orgId)).stream()
                .map(AbstractResourceFolderController::toBareMap)
                .toList();
        return ResponseEntity.ok(Map.of("folders", rows));
    }

    @PostMapping
    public ResponseEntity<?> createFolder(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> body) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (isViewer(orgRole)) return viewerForbidden();
        E folder = folders().create(
                new FolderScope(userId, orgId), asString(body.get("name")), asUuid(body.get("parentFolderId")));
        logger.info("{} folder created: id={} name='{}' parent={} org={}",
                resourceLabel(), folder.getId(), folder.getName(), folder.getParentFolderId(), orgId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toBareMap(folder));
    }

    @PutMapping("/{folderId}")
    public ResponseEntity<?> renameFolder(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable("folderId") UUID folderId,
            @RequestBody Map<String, Object> body) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (isViewer(orgRole)) return viewerForbidden();
        E folder = folders().rename(folderId, new FolderScope(userId, orgId), asString(body.get("name")));
        return ResponseEntity.ok(toBareMap(folder));
    }

    @PutMapping("/{folderId}/move")
    public ResponseEntity<?> moveFolder(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable("folderId") UUID folderId,
            @RequestBody Map<String, Object> body) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (isViewer(orgRole)) return viewerForbidden();
        E folder = folders().move(folderId, new FolderScope(userId, orgId), asUuid(body.get("parentFolderId")));
        return ResponseEntity.ok(toBareMap(folder));
    }

    @DeleteMapping("/{folderId}")
    public ResponseEntity<?> deleteFolder(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable("folderId") UUID folderId) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (isViewer(orgRole)) return viewerForbidden();
        var deleted = folders().delete(folderId, new FolderScope(userId, orgId));
        logger.info("{} folder deleted: id={} (with {} subfolder(s)), content moved to the top level",
                resourceLabel(), folderId, deleted.size() - 1);
        return ResponseEntity.ok(Map.of("deletedFolderIds", new ArrayList<>(deleted)));
    }

    /**
     * File resources into a folder, or back to the top level with {@code folderId: null}.
     * Ids outside the caller's workspace are silently not moved - {@code moved} reports how
     * many actually were.
     */
    @PostMapping("/items")
    public ResponseEntity<?> assignItems(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> body) {
        if (isAnonymous(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (isViewer(orgRole)) return viewerForbidden();
        List<String> ids = asIdList(body.get(itemIdsField()));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", itemIdsField() + " is required"));
        }
        int moved = assignItems(new FolderScope(userId, orgId), asUuid(body.get("folderId")), ids);
        return ResponseEntity.ok(Map.of("moved", moved));
    }

    /**
     * The folder rules speak in codes, not in statuses: an unknown folder is a 404 (its
     * existence is never leaked across workspaces), a bad name a 400, and a move that would
     * swallow its own branch a 409.
     */
    @ExceptionHandler(ResourceFolderException.class)
    public ResponseEntity<Map<String, String>> handleFolderError(ResourceFolderException e) {
        HttpStatus status = switch (e.getCode()) {
            case NOT_FOUND, PARENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_NAME -> HttpStatus.BAD_REQUEST;
            case CYCLE -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage(), "code", e.getCode().name()));
    }

    // ===================== Helpers =====================

    /**
     * The folder row itself, without the tile aggregates (those ride with each list).
     * Public because the list endpoints serialise their breadcrumb with the very same
     * shape - one place decides what a bare folder looks like on the wire.
     */
    public static Map<String, Object> toBareMap(AbstractResourceFolderEntity folder) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", folder.getId());
        map.put("name", folder.getName());
        map.put("parentFolderId", folder.getParentFolderId());
        map.put("createdAt", folder.getCreatedAt());
        map.put("updatedAt", folder.getUpdatedAt());
        return map;
    }

    protected static boolean isAnonymous(String userId) {
        return userId == null || userId.isBlank();
    }

    protected static boolean isViewer(String orgRole) {
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }

    protected static ResponseEntity<Map<String, String>> viewerForbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "VIEWER role cannot modify folders"));
    }

    protected static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** A blank or absent value means the top level; anything unparseable is a clean 404. */
    protected static UUID asUuid(Object value) {
        if (value == null) return null;
        String raw = value.toString().trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new ResourceFolderException(
                    ResourceFolderException.Code.NOT_FOUND, "Folder not found: " + raw);
        }
    }

    /** The ids of a batch, as the caller wrote them - blanks dropped, nothing parsed. */
    protected static List<String> asIdList(Object value) {
        if (!(value instanceof List<?> raw)) return List.of();
        List<String> ids = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item == null) continue;
            String id = item.toString().trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /**
     * For the lists whose ids ARE UUIDs. An id that is not one is dropped rather than
     * failing the batch: it can match no row of this workspace anyway.
     */
    protected static List<UUID> toUuids(List<String> ids) {
        List<UUID> parsed = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                parsed.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // Not an id of this list - skip it.
            }
        }
        return parsed;
    }
}
