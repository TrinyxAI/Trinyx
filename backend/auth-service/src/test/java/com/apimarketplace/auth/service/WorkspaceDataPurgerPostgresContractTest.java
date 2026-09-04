package com.apimarketplace.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Proves on real PostgreSQL that a required delete failure rolls back prior deletes and prevents
 * the caller's PURGED marker from committing.
 */
@EnabledIf("dockerAvailable")
class WorkspaceDataPurgerPostgresContractTest {

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void requiredDeleteFailureRollsBackTheGlobalPurgeTransaction() {
        try (PostgreSQLContainer<?> postgres =
                     new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                    CREATE SCHEMA auth;
                    CREATE SCHEMA conversation;
                    CREATE TABLE auth.organization (
                        id UUID PRIMARY KEY,
                        purged_at TIMESTAMPTZ
                    );
                    CREATE TABLE conversation.conversations (
                        id UUID PRIMARY KEY,
                        organization_id UUID NOT NULL
                    );
                    CREATE TABLE conversation.messages (
                        id UUID PRIMARY KEY,
                        conversation_id UUID NOT NULL REFERENCES conversation.conversations(id)
                    );
                    """);

            UUID organizationId = UUID.randomUUID();
            UUID conversationId = UUID.randomUUID();
            jdbc.update("INSERT INTO auth.organization(id) VALUES (?)", organizationId);
            jdbc.update("""
                    INSERT INTO conversation.conversations(id, organization_id)
                    VALUES (?,?)
                    """, conversationId, organizationId);
            jdbc.update("""
                    INSERT INTO conversation.messages(id, conversation_id)
                    VALUES (?,?)
                    """, UUID.randomUUID(), conversationId);

            WorkspaceDataPurger purger = new WorkspaceDataPurger(
                    jdbc, mock(WorkspaceStorageErasureOutbox.class));
            TransactionTemplate transaction = new TransactionTemplate(
                    new DataSourceTransactionManager(dataSource));

            assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
                purger.purgeOperationalData(organizationId.toString());
                jdbc.update("""
                        UPDATE auth.organization SET purged_at=now() WHERE id=?
                        """, organizationId);
            }))
                    .isInstanceOf(WorkspaceDataPurger.WorkspacePurgeIncompleteException.class)
                    .hasMessageContaining("orchestrator.workflow_step_data");

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM conversation.messages",
                    Integer.class)).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM conversation.conversations",
                    Integer.class)).isOne();
            assertThat(jdbc.queryForObject(
                    "SELECT purged_at IS NULL FROM auth.organization WHERE id=?",
                    Boolean.class, organizationId)).isTrue();
        }
    }

    static boolean dockerAvailable() {
        boolean required = Boolean.getBoolean("trinyx.contract.docker.required");
        try {
            boolean available = DockerClientFactory.instance().isDockerAvailable();
            if (required && !available) {
                throw new IllegalStateException(
                        "WorkspaceDataPurgerPostgresContractTest requires Docker in the CI contract gate");
            }
            return available;
        } catch (Throwable unavailable) {
            if (required) {
                throw new IllegalStateException(
                        "WorkspaceDataPurgerPostgresContractTest requires Docker in the CI contract gate",
                        unavailable);
            }
            return false;
        }
    }
}
