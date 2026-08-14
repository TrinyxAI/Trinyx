package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores an acquired application's tables to the rows frozen in its publication
 * snapshot - the "reset to the template you downloaded" action.
 *
 * <p>This is the counterpart of the table branch of {@link SnapshotCloneService}: acquire
 * CREATES the datasources and injects {@code _snapshot_ds_items}; this service REPLACES the
 * rows of those already-cloned datasources with the same snapshot rows. Nothing else about
 * the acquisition is touched - the plan, interfaces, agents and the acquirer's workflow runs
 * are left exactly as they are.
 *
 * <p><b>Deliberately NOT restored: the publisher's run outputs.</b> The showcase run is
 * publisher-scoped and is never transferred to acquirers (see the project docs);
 * its FileRefs address storage rows in the publisher's tenant, so importing them would
 * either 403 or require copying every blob into the acquirer's quota. Rows are different:
 * they already travel inside the publication snapshot, and their FILE / IMAGE column files
 * are re-uploaded under the acquirer's tenant by {@link DataSourceFileCloneService} - the
 * exact same path acquire uses.
 *
 * <p><b>Known cost, deliberately not solved here.</b> Restoring a table with FILE / IMAGE
 * columns re-copies the publisher's files under the owner's tenant, and the blobs the
 * previous generation of rows referenced are NOT reclaimed: deleting the rows
 * ({@code data_source_items}) does not delete the storage entries they pointed at. Acquire
 * pays that copy once; a user who resets N times leaves N-1 orphaned generations against
 * their storage quota. Reclaiming them means diffing the outgoing rows' file references and
 * deleting through storage-service, which belongs with the datasource row lifecycle rather
 * than in this one action - so it is logged and left visible instead of half-solved here.
 */
@Service
public class ApplicationTemplateResetService {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationTemplateResetService.class);

    /** Per-row byte estimate used by the acquire path when reporting DATA usage. */
    private static final long ROW_BYTES_ESTIMATE = 200L;

    private final OrchestratorInternalClient orchestratorClient;
    private final DataSourceClient dataSourceClient;
    private final DataSourceFileCloneService fileCloneService;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;

    public ApplicationTemplateResetService(OrchestratorInternalClient orchestratorClient,
                                           DataSourceClient dataSourceClient,
                                           DataSourceFileCloneService fileCloneService,
                                           StorageBreakdownService breakdownService,
                                           ObjectMapper objectMapper) {
        this.orchestratorClient = orchestratorClient;
        this.dataSourceClient = dataSourceClient;
        this.fileCloneService = fileCloneService;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
    }

    /**
     * Outcome of a reset, surfaced verbatim to the caller so the UI can say what happened
     * instead of a bare "done".
     *
     * @param tablesReset  number of datasources whose rows were replaced
     * @param rowsRestored total rows written across those datasources
     * @param tablesSkipped labels of snapshot tables that had no counterpart in the
     *                      acquirer's clone (or whose replace call failed)
     */
    public record ResetResult(int tablesReset, int rowsRestored, List<String> tablesSkipped) {}

    /**
     * Replace the rows of every table of the caller's acquired application with the rows
     * frozen in {@code publication}'s plan snapshot.
     *
     * @throws IllegalStateException    when the publication carries no plan snapshot
     * @throws IllegalArgumentException when the caller has no acquired clone of it
     */
    public ResetResult resetTables(WorkflowPublicationEntity publication, String tenantId, String organizationId) {
        Map<String, Object> planSnapshot = publication.getPlanSnapshot();
        if (planSnapshot == null || planSnapshot.isEmpty()) {
            throw new IllegalStateException("Publication has no plan snapshot");
        }
        List<Map<String, Object>> snapshotTables = tablesOf(planSnapshot);

        UUID publicationId = publication.getId();
        Map<String, Object> clone = orchestratorClient.findBySourcePublication(publicationId, tenantId, organizationId);
        Object cloneIdRaw = clone != null ? clone.get("id") : null;
        if (cloneIdRaw == null) {
            throw new IllegalArgumentException("No acquired application for publication " + publicationId);
        }

        // Every datasource write below is keyed on the tenant that OWNS the install, never
        // on the caller. The clone is resolved by ORGANIZATION (resolveClone ignores the
        // tenant), so in a shared workspace the teammate clicking reset is routinely NOT the
        // member who installed the app. Acquire created the datasources under the installer's
        // tenant and V333 pins every row's tenant to its parent datasource, so using the
        // caller here would re-upload the row files into the wrong tenant, count zero existing
        // rows (a tenant-scoped count) and charge the whole template to the wrong quota.
        String ownerTenantId = clone.get("tenantId") != null ? clone.get("tenantId").toString() : tenantId;

        UUID cloneWorkflowId = UUID.fromString(cloneIdRaw.toString());
        Map<String, Object> cloneWorkflow =
                orchestratorClient.getWorkflowForPublication(cloneWorkflowId, tenantId, organizationId);
        Map<String, Object> clonePlan = cloneWorkflow != null && cloneWorkflow.get("plan") instanceof Map<?, ?> p
                ? objectMapper.convertValue(p, new TypeReference<Map<String, Object>>() {})
                : null;
        if (clonePlan == null) {
            throw new IllegalArgumentException("Acquired application " + cloneWorkflowId + " has no readable plan");
        }

        // node id -> the acquirer's own datasource id. Table node ids survive the clone
        // untouched (only `dataSourceId` is remapped), so they are the stable join key
        // between the publication snapshot and the acquirer's plan.
        Map<String, Long> cloneDsByNodeId = new LinkedHashMap<>();
        for (Map<String, Object> tableNode : tablesOf(clonePlan)) {
            Object nodeId = tableNode.get("id");
            Long dsId = parseDsId(tableNode.get("dataSourceId"));
            if (nodeId != null && dsId != null) {
                cloneDsByNodeId.put(nodeId.toString(), dsId);
            }
        }

        int tablesReset = 0;
        int rowsRestored = 0;
        List<String> skipped = new ArrayList<>();
        // Several table nodes may point at the SAME datasource (publish snapshots it once,
        // acquire clones it once). Replacing twice would make the second pass wipe and
        // rewrite what the first just wrote, so the first snapshot node wins - mirroring
        // the dedupe both the publish and the acquire paths already do.
        Set<Long> handled = new LinkedHashSet<>();

        for (Map<String, Object> snapshotTable : snapshotTables) {
            // `_snapshot_ds_name` is the same gate acquire uses: without it the table was
            // never captured into the publication and there is no template to restore.
            if (snapshotTable.get("_snapshot_ds_name") == null) continue;

            String label = label(snapshotTable);
            Object nodeId = snapshotTable.get("id");
            Long targetDsId = nodeId != null ? cloneDsByNodeId.get(nodeId.toString()) : null;
            if (targetDsId == null) {
                logger.warn("[TemplateReset] no cloned datasource for table node {} (pub={}, tenant={})",
                        nodeId, publicationId, tenantId);
                skipped.add(label);
                continue;
            }
            if (!handled.add(targetDsId)) continue;

            // One table's failure must not discard the tables already restored, nor be
            // reported as a whole-reset failure: by the time anything can throw here the
            // previous tables have been wiped and refilled for real. Contain it, report the
            // table as skipped, carry on.
            try {
                // Deep copy BEFORE touching anything: the snapshot map belongs to the managed
                // publication entity, and rewriteFilePaths mutates items in place. Mutating it
                // here would dirty-check the publisher's stored snapshot and rewrite it to point
                // at the acquirer's copies - corrupting the template for everyone else.
                Map<String, Object> table = objectMapper.convertValue(snapshotTable,
                        new TypeReference<Map<String, Object>>() {});

                List<Map<String, Object>> items = table.get("_snapshot_ds_items") instanceof List<?> raw
                        ? objectMapper.convertValue(raw, new TypeReference<List<Map<String, Object>>>() {})
                        : new ArrayList<>();
                String sourceType = table.get("_snapshot_ds_sourceType") != null
                        ? table.get("_snapshot_ds_sourceType").toString() : "INLINE";
                Map<String, ColumnMappingSpecDto> mappingSpec = objectMapper.convertValue(
                        table.get("_snapshot_ds_mappingSpec"),
                        new TypeReference<Map<String, ColumnMappingSpecDto>>() {});
                if (mappingSpec == null) mappingSpec = Map.of();

                // Re-upload FILE / IMAGE column files under the OWNER's tenant, exactly as
                // acquire does. Without this the restored rows would reference the publisher's
                // storage keys and render as broken cells.
                //
                // sourceConfig is passed as null ON PURPOSE. That argument only drives the
                // FILE-sourced `sourceConfig.file_path` copy, and we are not re-creating the
                // datasource: acquire already rewrote that path to the owner's own file, and
                // this service never writes the datasource row back. Passing the snapshot's
                // config would copy a blob into storage on every reset and then discard the
                // rewritten path - pure quota burn for no effect.
                fileCloneService.rewriteFilePaths(sourceType, null, items, mappingSpec,
                        ownerTenantId, publicationId.toString(), organizationId);

                int before = dataSourceClient.getItemsCount(targetDsId, ownerTenantId);
                int inserted = dataSourceClient.replaceItems(targetDsId, items, ownerTenantId, organizationId);
                if (inserted < 0) {
                    logger.warn("[TemplateReset] replace failed for ds={} (pub={}, owner={})",
                            targetDsId, publicationId, ownerTenantId);
                    skipped.add(label);
                    continue;
                }

                // Report the DELTA against the OWNER's quota: rows were both removed and
                // written, so charging the inserted count alone would inflate usage on every
                // reset. "DATA" is the bucket acquire books cloned rows under, and the reset
                // has to land in the same one or the two paths disagree about the same rows.
                int deltaRows = inserted - before;
                if (deltaRows != 0) {
                    breakdownService.increment(ownerTenantId, "DATA",
                            deltaRows * ROW_BYTES_ESTIMATE, deltaRows, organizationId);
                }
                logger.info("[TemplateReset] table {} (ds={}) restored to {} template rows (was {}), pub={}",
                        label, targetDsId, inserted, before, publicationId);
                // Counted LAST, so nothing between here and the catch can put this table in
                // both tallies. A table reported as restored AND skipped would read as
                // "2 tables restored... Left alone: Leads" for a single table.
                tablesReset++;
                rowsRestored += inserted;
            } catch (Exception e) {
                logger.error("[TemplateReset] table {} (ds={}) failed to restore (pub={}): {}",
                        label, targetDsId, publicationId, e.getMessage(), e);
                skipped.add(label);
            }
        }

        return new ResetResult(tablesReset, rowsRestored, skipped);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tablesOf(Map<String, Object> plan) {
        if (plan == null || !(plan.get("tables") instanceof List<?> tables)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : tables) {
            if (entry instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private String label(Map<String, Object> tableNode) {
        Object label = tableNode.get("label");
        if (label != null && !label.toString().isBlank()) return label.toString();
        Object snapshotName = tableNode.get("_snapshot_ds_name");
        if (snapshotName != null) return snapshotName.toString();
        Object id = tableNode.get("id");
        return id != null ? id.toString() : "table";
    }

    private Long parseDsId(Object raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
