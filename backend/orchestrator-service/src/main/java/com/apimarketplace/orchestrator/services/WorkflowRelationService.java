package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowRelationRef;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowRelations;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Answers "who calls this workflow, and who does it call?" for the sub-workflow graph.
 *
 * <p>The CHILD direction is readable from a workflow's own plan; the PARENT direction is not, and is
 * the reason this service exists: nothing in a called workflow records that it is called. Both
 * directions are therefore derived from the workspace's plans on read (see
 * {@link WorkflowRepository#findSubWorkflowEdgesByOrganization}) rather than from a relation column
 * that a plan save could leave stale.
 *
 * <p>One workspace-wide edge query serves any number of requested workflows, so the Workflows and
 * Applications card grids resolve a whole page in a single call instead of one per card.
 */
@Service
public class WorkflowRelationService {

    /** Resource type used by the org restriction guard for workflows. */
    private static final String RESOURCE_TYPE = "workflow";

    private final WorkflowRepository workflowRepository;
    private final OrgAccessGuard orgAccessGuard;

    public WorkflowRelationService(WorkflowRepository workflowRepository, OrgAccessGuard orgAccessGuard) {
        this.workflowRepository = workflowRepository;
        this.orgAccessGuard = orgAccessGuard;
    }

    /**
     * Resolve the sub-workflow neighbourhood of each requested workflow.
     *
     * <p>Every requested id gets an entry, including workflows with no relation at all: the caller
     * asked about them, and "no relation" is an answer the card grid needs in order to hide its
     * indicator. Ids the caller may not see resolve to an empty neighbourhood, never to an error.
     *
     * @param workflowIds the workflows to describe
     * @param orgId       the caller's active workspace ({@code X-Organization-ID}). Blank means no
     *                    workspace, and the workspace IS the scope, so the answer is empty - the
     *                    same defensive stance as {@code WorkflowManagementService#listWorkflows}
     * @param userId      the caller ({@code X-User-ID}), for per-member restrictions
     * @param orgRole     the caller's role in the workspace ({@code X-Organization-Role}); OWNER and
     *                    ADMIN carry no restrictions
     */
    @Transactional(readOnly = true)
    public Map<UUID, WorkflowRelations> resolve(Collection<UUID> workflowIds, String orgId,
                                                String userId, String orgRole) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> requested = new LinkedHashSet<>();
        for (UUID id : workflowIds) {
            if (id != null) requested.add(id);
        }
        if (requested.isEmpty()) {
            return Map.of();
        }
        Map<UUID, WorkflowRelations> result = new HashMap<>();
        if (orgId == null || orgId.isBlank()) {
            requested.forEach(id -> result.put(id, WorkflowRelations.empty()));
            return result;
        }

        Set<String> restricted = orgAccessGuard.getRestrictedResourceIds(orgId, userId, RESOURCE_TYPE, orgRole);
        if (restricted == null) {
            restricted = Set.of();
        }

        // Edges touching at least one requested workflow, both ends inside the workspace and neither
        // restricted. The workspace-wide scan is unavoidable: only another plan knows it is a parent.
        List<Edge> edges = new ArrayList<>();
        Set<UUID> neighbourIds = new HashSet<>();
        for (Object[] row : workflowRepository.findSubWorkflowEdgesByOrganization(orgId)) {
            Edge edge = toEdge(row, restricted);
            if (edge == null) continue;
            if (!requested.contains(edge.parentId()) && !requested.contains(edge.childId())) continue;
            edges.add(edge);
            neighbourIds.add(edge.parentId());
            neighbourIds.add(edge.childId());
        }

        Map<UUID, String> names = resolveNames(neighbourIds, orgId);

        // LinkedHashSet per direction: a plan may call the same workflow from several nodes, and the
        // menu lists workflows, not call sites - one row each.
        Map<UUID, Set<UUID>> parentsByChild = new HashMap<>();
        Map<UUID, Set<UUID>> childrenByParent = new HashMap<>();
        for (Edge edge : edges) {
            if (requested.contains(edge.childId())) {
                parentsByChild.computeIfAbsent(edge.childId(), k -> new LinkedHashSet<>()).add(edge.parentId());
            }
            if (requested.contains(edge.parentId())) {
                childrenByParent.computeIfAbsent(edge.parentId(), k -> new LinkedHashSet<>()).add(edge.childId());
            }
        }

        for (UUID id : requested) {
            List<WorkflowRelationRef> parents = toRefs(parentsByChild.get(id), names);
            List<WorkflowRelationRef> children = toRefs(childrenByParent.get(id), names);
            result.put(id, parents.isEmpty() && children.isEmpty()
                    ? WorkflowRelations.empty()
                    : new WorkflowRelations(parents, children));
        }
        return result;
    }

    /** Single-workflow convenience for the builder toolbar, which describes exactly one workflow. */
    @Transactional(readOnly = true)
    public WorkflowRelations resolveOne(UUID workflowId, String orgId, String userId, String orgRole) {
        if (workflowId == null) {
            return WorkflowRelations.empty();
        }
        return resolve(List.of(workflowId), orgId, userId, orgRole)
                .getOrDefault(workflowId, WorkflowRelations.empty());
    }

    /**
     * Turn one raw {@code [UUID parentId, String childId]} row into an edge, or {@code null} when it
     * is not one the UI can act on.
     *
     * <p>Dropped: a child id that is not a UUID (a plan may hold a template expression there, which
     * names no fixed workflow to open), a self-call (it would list a workflow as its own parent AND
     * its own child), and either end the caller is restricted from.
     */
    private Edge toEdge(Object[] row, Set<String> restricted) {
        if (row == null || row.length < 2 || row[0] == null || row[1] == null) return null;
        UUID parentId = asUuid(row[0]);
        UUID childId = asUuid(row[1]);
        if (parentId == null || childId == null || parentId.equals(childId)) return null;
        if (restricted.contains(parentId.toString()) || restricted.contains(childId.toString())) return null;
        return new Edge(parentId, childId);
    }

    private Map<UUID, String> resolveNames(Set<UUID> ids, String orgId) {
        if (ids.isEmpty()) return Map.of();
        Map<UUID, String> names = new HashMap<>();
        for (Object[] row : workflowRepository.findIdNamePairsInOrganization(ids, orgId)) {
            if (row == null || row.length < 2) continue;
            UUID id = asUuid(row[0]);
            if (id != null && row[1] != null) {
                names.put(id, row[1].toString());
            }
        }
        return names;
    }

    /** Named rows first (A-Z), unresolved ones last - a row that cannot be opened never leads. */
    private List<WorkflowRelationRef> toRefs(Set<UUID> ids, Map<UUID, String> names) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<WorkflowRelationRef> refs = new ArrayList<>();
        for (UUID id : ids) {
            refs.add(WorkflowRelationRef.of(id.toString(), names.get(id)));
        }
        refs.sort(Comparator
                .comparing(WorkflowRelationRef::resolved, Comparator.reverseOrder())
                .thenComparing(ref -> ref.name() == null ? "" : ref.name(), String.CASE_INSENSITIVE_ORDER));
        return refs;
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) return uuid;
        if (value == null) return null;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record Edge(UUID parentId, UUID childId) {
    }
}
