package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeInstallPing;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the fleet-ledger write statements and its purge against a real Postgres.
 *
 * <p>They are the statements no other test can execute: {@code ON CONFLICT}, {@code ctid} and
 * {@code LIMIT} inside a DELETE are all Postgres, which H2 does not implement, so
 * {@link CeInstallPingRepositoryPersistenceTest} can only cover the reads, and every other test
 * hands the repository to Mockito, which will happily accept SQL no engine would parse.
 *
 * <p>Two things make this hard to fool. The DDL is the actual migration file, executed verbatim, so
 * the table here cannot drift from the one production gets. And the statement is read off
 * {@link CeInstallPingRepository} by reflection rather than copied, so editing a production
 * query into something Postgres rejects, or into something that stops preserving
 * {@code first_seen_at}, fails here instead of shipping.
 *
 * <p><b>How it runs.</b> Plain JDBC rather than Testcontainers: the {@code arc-build} CI runners
 * expose no Docker socket. CI provides a {@code postgres:16-alpine} service container and sets
 * {@code CREDENTIAL_TEST_PG_URL} (the scratch-Postgres URL the auth-service SQL tests share), so
 * this executes there for real.
 *
 * <p>The gate is deliberately asymmetric, because a test that skips on CI is the same as no test.
 * With {@code CI} unset and no URL it aborts as skipped: a laptop with no scratch Postgres is not a
 * failure. With {@code CI} set it REFUSES to skip, so moving this class out of the job carrying the
 * service container breaks the build rather than quietly returning it to being compiled and never
 * executed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CeInstallPing SQL - real Postgres")
class CeInstallPingSqlPostgresTest {

    private static final String URL = System.getenv("CREDENTIAL_TEST_PG_URL");
    private static final String USER =
            System.getenv().getOrDefault("CREDENTIAL_TEST_PG_USER", "postgres");
    private static final String PASSWORD =
            System.getenv().getOrDefault("CREDENTIAL_TEST_PG_PASSWORD", "postgres");

    /** The production statements, read from the repository so this test cannot drift from them. */
    private static final String REFRESH = sqlOf("refreshSighting", UUID.class, String.class);
    private static final String INSERT = sqlOf("insertSighting", UUID.class, String.class);
    private static final String PURGE = sqlOf("purgeUnseenSince", Instant.class, int.class);
    private static final String COUNTS = sqlOf("fleetCounts", Instant.class, Instant.class);

    private NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    void setUpSchema() throws Exception {
        requireDatabaseOnCi();

        // This class DROPs auth.ce_install_ping. A URL pointing at a dev or shared database would
        // destroy a real ledger, and that mistake is one copy-paste away.
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("[?]")[0];
        if (!database.toLowerCase().contains("test")) {
            throw new IllegalStateException(
                    "CREDENTIAL_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops auth.ce_install_ping), got: " + database);
        }
        awaitDatabase();

        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        this.jdbc = new NamedParameterJdbcTemplate(ds);

        jdbc.getJdbcTemplate().execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS auth.ce_install_ping");
        jdbc.getJdbcTemplate().execute("DROP TABLE IF EXISTS auth.ce_install");
        // The migration itself, so the table under test is the table production gets.
        jdbc.getJdbcTemplate().execute(migrationSql());
    }

    @BeforeEach
    void clearLedger() {
        jdbc.getJdbcTemplate().execute("TRUNCATE auth.ce_install_ping");
    }

    /** What the recorder does: refresh if the ledger knows it, otherwise insert. */
    private void ping(UUID installId, String version) {
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("installId", installId)
                .addValue("version", version);
        if (jdbc.update(REFRESH, args) == 0) {
            jdbc.update(INSERT, args);
        }
    }

    private Map<String, Object> row(UUID installId) {
        return jdbc.queryForMap(
                "SELECT * FROM auth.ce_install_ping WHERE install_id = :id",
                new MapSqlParameterSource("id", installId));
    }

    @Test
    @DisplayName("the migration seeds exactly one anonymous install identity")
    void migrationSeedsOneIdentity() {
        List<Map<String, Object>> rows =
                jdbc.getJdbcTemplate().queryForList("SELECT * FROM auth.ce_install");

        assertThat(rows).hasSize(1);
        // The column is typed UUID, so isInstanceOf could not fail. What matters is that the seed
        // produced a real random value rather than a fixed or nil one: a nil UUID baked into every
        // install would collapse the whole fleet onto one row.
        assertThat((UUID) rows.get(0).get("install_id")).isNotEqualTo(new UUID(0L, 0L));
    }

    @Test
    @DisplayName("re-running the seed keeps the identity the install already has")
    void seedIsIdempotent() {
        UUID before = jdbc.getJdbcTemplate().queryForObject(
                "SELECT install_id FROM auth.ce_install", UUID.class);

        jdbc.getJdbcTemplate().execute(
                "INSERT INTO auth.ce_install (id, install_id) VALUES (1, gen_random_uuid()) "
                        + "ON CONFLICT (id) DO NOTHING");

        // An id that changed under a live install would count that install twice for the rest of
        // its life, and nothing anywhere would report an error.
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT install_id FROM auth.ce_install", UUID.class)).isEqualTo(before);
    }

    @Test
    @DisplayName("a first sighting inserts the install")
    void firstSightingInserts() {
        UUID install = UUID.randomUUID();

        ping(install, "0.2.13");

        Map<String, Object> row = row(install);
        assertThat(row.get("last_version")).isEqualTo("0.2.13");
        // Both stamps are NOT NULL DEFAULT now(), so asserting they are non-null could never fail.
        // What the insert has to get right is that they START equal: the purge distinguishes a
        // forged id from a real install by exactly that, so a first sighting whose last_seen_at ran
        // ahead of its first_seen_at would make every new install look already-confirmed.
        assertThat(row.get("first_seen_at")).isEqualTo(row.get("last_seen_at"));
    }

    @Test
    @DisplayName("one statement returns all five fleet figures, over the right columns")
    void fleetCountsAnswersEveryWindow() {
        Instant now = Instant.now();
        // Two installs inside both windows, one only inside the 30-day one, one long gone. The
        // "new" figures differ from the "active" ones, so a copy-paste between the two FILTER
        // clauses cannot pass: that is the whole failure this covers, and it is invisible in a
        // mock, which returns whatever the test hands it whatever the SQL says.
        seed(UUID.randomUUID(), now.minus(40, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
        seed(UUID.randomUUID(), now.minus(3, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS));
        seed(UUID.randomUUID(), now.minus(20, ChronoUnit.DAYS), now.minus(20, ChronoUnit.DAYS));
        seed(UUID.randomUUID(), now.minus(90, ChronoUnit.DAYS), now.minus(90, ChronoUnit.DAYS));
        // Three more, purely so that all FIVE figures come out different. With total=4 the row read
        // 4,2,3,1,2: positions 1 and 4 were equal, so swapping active_short with new_long in the
        // SELECT list survived here, and that swap feeds the 7-day live-fleet gauge with the
        // 30-day new-installs count, because CeFleetReader reads the row BY POSITION.
        seed(UUID.randomUUID(), now.minus(5, ChronoUnit.DAYS), now.minus(5, ChronoUnit.DAYS));
        seed(UUID.randomUUID(), now.minus(50, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS));
        seed(UUID.randomUUID(), now.minus(25, ChronoUnit.DAYS), now.minus(4, ChronoUnit.DAYS));

        Map<String, Object> counts = jdbc.queryForMap(COUNTS, new MapSqlParameterSource()
                .addValue("shortAgo", Timestamp.from(now.minus(7, ChronoUnit.DAYS)))
                .addValue("longAgo", Timestamp.from(now.minus(30, ChronoUnit.DAYS))));

        // count(*) FILTER is Postgres syntax that no mock and no H2-free path can validate, and a
        // statement this shape either parses or does not: getting it here is the only proof the
        // five gauges are not about to fail every refresh in production.
        assertThat(((Number) counts.get("total")).longValue()).isEqualTo(7);
        assertThat(((Number) counts.get("active_short")).longValue()).isEqualTo(5);
        assertThat(((Number) counts.get("active_long")).longValue()).isEqualTo(6);
        assertThat(((Number) counts.get("new_short")).longValue()).isEqualTo(2);
        assertThat(((Number) counts.get("new_long")).longValue()).isEqualTo(4);

        // And again BY POSITION, because that is how CeFleetReader reads the row. Asserting only
        // by alias leaves an alias glued to its expression through any reorder of the SELECT list,
        // so swapping two lines would move the 30-day figure onto the 7-day gauge with this test
        // still green. Every value here differs, so no swap can survive.
        long[] ordinals = jdbc.query(COUNTS, new MapSqlParameterSource()
                        .addValue("shortAgo", Timestamp.from(now.minus(7, ChronoUnit.DAYS)))
                        .addValue("longAgo", Timestamp.from(now.minus(30, ChronoUnit.DAYS))),
                rs -> {
                    assertThat(rs.next()).isTrue();
                    return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5)};
                });
        assertThat(ordinals).containsExactly(7, 5, 6, 2, 4);
        assertThat(ordinals).as("all five must differ, or a swap hides in the equal pair")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every window boundary is exclusive, on both columns")
    void fleetCountsWindowsAreHalfOpen() {
        Instant now = Instant.now();
        Instant shortAgo = now.minus(7, ChronoUnit.DAYS);
        Instant longAgo = now.minus(30, ChronoUnit.DAYS);
        Instant before = longAgo.minus(1, ChronoUnit.DAYS);
        // One row sitting exactly ON each of the four boundaries the statement compares, so each
        // comparison has a row that flips only if THAT token is relaxed.
        seed(UUID.randomUUID(), before, longAgo);      // on the active_long boundary
        seed(UUID.randomUUID(), before, shortAgo);     // on the active_short boundary
        seed(UUID.randomUUID(), longAgo, now);         // on the new_long boundary
        seed(UUID.randomUUID(), shortAgo, now);        // on the new_short boundary

        Map<String, Object> counts = jdbc.queryForMap(COUNTS, new MapSqlParameterSource()
                .addValue("shortAgo", Timestamp.from(shortAgo))
                .addValue("longAgo", Timestamp.from(longAgo)));

        // The purge deletes last_seen_at < cutoff and this counts last_seen_at > cutoff, so a row
        // exactly on the boundary is neither. Relax any one of the four to >= and its row joins
        // that count: the active_long case is also the one that would make a row both counted
        // alive and eligible for deletion, the single state the retention floor in
        // CeInstallPingRetentionScheduler exists to make impossible. All four are asserted because
        // they are four separate tokens and three of them were pinned by nothing.
        assertThat(((Number) counts.get("total")).longValue()).isEqualTo(4);
        assertThat(((Number) counts.get("active_short")).longValue()).isEqualTo(2);
        assertThat(((Number) counts.get("active_long")).longValue()).isEqualTo(3);
        assertThat(((Number) counts.get("new_short")).longValue()).isZero();
        assertThat(((Number) counts.get("new_long")).longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty ledger still answers one row, of zeroes")
    void fleetCountsAnswersOnAnEmptyLedger() {
        Map<String, Object> counts = jdbc.queryForMap(COUNTS, new MapSqlParameterSource()
                .addValue("shortAgo", Timestamp.from(Instant.now().minus(7, ChronoUnit.DAYS)))
                .addValue("longAgo", Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS))));

        // The reader reads row 0 without guarding, which is correct ONLY because an aggregate with
        // no GROUP BY always returns exactly one row. Add a GROUP BY and a fresh install would get
        // an IndexOutOfBoundsException on every refresh instead of five zeroes.
        assertThat(((Number) counts.get("total")).longValue()).isZero();
        assertThat(((Number) counts.get("active_short")).longValue()).isZero();
    }

    /** Inserts an install with the two stamps set explicitly, which no production path does. */
    private void seed(UUID installId, Instant firstSeen, Instant lastSeen) {
        jdbc.update("INSERT INTO auth.ce_install_ping "
                        + "(install_id, first_seen_at, last_seen_at, last_version) "
                        + "VALUES (:id, :first, :last, '0.2.13')",
                new MapSqlParameterSource()
                        .addValue("id", installId)
                        .addValue("first", Timestamp.from(firstSeen))
                        .addValue("last", Timestamp.from(lastSeen)));
    }

    @Test
    @DisplayName("the entity maps exactly the columns the migration creates")
    void entityAndMigrationAgreeOnColumns() {
        Set<String> ddlColumns = new HashSet<>(jdbc.getJdbcTemplate().queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'auth' AND table_name = 'ce_install_ping'",
                String.class));
        Set<String> mapped = new HashSet<>();
        for (Field field : CeInstallPing.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column != null) {
                mapped.add(column.name());
            }
        }

        // The H2 slice test creates its table FROM the entity, so a misspelled @Column name there
        // is self-consistent and passes, and the raw-SQL tests here never touch the mapping at all.
        // This is the only place the two definitions are compared.
        assertThat(mapped).isNotEmpty().isEqualTo(ddlColumns);
    }

    @Test
    @DisplayName("a repeat sighting refreshes the version and never moves first_seen_at")
    void repeatSightingPreservesFirstSeen() {
        UUID install = UUID.randomUUID();
        ping(install, "0.2.12");
        Instant firstSeen = ((Timestamp) row(install).get("first_seen_at")).toInstant();

        ping(install, "0.2.13");

        Map<String, Object> row = row(install);
        assertThat(row.get("last_version")).isEqualTo("0.2.13");
        // first_seen_at is what makes retention answerable: overwrite it and every install looks
        // new forever, so "new installs this week" would just equal "installs seen this week".
        // Exact, not within a tolerance: the upsert must not touch this column at all, and a
        // tolerance would wave through a future LEAST(...) that shifted it by less than a
        // millisecond.
        assertThat(((Timestamp) row.get("first_seen_at")).toInstant()).isEqualTo(firstSeen);
    }

    @Test
    @DisplayName("a repeat sighting moves last_seen_at forward")
    void repeatSightingMovesLastSeen() {
        UUID install = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.ce_install_ping "
                        + "(install_id, first_seen_at, last_seen_at, last_version) "
                        + "VALUES (:id, now() - interval '40 days', now() - interval '40 days', '0.2.10')",
                new MapSqlParameterSource("id", install));

        ping(install, "0.2.13");

        Instant lastSeen = ((Timestamp) row(install).get("last_seen_at")).toInstant();
        // Every "is this install alive" read is a last_seen_at comparison; a stamp that did not
        // move would retire the whole fleet after 30 days. The margin is generous on purpose: the
        // stamp comes from the database server and the bound from the JVM, and on CI those are two
        // containers whose clocks are only loosely aligned.
        assertThat(lastSeen).isAfter(Instant.now().minus(30, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("a null version keeps the version already known instead of blanking it")
    void nullVersionKeepsTheKnownOne() {
        UUID install = UUID.randomUUID();
        ping(install, "0.2.13");

        ping(install, null);

        // A request that omits the version must not erase what we knew, or the breakdown decays to
        // 'unknown' for every install that ever sent one bare request.
        assertThat(row(install).get("last_version")).isEqualTo("0.2.13");
    }

    @Test
    @DisplayName("a first sighting with no version is recorded, not refused")
    void firstSightingWithoutVersionIsAccepted() {
        UUID install = UUID.randomUUID();

        ping(install, null);

        assertThat(row(install).get("last_version")).isNull();
    }

    @Test
    @DisplayName("concurrent first sightings of one install raise no key violation")
    void concurrentSightingsLoseNothing() throws Exception {
        UUID install = UUID.randomUUID();
        int threads = 8;
        int perThread = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ping(install, "0.2.13");
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        // ON CONFLICT DO NOTHING on the insert is what makes this safe: several cloud pods answer
        // the same public feed, so the FIRST sighting of an install is genuinely concurrent, and a
        // plain insert turns that into a primary-key violation on the endpoint the fleet polls for
        // security releases.
        assertThat(failures.get()).isZero();
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM auth.ce_install_ping WHERE install_id = ?", Long.class, install))
                .isEqualTo(1L);
        assertThat(row(install).get("last_version")).isEqualTo("0.2.13");
    }

    @Test
    @DisplayName("the purge drops only installs unseen before the cutoff, and reports how many")
    void purgeDropsOnlyStaleInstalls() {
        UUID stale = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        jdbc.update("INSERT INTO auth.ce_install_ping "
                        + "(install_id, first_seen_at, last_seen_at, last_version) VALUES "
                        + "(:stale,  now() - interval '400 days', now() - interval '300 days', '0.1.0'), "
                        + "(:recent, now() - interval '400 days', now() - interval '2 days',  '0.2.13')",
                new MapSqlParameterSource().addValue("stale", stale).addValue("recent", recent));

        int removed = jdbc.update(PURGE, new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(Instant.now().minus(180, ChronoUnit.DAYS)))
                .addValue("batchSize", 5_000));

        // The purge is keyed on last_seen_at, never first_seen_at: an install running happily for
        // two years would otherwise be deleted for the crime of being old, and the fleet count
        // would shrink as it aged.
        assertThat(removed).isEqualTo(1);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM auth.ce_install_ping", Long.class)).isEqualTo(1L);
        assertThat(row(recent).get("last_version")).isEqualTo("0.2.13");
    }

    @Test
    @DisplayName("the purge removes at most one batch per statement")
    void purgeHonoursItsBatchSize() {
        for (int i = 0; i < 7; i++) {
            jdbc.update("INSERT INTO auth.ce_install_ping "
                            + "(install_id, first_seen_at, last_seen_at, last_version) "
                            + "VALUES (:id, now() - interval '400 days', now() - interval '300 days', '0.1.0')",
                    new MapSqlParameterSource("id", UUID.randomUUID()));
        }

        int removed = jdbc.update(PURGE, new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(Instant.now().minus(180, ChronoUnit.DAYS)))
                .addValue("batchSize", 3));

        // The batching is what keeps the run with the most to remove, which is the run that matters,
        // from becoming one long transaction holding locks on a table an anonymous caller can grow.
        // Passing a batch size larger than the row count, as the sibling purge test does, would let
        // the LIMIT be deleted from the production statement with every test still green.
        assertThat(removed).isEqualTo(3);
        assertThat(jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM auth.ce_install_ping", Long.class)).isEqualTo(4L);
    }


    @Test
    @DisplayName("the refresh reports whether the ledger knew the install, which is the whole design")
    void refreshReportsWhetherTheInstallWasKnown() {
        UUID install = UUID.randomUUID();
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("installId", install)
                .addValue("version", "0.2.13");

        int beforeInsert = jdbc.update(REFRESH, args);
        jdbc.update(INSERT, args);
        int afterInsert = jdbc.update(REFRESH, args);

        // This return value is the entire bound. The recorder gates inserts and never refreshes,
        // so if the refresh stopped reporting 0 for an unknown install nothing would ever be
        // inserted, and if it stopped reporting 1 for a known one every real install would be
        // budget-gated and a flood could starve the fleet out of the count.
        assertThat(beforeInsert).isZero();
        assertThat(afterInsert).isEqualTo(1);
    }

    @Test
    @DisplayName("a concurrent second insert of the same install is a no-op, not a violation")
    void secondInsertIsANoOp() {
        UUID install = UUID.randomUUID();
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("installId", install)
                .addValue("version", "0.2.13");
        jdbc.update(INSERT, args);

        int second = jdbc.update(INSERT, new MapSqlParameterSource()
                .addValue("installId", install)
                .addValue("version", "9.9.9"));

        // DO NOTHING, not DO UPDATE: the insert path must never move last_seen_at, or a caller
        // could refresh a row it does not own past the budget by pretending it is new.
        assertThat(second).isZero();
        assertThat(row(install).get("last_version")).isEqualTo("0.2.13");
    }

    /**
     * Reads a production statement off the repository by reflection, so this test cannot drift from
     * the SQL that actually runs.
     */
    private static String sqlOf(String method, Class<?>... parameters) {
        try {
            Query query = CeInstallPingRepository.class.getMethod(method, parameters)
                    .getAnnotation(Query.class);
            if (query == null || query.value().isBlank()) {
                throw new IllegalStateException(
                        "CeInstallPingRepository." + method + " no longer carries a @Query - this "
                                + "class exists to execute those statements against a real engine");
            }
            return query.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("CeInstallPingRepository." + method + " was renamed", e);
        }
    }

    /** The real migration file, so the DDL under test is the DDL production gets. */
    private static String migrationSql() throws Exception {
        Path path = Path.of("..", "migration-service", "src", "main", "resources", "db",
                "migration", "V454__ce_install_telemetry.sql");
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "V454__ce_install_telemetry.sql not found at " + path.toAbsolutePath()
                            + " - this class executes the real migration rather than a copy of its "
                            + "DDL, so it must run from the auth-service module directory");
        }
        return Files.readString(path);
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "CREDENTIAL_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only thing that runs the fleet-ledger upsert against a real "
                            + "engine (H2 has no ON CONFLICT ... DO UPDATE). Keep this class in a "
                            + "job carrying the postgres service, with that env block.");
        }
        Assumptions.abort(
                "no scratch Postgres: set CREDENTIAL_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

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
}
