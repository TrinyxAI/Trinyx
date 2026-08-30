package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.controllers.dto.WorkflowRelations;
import com.apimarketplace.orchestrator.services.WorkflowRelationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the sub-workflow relation graph: which workflows call a given workflow (parents) and which
 * ones it calls (children).
 *
 * <p>Two shapes for the two ways the UI asks. The single-workflow GET serves a workflow that is
 * already open (its canvas toolbar, its application view); the batch POST serves a card grid, which
 * needs the whole visible page in one call rather than one request per card - the same reason
 * {@code /applications/run-version-batch} exists next door.
 *
 * <p>The batch path is a POST because the ids are a list the caller sends, not a resource address:
 * a page of 25 ids in a query string is both fragile and cacheable in the wrong way.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowRelationController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRelationController.class);

    /**
     * Batch ceiling - a backstop against an unbounded body, not a page size.
     *
     * <p>Set well above what any grid sends: the Applications page unions its published and
     * acquired streams at 100 each, so 200 ids is a REACHABLE number there and a cap at 200 would
     * silently drop the tail of a real page. The cost of a larger list is one longer {@code IN}
     * clause; the workspace edge scan behind it does not grow with the number of ids at all.
     */
    private static final int MAX_BATCH_IDS = 500;

    private final WorkflowRelationService relationService;

    public WorkflowRelationController(WorkflowRelationService relationService) {
        this.relationService = relationService;
    }

    /**
     * Relations of one workflow. A workflow outside the caller's workspace is not an error here: it
     * simply has no relations the caller can see, which is what an empty pair of lists says.
     */
    @GetMapping("/{workflowId}/relations")
    public ResponseEntity<WorkflowRelations> getRelations(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(relationService.resolveOne(workflowId, orgId, userId, orgRole));
    }

    /**
     * Relations for a page of cards. Body: {@code {workflowIds: [...]}}. Returns
     * {@code Map<workflowId, {parents, children}>} carrying an entry for every id that resolved,
     * including the ones with no relation - the card grid reads "absent or empty" the same way and
     * hides its indicator either way.
     *
     * <p>Malformed ids are skipped rather than rejected: the grid resolves each card's workflow id
     * from several sources (a published app, an acquired clone) and one unusable id must not cost
     * the page its whole enrichment.
     */
    @PostMapping("/relations-batch")
    public ResponseEntity<Map<String, WorkflowRelations>> getRelationsBatch(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        Object raw = body == null ? null : body.get("workflowIds");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object o : rawList) {
            if (o == null) continue;
            try {
                ids.add(UUID.fromString(o.toString()));
            } catch (IllegalArgumentException ignored) { /* skip malformed id */ }
            if (ids.size() >= MAX_BATCH_IDS) {
                logger.warn("relations-batch truncated at {} ids (caller sent {}) - the tail is not "
                        + "described, so those cards show no relations indicator", MAX_BATCH_IDS, rawList.size());
                break;
            }
        }
        if (ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        Map<UUID, WorkflowRelations> resolved = relationService.resolve(ids, orgId, userId, orgRole);
        Map<String, WorkflowRelations> response = new HashMap<>();
        resolved.forEach((id, relations) -> response.put(id.toString(), relations));
        return ResponseEntity.ok(response);
    }
}
