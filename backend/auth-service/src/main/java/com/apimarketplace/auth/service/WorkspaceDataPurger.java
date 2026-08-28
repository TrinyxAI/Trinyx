package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Single source of truth for deleting all OPERATIONAL org-scoped data across every
 * service schema for a given organization id. Reused by account deletion
 * ({@link AccountPurgeService}) and workspace deletion ({@link WorkspacePurgeService}).
 *
 * <p><b>Deliberate scope - operational only.</b> This NEVER touches the financial /
 * audit ledger ({@code auth.credit_ledger}, {@code auth.usage_cycle},
 * {@code auth.credit_reconciliation_log}, {@code auth.organization_audit_event}) nor the
 * {@code auth.organization} row / its memberships. The workspace flow keeps the org row
 * as a tombstone so owner-pays credit-ledger references stay valid (ADR-009); the caller
 * decides what to do with the org shell.
 *
 * <p>Runs as native cross-schema SQL - the deliberate exception to the "each service owns
 * its schema" rule, exactly like the account-purge path. The CALLER owns the surrounding
 * {@code @Transactional}.
 *
 * <p><b>Fail-closed transaction.</b> Every required delete participates in the caller's single
 * transaction. A schema/type/permission failure is rethrown immediately, so the whole purge rolls
 * back and the caller cannot mark the workspace {@code PURGED} while data remains. Statements are
 * type-safe: org-id columns are a UUID/VARCHAR mix across schemas, so
 * predicates cast {@code organization_id::text = ?}; the one UUID column uses {@code = ?::uuid}.
 *
 * <p><b>Storage:</b> records every immutable tenant/key tuple in a durable erasure outbox
 * before deleting {@code storage.storage}. Physical S3/MinIO deletion is retried outside
 * the purge transaction until confirmed. An enumeration or enqueue failure aborts the
 * surrounding purge transaction so metadata can never disappear without a recovery record.
 *
 * <p><b>Custom APIs:</b> workspace-owned rows are deleted only through the exact
 * {@code organization_id::text = ?} predicate. Imported global APIs have a NULL organization and
 * are therefore structurally outside this purge. Tool mappings are deleted before the API row;
 * the remaining tool children cascade from {@code catalog.apis}.
 *
 * <p>{@link #PURGED_ORG_SCOPED_TABLES} declares every table this purges; the
 * {@code WorkspaceDataPurgerTest} captures the issued SQL and asserts (a) every statement is
 * org-scoped, (b) the retained financial/audit tables are never touched, and (c) every declared
 * table is actually hit. A wrong/missing table is intentionally fatal and rolls the transaction
 * back, leaving the workspace retryable.
 */
@Component
public class WorkspaceDataPurger {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceDataPurger.class);

    private final JdbcTemplate jdbc;

    /** Durable handoff for stored bytes; the rows below only reference them. */
    private final WorkspaceStorageErasureOutbox storageErasureOutbox;

    public WorkspaceDataPurger(
            JdbcTemplate jdbc,
            WorkspaceStorageErasureOutbox storageErasureOutbox) {
        this.jdbc = jdbc;
        this.storageErasureOutbox = storageErasureOutbox;
    }

    /**
     * Every {@code schema.table} this purger deletes org-scoped rows from. Kept in sync with
     * {@link #purgeOperationalData} and consumed by the anti-drift coverage test. Order here
     * is documentation only; the method runs children-before-parents for FK safety.
     */
    public static final List<String> PURGED_ORG_SCOPED_TABLES = List.of(
            "conversation.conversations",
            "orchestrator.workflow_runs",
            "orchestrator.workflows",
            "orchestrator.projects",
            "orchestrator.notifications",
            "agent.agent_executions",
            "agent.agent_tasks",
            "agent.agent_task_recurrences",
            "agent.agent_task_notes",
            "agent.agent_task_events",
            "agent.agent_task_claims",
            "agent.agents",
            "agent.skill_folders",
            "agent.skills",
            "interface.interfaces",
            "datasource.data_sources",
            "trigger.scheduled_executions",
            "trigger.standalone_webhooks",
            "trigger.standalone_chat_endpoints",
            "trigger.standalone_form_endpoints",
            "trigger.webhook_tokens",
            "trigger.datasource_trigger_subscriptions",
            "storage.storage",
            "storage.organization_storage_quota",
            "storage.org_storage_breakdown",
            "storage.org_storage_usage_history",
            "publication.workflow_publications",
            "publication.publication_receipts",
            "catalog.apis",
            "auth.org_resource_restrictions",
            "auth.org_member_quota_limit",
            "auth.credentials"
    );

    /**
     * Durably records the tenant/key tuples before their metadata rows disappear.
     *
     * <p>The caller owns the surrounding transaction. Any enumeration, ownership or
     * enqueue failure is fatal so the transaction rolls back and retains the source
     * metadata. The dispatcher performs physical deletion only after this transaction
     * commits and can resume after a restart.
     */
    private void enqueueStorageErasures(String orgId) {
        final List<Map<String, Object>> objects;
        try {
            objects = jdbc.queryForList(
                    "SELECT s3_key, tenant_id FROM storage.storage "
                            + "WHERE organization_id::text = ? AND s3_key IS NOT NULL",
                    orgId);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Could not enumerate workspace storage objects before purge", failure);
        }

        int queued = 0;
        for (Map<String, Object> row : objects) {
            Object rawKey = row.get("s3_key");
            Object rawTenantId = row.get("tenant_id");
            String key = rawKey == null ? null : rawKey.toString();
            String tenantId = rawTenantId == null ? null : rawTenantId.toString();
            storageErasureOutbox.enqueue(orgId, tenantId, key);
            queued++;
        }
        if (queued > 0) {
            logger.info("Workspace purge: org {} - {} stored objects durably queued for erasure",
                    orgId, queued);
        }
    }

    /**
     * Deletes all operational org-scoped rows for {@code orgId}. Idempotent. Does NOT touch
     * the financial ledger / audit / organization row. Must be called inside a transaction.
     */
    public void purgeOperationalData(String orgId) {
        // conversation schema
        nativeExec("DELETE FROM conversation.messages WHERE conversation_id IN " +
                "(SELECT id FROM conversation.conversations WHERE organization_id::text = ?)", orgId);
        nativeExec("DELETE FROM conversation.conversations WHERE organization_id::text = ?", orgId);

        // orchestrator schema - FK cascades handle child tables (plan_versions, signals, epochs)
        // workflow_runs.id is UUID but workflow_step_data.run_id is VARCHAR - cast the subquery
        // id to text so the IN comparison doesn't trip 'character varying = uuid'.
        nativeExec("DELETE FROM orchestrator.workflow_step_data WHERE run_id IN " +
                "(SELECT id::text FROM orchestrator.workflow_runs WHERE organization_id::text = ?)", orgId);
        nativeExec("DELETE FROM orchestrator.workflow_runs WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM orchestrator.workflows WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM orchestrator.projects WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM orchestrator.notifications WHERE organization_id::text = ?", orgId);

        // agent schema (children before parents)
        nativeExec("DELETE FROM agent.agent_execution_tool_calls WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_execution_messages WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_execution_iterations WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_executions WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_task_recurrences WHERE organization_id::text = ?", orgId);
        // agent_task_notes/events/claims also ON DELETE CASCADE from agent_tasks; deleting them
        // explicitly first is defense-in-depth (survives a future cascade removal) and correctly ordered.
        nativeExec("DELETE FROM agent.agent_task_notes WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_task_events WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_task_claims WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agent_tasks WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.agents WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.skill_folders WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM agent.skills WHERE organization_id::text = ?", orgId);

        // interface schema
        nativeExec("DELETE FROM interface.interfaces WHERE organization_id::text = ?", orgId);

        // datasource schema
        nativeExec("DELETE FROM datasource.data_sources WHERE organization_id::text = ?", orgId);

        // trigger schema
        nativeExec("DELETE FROM trigger.scheduled_executions WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.standalone_webhooks WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.standalone_chat_endpoints WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.standalone_form_endpoints WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.webhook_tokens WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM trigger.datasource_trigger_subscriptions WHERE organization_id::text = ?", orgId);

        // storage schema - durable erasure records first, then metadata.
        // The outbox insert joins the caller transaction: a failure rolls back and keeps the
        // source rows, while a committed purge leaves restart-safe work for the dispatcher.
        enqueueStorageErasures(orgId);
        int storageRows = nativeExec("DELETE FROM storage.storage WHERE organization_id::text = ?", orgId);
        if (storageRows > 0) {
            logger.info("Workspace purge: deleted {} storage rows for org {}", storageRows, orgId);
        }
        // Org storage accounting: the V205 quota row + the V222 LIVE breakdown/usage-history tables
        // (storage.org_storage_*, the entity-mapped ones the trackers write - NOT the dead V205
        // storage.organization_storage_breakdown/usage_history). All keyed by organization_id VARCHAR.
        nativeExec("DELETE FROM storage.organization_storage_quota WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM storage.org_storage_breakdown WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM storage.org_storage_usage_history WHERE organization_id::text = ?", orgId);

        // publication schema (ORG-owned publications + org-scoped receipts)
        nativeExec("DELETE FROM publication.workflow_publications WHERE owner_type = 'ORG' AND owner_id::text = ?", orgId);
        nativeExec("DELETE FROM publication.publication_receipts WHERE organization_id::text = ?", orgId);

        // catalog schema - only workspace custom APIs. Global imported APIs have organization_id NULL.
        nativeExec("DELETE FROM catalog.mapping_definitions WHERE tool_id IN "
                + "(SELECT t.id FROM catalog.api_tools t JOIN catalog.apis a ON a.id=t.api_id "
                + "WHERE a.organization_id::text = ?)", orgId);
        nativeExec("DELETE FROM catalog.apis WHERE organization_id::text = ?", orgId);

        // auth schema - org-scoped OPERATIONAL rows (NOT the financial ledger / audit).
        // org_member_quota_limit.org_id is UUID (not organization_id VARCHAR); it has an
        // ON DELETE CASCADE on the org row, but the workspace flow keeps that row, so we
        // must delete it explicitly here.
        nativeExec("DELETE FROM auth.org_resource_restrictions WHERE organization_id::text = ?", orgId);
        nativeExec("DELETE FROM auth.org_member_quota_limit WHERE org_id = ?::uuid", orgId);
        nativeExec("DELETE FROM auth.credentials WHERE organization_id::text = ?", orgId);
    }

    /**
     * Executes one required org-scoped delete. Any failure is fatal: the caller's
     * {@code @Transactional} boundary rolls back every prior delete and leaves the purge retryable.
     */
    private int nativeExec(String sql, String orgId) {
        try {
            return jdbc.update(sql, orgId);
        } catch (RuntimeException failure) {
            logger.error("Workspace purge failed closed [{}] org={}: {}",
                    sql.substring(0, Math.min(sql.length(), 80)), orgId,
                    failure.getMessage());
            throw new WorkspacePurgeIncompleteException(sql, orgId, failure);
        }
    }

    static final class WorkspacePurgeIncompleteException extends IllegalStateException {
        WorkspacePurgeIncompleteException(String sql, String orgId, Throwable cause) {
            super("Workspace purge incomplete for org " + orgId + " at statement: "
                    + sql.substring(0, Math.min(sql.length(), 120)), cause);
        }
    }

}
