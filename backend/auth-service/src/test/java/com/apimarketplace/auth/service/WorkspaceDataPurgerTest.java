package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkspaceDataPurgerTest {

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    private static final List<String> RETAINED_TABLES = List.of(
            "auth.credit_ledger", "auth.usage_cycle", "auth.credit_reconciliation_log",
            "auth.organization_audit_event", "auth.billing_customer", "auth.subscription");

    @Mock
    private WorkspaceStorageErasureOutbox storageErasureOutbox;

    private RecordingJdbc jdbc;
    private WorkspaceDataPurger purger;

    @BeforeEach
    void setUp() {
        jdbc = new RecordingJdbc();
        purger = new WorkspaceDataPurger(jdbc, storageErasureOutbox);
        doAnswer(invocation -> {
            jdbc.events.add("OUTBOX " + invocation.getArgument(2));
            return null;
        }).when(storageErasureOutbox).enqueue(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private List<String> capturePurgeSql() {
        purger.purgeOperationalData(ORG_ID);
        return jdbc.updates;
    }

    @Test
    @DisplayName("every purge statement is org-scoped")
    void everyStatementIsOrgScoped() {
        for (String sql : capturePurgeSql()) {
            assertThat(sql).containsIgnoringCase("DELETE FROM")
                    .containsIgnoringCase("WHERE")
                    .contains("?");
        }
    }

    @Test
    @DisplayName("stored object keys are durably queued before metadata is deleted")
    void keysAreReadBeforeTheRowsThatCarryThemAreDeleted() {
        jdbc.storageRows = List.of(Map.of(
                "s3_key", "tenant-9/report.pdf",
                "tenant_id", "tenant-9"));

        purger.purgeOperationalData(ORG_ID);

        int select = jdbc.events.indexOf("SELECT storage");
        int outbox = jdbc.events.indexOf("OUTBOX tenant-9/report.pdf");
        int metadataDelete = indexContaining(jdbc.events, "DELETE FROM storage.storage");
        assertThat(select).isGreaterThanOrEqualTo(0);
        assertThat(outbox).isGreaterThan(select);
        assertThat(metadataDelete).isGreaterThan(outbox);
        verify(storageErasureOutbox).enqueue(
                ORG_ID, "tenant-9", "tenant-9/report.pdf");
    }

    @Test
    @DisplayName("an erasure enqueue failure aborts before storage metadata is deleted")
    void enqueueFailureIsFatal() {
        jdbc.storageRows = List.of(Map.of(
                "s3_key", "tenant-9/first.bin",
                "tenant_id", "tenant-9"));
        doThrow(new IllegalStateException("outbox unavailable"))
                .when(storageErasureOutbox)
                .enqueue(ORG_ID, "tenant-9", "tenant-9/first.bin");

        assertThatThrownBy(() -> purger.purgeOperationalData(ORG_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox unavailable");
        assertThat(jdbc.updates).noneMatch(
                statement -> statement.contains("DELETE FROM storage.storage"));
    }

    @Test
    @DisplayName("purge never touches retained financial or audit tables")
    void neverTouchesRetainedTables() {
        for (String sql : capturePurgeSql()) {
            String lower = sql.toLowerCase(Locale.ROOT);
            for (String retained : RETAINED_TABLES) {
                assertThat(lower).doesNotContain("delete from " + retained);
            }
            assertThat(lower).doesNotContain("delete from auth.organization ");
        }
    }

    @Test
    @DisplayName("purge covers every declared org-scoped table")
    void coversEveryDeclaredTable() {
        List<String> sql = capturePurgeSql();
        for (String table : WorkspaceDataPurger.PURGED_ORG_SCOPED_TABLES) {
            assertThat(sql).anyMatch(statement ->
                    statement.contains("DELETE FROM " + table));
        }
    }

    @Test
    @DisplayName("workspace custom APIs are deleted without touching global catalog rows")
    void customApisUseExactOrganizationPredicate() {
        List<String> sql = capturePurgeSql();

        assertThat(sql).anyMatch(statement -> statement.equals(
                "DELETE FROM catalog.apis WHERE organization_id::text = ?"));
        assertThat(sql).anyMatch(statement ->
                statement.contains("DELETE FROM catalog.mapping_definitions")
                        && statement.contains("a.organization_id::text = ?"));
        assertThat(sql).noneMatch(statement ->
                statement.contains("DELETE FROM catalog.apis")
                        && !statement.contains("organization_id::text = ?"));
    }

    @Test
    @DisplayName("workflow step-data subquery casts run id")
    void stepDataSubqueryCastsRunId() {
        assertThat(capturePurgeSql()).anyMatch(sql ->
                sql.contains("orchestrator.workflow_step_data")
                        && sql.contains("SELECT id::text FROM orchestrator.workflow_runs"));
    }

    @Test
    @DisplayName("organization predicates are type-safe")
    void orgIdPredicatesAreTypeSafe() {
        for (String sql : capturePurgeSql()) {
            assertThat(sql).doesNotContain("organization_id = ?")
                    .doesNotContain("owner_id = ?");
        }
    }

    @Test
    @DisplayName("credentials are deleted by organization, not user tenant")
    void credentialsDeletedByOrgNotUser() {
        List<String> sql = capturePurgeSql();
        assertThat(sql).anyMatch(statement -> statement.contains(
                "DELETE FROM auth.credentials WHERE organization_id::text = ?"));
        assertThat(sql).noneMatch(statement ->
                statement.contains("auth.credentials WHERE tenant_id"));
    }

    @Test
    @DisplayName("a required SQL failure aborts immediately and remains retryable")
    void failingStatementAbortsTheWholePurge() {
        jdbc.failAtUpdate = 1;

        assertThatThrownBy(() -> purger.purgeOperationalData(ORG_ID))
                .isInstanceOf(WorkspaceDataPurger.WorkspacePurgeIncompleteException.class)
                .hasMessageContaining("Workspace purge incomplete");

        assertThat(jdbc.updates).hasSize(1);
        assertThat(jdbc.updates.getFirst())
                .contains("DELETE FROM conversation.messages");
    }

    private static int indexContaining(List<String> values, String fragment) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final List<String> updates = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private List<Map<String, Object>> storageRows = List.of();
        private int failAtUpdate = -1;

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            events.add(sql);
            if (updates.size() == failAtUpdate) {
                throw new DataIntegrityViolationException("required delete failed");
            }
            return 0;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            events.add("SELECT storage");
            return storageRows;
        }
    }
}
