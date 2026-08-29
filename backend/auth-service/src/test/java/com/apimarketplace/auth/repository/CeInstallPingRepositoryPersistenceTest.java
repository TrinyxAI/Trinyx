package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeInstallPing;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence tests for the fleet ledger's READ side.
 *
 * <p>Every other test of this feature hands the repository to Mockito, which will happily accept a
 * finder that Spring Data cannot derive and a native query that no database can parse - the context
 * would then fail at startup, in production, on a path nothing else covers. These boot a real
 * EntityManager so the queries have to be resolvable.
 *
 * <p>The writes are deliberately absent here: both use {@code ON CONFLICT}, which H2 does not
 * implement, so this class covers the reads only and the writes are verified against a real engine
 * by {@code CeInstallPingSqlPostgresTest}. Do not "fix" that gap by rewriting them as
 * read-modify-write to please H2: several pods answer the same feed, and the first sighting of an
 * install is genuinely concurrent.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = CeInstallPingRepositoryPersistenceTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ce_install_ping;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("CeInstallPing persistence - the fleet aggregates resolve against a real EntityManager")
class CeInstallPingRepositoryPersistenceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CeInstallPing.class)
    @EnableJpaRepositories(basePackageClasses = CeInstallPingRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private CeInstallPingRepository repository;

    @Autowired
    private EntityManager entityManager;

    /** Inserts a ledger row directly: the production write is a Postgres-only upsert. */
    private void seed(String id, int firstSeenDaysAgo, int lastSeenDaysAgo, String version) {
        Instant now = Instant.now();
        entityManager.createNativeQuery("""
                        INSERT INTO auth.ce_install_ping (install_id, first_seen_at, last_seen_at, last_version)
                        VALUES (?, ?, ?, ?)
                        """)
                .setParameter(1, UUID.fromString(id))
                .setParameter(2, now.minus(firstSeenDaysAgo, ChronoUnit.DAYS))
                .setParameter(3, now.minus(lastSeenDaysAgo, ChronoUnit.DAYS))
                .setParameter(4, version)
                .executeUpdate();
    }

    @BeforeEach
    void seedFleet() {
        // Native, because the repository deliberately exposes no deleteAll: it extends the bare
        // Repository marker so that an anonymous counter cannot grow a per-install API by
        // inheritance.
        entityManager.createNativeQuery("DELETE FROM auth.ce_install_ping").executeUpdate();
        entityManager.flush();
        seed("11111111-1111-1111-1111-111111111111", 40, 1, "0.2.13");
        seed("22222222-2222-2222-2222-222222222222", 20, 2, "0.2.13");
        seed("33333333-3333-3333-3333-333333333333", 3, 1, "0.2.12");
        seed("44444444-4444-4444-4444-444444444444", 90, 60, "0.1.22");
        seed("55555555-5555-5555-5555-555555555555", 1, 0, null);
        // Inside the 30-day window and outside the 7-day one, so active_short and active_long come
        // out DIFFERENT. With them equal, swapping the two FILTER clauses in the statement is
        // invisible: the counts row is read by position, so the 7-day gauge would then carry the
        // 30-day figure everywhere, on a card whose title says otherwise.
        seed("66666666-6666-6666-6666-666666666666", 20, 20, "0.2.13");
        // And a seventh, so that all FIVE positional counts differ (7,4,6,2,5). With the fixture
        // at six rows, active_short and new_long were both 4: swapping those two expressions in
        // the SELECT list was invisible here, and this is the only ledger test that runs without
        // a database, so it is the one a developer sees.
        seed("77777777-7777-7777-7777-777777777777", 25, 25, "0.2.13");
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("active counts follow last_seen_at, so a long-dead install drops out of the 7-day window")
    void activeCountsUseLastSeen() {
        Instant now = Instant.now();

        assertThat(repository.count()).isEqualTo(7);
        // The install last seen 60 days ago is still IN the ledger but must not be counted alive:
        // "total" and "active" measuring the same thing is what makes a fleet number meaningless.
        Object[] counts = repository.fleetCounts(
                now.minus(7, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS)).get(0);
        assertThat(((Number) counts[1]).longValue()).as("seen in the last 7 days").isEqualTo(4);
        assertThat(((Number) counts[2]).longValue()).as("seen in the last 30 days").isEqualTo(6);
        assertThat(((Number) counts[0]).longValue()).as("the whole ledger").isEqualTo(7);
    }

    @Test
    @DisplayName("new counts follow first_seen_at, which the upsert never overwrites")
    void newCountsUseFirstSeen() {
        Instant now = Instant.now();

        // An install seen daily for 40 days is not new, even though it pinged an hour ago. This is
        // the whole reason first_seen_at is left alone on update.
        Object[] counts = repository.fleetCounts(
                now.minus(7, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS)).get(0);
        assertThat(((Number) counts[3]).longValue()).as("first seen in the last 7 days").isEqualTo(2);
        assertThat(((Number) counts[4]).longValue()).as("first seen in the last 30 days").isEqualTo(5);
        // All five differ on purpose: with any pair equal, swapping those two expressions in the
        // SELECT list passes, and CeFleetReader reads the row BY POSITION.
        assertThat(List.of(counts[0], counts[1], counts[2], counts[3], counts[4]))
                .extracting(value -> ((Number) value).longValue())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every statement on the ledger carries a timeout")
    void everyStatementIsTimeboxed() {
        // Lives here rather than in the Postgres suite because it executes no SQL: gating a pure
        // reflection guard on a database only CI provides means it never runs for anyone editing
        // the repository. EVERY declared method, found by reflection rather than a hard-coded
        // pair. The writes run
        // inline on the request thread of the endpoint the whole fleet polls for security releases;
        // the reads now run every five minutes per pod on the shared scheduler thread and are
        // sequential scans that grow with the ledger. Every other guard here defends against
        // exceptions, and a statement that is merely slow is not one. Enumerating the methods was
        // how fleetCounts, versionBreakdown and count() came to be promised a timeout in the docs
        // and pinned by nothing.
        assertThat(CeInstallPingRepository.class.getDeclaredMethods()).isNotEmpty();
        for (java.lang.reflect.Method method : CeInstallPingRepository.class.getDeclaredMethods()) {
            org.springframework.data.jpa.repository.QueryHints hints = method
                    .getAnnotation(org.springframework.data.jpa.repository.QueryHints.class);

            assertThat(hints).as("%s must be timeboxed", method.getName()).isNotNull();
            assertThat(hints.value()).as("%s", method.getName()).hasSize(1);
            assertThat(hints.value()[0].name()).isEqualTo("jakarta.persistence.query.timeout");
            // The purge is the one allowed to be slower: it is batched, runs alone under a
            // ShedLock, and its 30s bound is argued for on the method itself.
            int limit = "purgeUnseenSince".equals(method.getName()) ? 30_000 : 2_000;
            // Bounded from BELOW as well. An upper bound alone accepts "2", which times out every
            // statement in production while every test here still passes: no gauge would ever
            // register and /stats would answer 503 for ever.
            assertThat(Integer.parseInt(hints.value()[0].value()))
                    .as("%s timeout", method.getName())
                    .isBetween(500, limit);
        }
    }

    @Test
    @DisplayName("version breakdown is ordered by install count and labels a missing version")
    void versionBreakdownIsOrderedAndLabelled() {
        List<Object[]> rows = repository.versionBreakdown(Instant.now().minus(30, ChronoUnit.DAYS), 15);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)[0]).isEqualTo("0.2.13");
        assertThat(((Number) rows.get(0)[1]).longValue()).isEqualTo(4);
        // A null version must arrive as a label: the stats controller builds a Map.of from these
        // rows and Map.of throws on a null value, so a raw null here would 500 the fleet read.
        assertThat(rows).anySatisfy(row -> assertThat(row[0]).isEqualTo("unknown"));
    }

    @Test
    @DisplayName("the breakdown honours its limit")
    void versionBreakdownHonoursLimit() {
        List<Object[]> rows = repository.versionBreakdown(Instant.now().minus(30, ChronoUnit.DAYS), 1);

        // The limit is a bound parameter, which not every database accepts in that position; this
        // is the assertion that would fail if it silently stopped being applied.
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("installs outside the window are excluded from the breakdown entirely")
    void breakdownExcludesStaleInstalls() {
        List<Object[]> rows = repository.versionBreakdown(Instant.now().minus(30, ChronoUnit.DAYS), 15);

        assertThat(rows).noneSatisfy(row -> assertThat(row[0]).isEqualTo("0.1.22"));
    }
}
