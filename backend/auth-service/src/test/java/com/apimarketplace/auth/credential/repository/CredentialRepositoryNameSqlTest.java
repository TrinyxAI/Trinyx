package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Postgres integration test for {@link CredentialRepository#updateName(Long, String, String)} - the
 * write behind "rename a credential". Proves against a live engine what a mocked repository
 * cannot see:
 *
 * <ul>
 *   <li>{@code name} is replaced and {@code updated_at} IS moved (unlike
 *       {@code touchLastUsed}): {@code computeStateVersion} keys the agent response cache on
 *       {@code MAX(updated_at)}, so a rename that left it behind would serve the old name,
 *   <li>{@code credential_data} stays byte-for-byte intact - the targeted UPDATE never goes
 *       through {@code save()} and so never re-encrypts the secret,
 *   <li>{@code integration}, {@code is_default} and {@code status} are untouched: they are what
 *       execution resolves on, so a rename must not disturb them,
 *   <li>a rename works on a row whose {@code tenant_id} is NOT the caller (workspace-shared
 *       credential renamed by another org member) - the UPDATE is scoped by workspace,
 *   <li>a row outside the workspace named by the caller is NOT renamed, including one the
 *       caller OWNS in another workspace, and a blank workspace matches nothing,
 *   <li>{@code findOtherIntegrationsWithNameForTenant} catches a collision in another
 *       workspace of the same tenant (the scope {@code findAllByTenantIdAndName} actually
 *       reads), returns the integration that decides whether it is refused, and reaches no
 *       encrypted column while doing it,
 *   <li>{@code findAllByTenantIdAndName} returns every duplicate, deterministically ordered,
 *   <li>an unknown or null id is a no-op returning 0 (never throws).
 * </ul>
 *
 * <p>None of that is reachable from a mocked repository: {@code verify(repo).updateName(7L,
 * "org-1", "x")} passes whatever SQL the method body happens to contain, so a WHERE clause
 * that lost its {@code organization_id} or an {@code ORDER BY} flipped to {@code DESC} would
 * leave every mocked test green. This class runs the real statements against a real engine.
 *
 * <p><b>How it runs.</b> It talks to a plain Postgres over JDBC rather than starting one:
 * Testcontainers needs a Docker socket, which the {@code arc-build} CI runners do not expose.
 * CI provides a {@code postgres:16-alpine} service container and sets
 * {@code CREDENTIAL_TEST_PG_URL}, so the class runs there for real.
 *
 * <p>The gate deliberately behaves differently on a dev machine and on CI, because a test that
 * skips in CI is the same as no test, and this file exists precisely because the previous one
 * ran nowhere. With {@code CI} unset and no URL it aborts as skipped (a laptop with no scratch
 * Postgres is not a failure). With {@code CI} set it REFUSES to skip: no URL is a hard failure
 * naming the workflow step that must provide it, so deleting those {@code env:} lines, or
 * moving the class out of the job that carries the service container, breaks the build instead
 * of quietly returning the file to being compiled and never executed. A URL that is set but
 * unreachable always fails, so a broken service container cannot pass either.
 *
 * <p>It creates and TRUNCATEs {@code auth.credentials}, so it refuses to start unless the
 * target database name contains {@code test}: pointing it at a dev database would wipe real
 * credentials. Locally:
 * {@code createdb lc_auth_test && CREDENTIAL_TEST_PG_URL=jdbc:postgresql://localhost:5432/lc_auth_test
 * mvn -pl auth-service test -Dtest=CredentialRepositoryNameSqlTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CredentialRepository name SQL - real Postgres")
class CredentialRepositoryNameSqlTest {

    private static final String URL = System.getenv("CREDENTIAL_TEST_PG_URL");
    private static final String USER =
            System.getenv().getOrDefault("CREDENTIAL_TEST_PG_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("CREDENTIAL_TEST_PG_PASSWORD", "postgres");

    private JdbcTemplate jdbc;
    private CredentialEncryptionService encryption;
    private CredentialRepository repository;
    private ObjectMapper objectMapper;

    @BeforeAll
    void setUpSchema() {
        requireDatabaseOnCi();

        // This class TRUNCATEs auth.credentials. A URL pointing at a dev or, worse, a shared
        // database would destroy real credentials, and the mistake is one copy-paste away, so
        // refuse anything that is not visibly a scratch database.
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase().contains("test")) {
            throw new IllegalStateException(
                    "CREDENTIAL_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test truncates auth.credentials), got: " + database);
        }
        awaitDatabase();

        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        this.jdbc = new JdbcTemplate(ds);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(ds);
        this.objectMapper = new ObjectMapper();

        // Encryption is a pass-through stub: identity is enough for the columns these tests read.
        // Held as a field so a test can assert the rename guard NEVER reaches it - see
        // guardReadsIntegrationsWithoutDecryptingAnySecret.
        CredentialEncryptionService enc = mock(CredentialEncryptionService.class);
        encryption = enc;
        when(enc.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.credentials");
        jdbc.execute("""
                CREATE TABLE auth.credentials (
                    id BIGSERIAL PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    name VARCHAR(255) NOT NULL,
                    integration VARCHAR(255),
                    type VARCHAR(50) NOT NULL,
                    environment VARCHAR(50) NOT NULL DEFAULT 'Production',
                    status VARCHAR(50) NOT NULL DEFAULT 'active',
                    description TEXT,
                    credential_data JSONB NOT NULL DEFAULT '{}',
                    scopes TEXT[],
                    tags TEXT[],
                    owner VARCHAR(255),
                    icon_url VARCHAR(500),
                    is_default BOOLEAN NOT NULL DEFAULT FALSE,
                    last_used TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        this.repository = new CredentialRepository(jdbc, namedJdbc, objectMapper, enc);
    }

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE auth.credentials RESTART IDENTITY");
    }

    @Test
    @DisplayName("replaces the name, moves updated_at and leaves the encrypted secret intact")
    void renamesRowAndMovesUpdatedAtOnly() {
        long id = insertCredential("tenant-1", "gmail Credential",
                Map.of("access_token", "ya29-secret", "refresh_token", "1//refresh"));
        jdbc.update("UPDATE auth.credentials SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), id);
        Timestamp updatedAtBefore = getTimestamp(id, "updated_at");
        String dataBefore = getCredentialDataText(id);

        int rows = repository.updateName(id, "org-1", "Gmail (work account)");

        assertThat(rows).isEqualTo(1);
        assertThat(getString(id, "name")).isEqualTo("Gmail (work account)");
        // updated_at MUST move: it is the agent response cache key (computeStateVersion).
        assertThat(getTimestamp(id, "updated_at")).isAfter(updatedAtBefore);
        // The secret is never re-encrypted by this path.
        assertThat(getCredentialDataText(id)).isEqualTo(dataBefore);
    }

    @Test
    @DisplayName("leaves integration, is_default and status untouched")
    void leavesResolutionColumnsUntouched() {
        long id = insertCredential("tenant-1", "gmail Credential", Map.of("access_token", "t"));
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", id);

        repository.updateName(id, "org-1", "Renamed");

        // These three are what execution resolves on. A rename that moved any of them
        // would silently re-point every workflow that has no credential_id pinned.
        assertThat(getString(id, "integration")).isEqualTo("gmail");
        assertThat(getString(id, "status")).isEqualTo("active");
        assertThat(jdbc.queryForObject(
                "SELECT is_default FROM auth.credentials WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("renames a workspace-shared row owned by another member (keyed on id alone)")
    void renamesRowOwnedByAnotherMember() {
        long id = insertCredential("owner-member", "Team Gmail", Map.of("access_token", "t"));

        // save()'s UPDATE is "WHERE id = ? AND tenant_id = ?" and would write 0 rows here;
        // updateName matches the workspace too, so a member can rename what the org shares.
        int rows = repository.updateName(id, "org-1", "Shared Gmail");

        assertThat(rows).isEqualTo(1);
        assertThat(getString(id, "name")).isEqualTo("Shared Gmail");
        assertThat(getString(id, "tenant_id")).isEqualTo("owner-member");
    }

    @Test
    @DisplayName("renames only the targeted row")
    void renamesOnlyTargetRow() {
        long first = insertCredential("tenant-1", "Gmail A", Map.of("access_token", "a"));
        long second = insertCredential("tenant-1", "Gmail B", Map.of("access_token", "b"));

        repository.updateName(first, "org-1", "Gmail renamed");

        assertThat(getString(first, "name")).isEqualTo("Gmail renamed");
        assertThat(getString(second, "name")).isEqualTo("Gmail B");
    }

    @Test
    @DisplayName("refuses to rename a row in another workspace, even for its own owner")
    void refusesRowOutsideTheNamedWorkspace() {
        long id = insertCredential("tenant-1", "Personal Gmail", Map.of("access_token", "t"));

        // The caller OWNS this row, but is acting in another workspace. Strict
        // isolation is pure org equality (CredentialService.matchesScope), so the
        // second lock has to refuse here too: an "OR tenant_id = ?" clause would
        // let a member reach into a workspace they are not currently in.
        int rows = repository.updateName(id, "org-OTHER", "Hijacked");

        assertThat(rows).isZero();
        assertThat(getString(id, "name")).isEqualTo("Personal Gmail");
    }

    @Test
    @DisplayName("refuses to rename a row belonging to a stranger's workspace")
    void refusesRowOfAnotherWorkspace() {
        long id = insertCredential("owner-member", "Team Gmail", Map.of("access_token", "t"));

        assertThat(repository.updateName(id, "org-OTHER", "Hijacked")).isZero();
        assertThat(getString(id, "name")).isEqualTo("Team Gmail");
    }

    @Test
    @DisplayName("refuses to rename when no workspace is supplied")
    void refusesWithoutWorkspace() {
        long id = insertCredential("tenant-1", "Gmail", Map.of("access_token", "t"));

        // A blank org must never widen to "match any row"; it matches nothing.
        assertThat(repository.updateName(id, null, "Renamed")).isZero();
        assertThat(repository.updateName(id, "  ", "Renamed")).isZero();
        assertThat(getString(id, "name")).isEqualTo("Gmail");
    }

    @Test
    @DisplayName("findOtherIntegrationsWithNameForTenant catches a collision in ANOTHER workspace of the same tenant")
    void detectsDuplicateAcrossTheTenantsOtherWorkspace() {
        long personal = insertCredential("tenant-1", "gmail", "slack", Map.of("access_token", "a"));
        jdbc.update("UPDATE auth.credentials SET organization_id = 'org-PERSONAL' WHERE id = ?", personal);
        long shared = insertCredential("tenant-1", "Shared", Map.of("access_token", "b"));

        // findAllByTenantIdAndName is TENANT-scoped: renaming the org row to "gmail" would
        // make that lookup pick between two rows, even though they sit in different
        // workspaces. An org-only probe would miss this and allow the collision.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(shared, "tenant-1", "gmail"))
                .containsExactly("slack");
    }

    @Test
    @DisplayName("findOtherIntegrationsWithNameForTenant sees a sibling with the same name and ignores the row itself")
    void detectsDuplicateNamesInScope() {
        long first = insertCredential("tenant-1", "Gmail", Map.of("access_token", "a"));
        long second = insertCredential("tenant-1", "Other", Map.of("access_token", "b"));

        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "tenant-1", "Gmail"))
                .containsExactly("gmail");
        // The row being renamed never collides with itself.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(first, "tenant-1", "Gmail")).isEmpty();
        // Case-insensitive and trimmed, unlike findAllByTenantIdAndName's exact `name = ?`.
        // Deliberately wider: catalog's run-time selector compares the typed LABEL with
        // trim + equalsIgnoreCase, so "Gmail" and "  gmail " are one credential to it and two
        // rows differing only that way are exactly the ambiguity it refuses to resolve.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "tenant-1", "gmail"))
                .containsExactly("gmail");
        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "tenant-1", "  GMAIL  "))
                .containsExactly("gmail");
        // Another tenant's name is not a collision.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(second, "another-tenant", "Gmail")).isEmpty();
    }

    @Test
    @DisplayName("findOtherIntegrationsWithNameForTenant returns every colliding integration, ordered, nulls kept")
    void collisionsCarryTheirIntegrationInOrder() {
        insertCredential("tenant-1", "grok", "slack", Map.of("access_token", "a"));
        long nameless = insertCredential("tenant-1", "grok", null, Map.of("access_token", "b"));
        long renamed = insertCredential("tenant-1", "My key", "xai", Map.of("access_token", "c"));
        // The default is the row inserted SECOND, so a plain scan would return it last: the
        // assertion below can only pass on the ORDER BY.
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", nameless);

        // The integration is the whole input to the refusal, and a NULL one is the shape whose
        // NAME is its identity: dropping nulls would silently stop refusing the one collision
        // that matters most. The order is the documented one (default first), so the log names
        // a stable row rather than whichever the scan happened to reach.
        assertThat(repository.findOtherIntegrationsWithNameForTenant(renamed, "tenant-1", "grok"))
                .containsExactly(null, "slack");
    }

    @Test
    @DisplayName("the rename guard reads integrations without decrypting a single secret")
    void guardReadsIntegrationsWithoutDecryptingAnySecret() {
        insertCredential("tenant-1", "gmail", "gmail", Map.of("access_token", "a"));
        long renamed = insertCredential("tenant-1", "My key", "gmail", Map.of("access_token", "b"));
        clearInvocations(encryption);

        assertThat(repository.findOtherIntegrationsWithNameForTenant(renamed, "tenant-1", "gmail"))
                .containsExactly("gmail");

        // Mapping these rows through CredentialRowMapper would decrypt the api_key /
        // access_token / password of every colliding row, including rows in a workspace the
        // caller cannot open, purely to decide not to use them. It would also turn the rename
        // into a 500 on any row encrypted under a rotated key, since decrypt throws and the
        // guard has no catch. One column, no mapper, no decryption.
        verifyNoInteractions(encryption);
    }

    @Test
    @DisplayName("findAllByTenantIdAndName returns EVERY duplicate, default first then oldest")
    void nameLookupReturnsAllDuplicatesDefaultFirst() {
        long older = insertCredential("tenant-1", "Gmail", Map.of("access_token", "older"));
        long newer = insertCredential("tenant-1", "Gmail", Map.of("access_token", "newer"));
        jdbc.update("UPDATE auth.credentials SET is_default = TRUE WHERE id = ?", newer);

        // ALL of them: the resolver walks this list to find the row the name actually
        // identifies, so a query returning one row would hide the right answer behind the
        // wrong one. The DEFAULT leads even though the scan reaches the older row first,
        // so this cannot pass on insertion order alone.
        assertThat(repository.findAllByTenantIdAndName("tenant-1", "Gmail"))
                .extracting(c -> c.id()).containsExactly(newer, older);

        // With no default set, the OLDEST leads. Not a compatibility guarantee: an unordered
        // scan does not return insertion order on this table (touchLastUsed rewrites a tuple
        // on every use), so duplicates were already resolving arbitrarily. Ascending is the
        // safer of two arbitrary choices, since the oldest row has had the most time to be
        // pinned by id somewhere.
        jdbc.update("UPDATE auth.credentials SET is_default = FALSE WHERE id = ?", newer);
        assertThat(repository.findAllByTenantIdAndName("tenant-1", "Gmail"))
                .extracting(c -> c.id()).containsExactly(older, newer);
    }

    @Test
    @DisplayName("rename on an unknown id is a no-op returning 0 (never throws)")
    void unknownIdIsNoOp() {
        assertThat(repository.updateName(999_999L, "org-1", "Ghost")).isZero();
    }

    @Test
    @DisplayName("null id or null name is a no-op returning 0")
    void nullArgumentsAreNoOp() {
        long id = insertCredential("tenant-1", "Gmail", Map.of("access_token", "t"));

        assertThat(repository.updateName(null, "org-1", "Gmail")).isZero();
        assertThat(repository.updateName(id, "org-1", null)).isZero();
        assertThat(getString(id, "name")).isEqualTo("Gmail");
    }

    // ────────────────────────── helpers ──────────────────────────

    /**
     * Skip on a laptop, fail on CI. The whole reason this class replaced a Testcontainers
     * {@code *IT} is that the old one was executed by no build and nobody could tell, so the
     * one outcome it must never produce is "silently did not run" in the place that is
     * supposed to run it.
     */
    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "CREDENTIAL_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only thing that runs the credential name/rename SQL against a "
                            + "real engine. Restore the env block on the workflow step that runs "
                            + "it, and keep that step in a job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set CREDENTIAL_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    /**
     * A CI service container answers on its port before Postgres finishes starting, so the
     * first connection can be refused on an otherwise healthy database. Retry briefly, then
     * fail loudly: skipping here would turn a broken CI service into a silent pass.
     */
    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException(
                        "CREDENTIAL_TEST_PG_URL is set but the database is unreachable: " + URL, e);
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private long insertCredential(String tenantId, String name, Map<String, Object> data) {
        return insertCredential(tenantId, name, "gmail", data);
    }

    /** {@code integration} may be null: that is the shape whose NAME is its identity. */
    private long insertCredential(String tenantId, String name, String integration, Map<String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            return jdbc.queryForObject("""
                    INSERT INTO auth.credentials (tenant_id, organization_id, name, integration, type, status, credential_data)
                    VALUES (?, 'org-1', ?, ?, 'OAuth2', 'active', ?::jsonb)
                    RETURNING id
                    """, Long.class, tenantId, name, integration, json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getString(long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM auth.credentials WHERE id = ?", String.class, id);
    }

    private Timestamp getTimestamp(long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM auth.credentials WHERE id = ?", Timestamp.class, id);
    }

    private String getCredentialDataText(long id) {
        return jdbc.queryForObject(
                "SELECT credential_data::text FROM auth.credentials WHERE id = ?", String.class, id);
    }
}
