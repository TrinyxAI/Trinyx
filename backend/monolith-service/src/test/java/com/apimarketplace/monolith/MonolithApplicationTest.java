package com.apimarketplace.monolith;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MonolithApplication")
class MonolithApplicationTest {

    @Test
    @DisplayName("repairs known failed pgvector migration before migrating in CE monolith")
    void repairsKnownFailedPgvectorMigrationBeforeMigratingInCeMonolith() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo failedV75 = migration("75", MigrationState.FAILED);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { failedV75 });

        strategy.migrate(flyway);

        var inOrder = inOrder(flyway);
        inOrder.verify(flyway).repair();
        inOrder.verify(flyway).migrate();
    }

    @Test
    @DisplayName("does not repair Flyway history when the failure is unrelated to pgvector")
    void doesNotRepairUnrelatedFlywayFailures() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo failedUnrelated = migration("240", MigrationState.FAILED);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { failedUnrelated });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    @Test
    @DisplayName("does not repair Flyway history when pgvector migrations are already successful")
    void doesNotRepairSuccessfulPgvectorHistory() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo successfulV74 = migration("74", MigrationState.SUCCESS);
        MigrationInfo successfulV75 = migration("75", MigrationState.SUCCESS);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { successfulV74, successfulV75 });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    // ── CE image/repo migration parity ──────────────────────────────────────
    // V44 shipped ~1 MB of captured third-party API responses inside the published image.
    // The CE build now substitutes the byte-identical no-op the public export writes, which
    // changes that file's checksum. Flyway refuses to start on a mismatch, so an install
    // created by an earlier image would stop booting after a routine `docker compose pull`.
    // These cases pin the narrow repair that makes that upgrade silent, WITHOUT widening it
    // into an unconditional repair that would delete genuine failed-migration history.

    @Test
    @DisplayName("repairs when the neutralized V44 checksum no longer matches, so an upgrade still boots")
    void repairsNeutralizedMigrationChecksumMismatch() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo mismatchedV44 = appliedMigration("44", MigrationState.SUCCESS, 111, false);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { mismatchedV44 });

        strategy.migrate(flyway);

        var inOrder = inOrder(flyway);
        inOrder.verify(flyway).repair();
        inOrder.verify(flyway).migrate();
    }

    @Test
    @DisplayName("repairs when the neutralized V328 launch-promo checksum no longer matches")
    void repairsNeutralizedPromoSeedChecksumMismatch() {
        // V328 seeded a cloud-only uncapped 20k-credit code that shipped readable inside every
        // published image. The CE build now substitutes the scrubbed copy the export writes, so
        // an install created by an earlier image meets a changed checksum on the next pull.
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo mismatchedV328 = appliedMigration("328", MigrationState.SUCCESS, 111, false);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { mismatchedV328 });

        strategy.migrate(flyway);

        var inOrder = inOrder(flyway);
        inOrder.verify(flyway).repair();
        inOrder.verify(flyway).migrate();
    }

    @Test
    @DisplayName("repairs when the neutralized V346 promo-retirement checksum no longer matches")
    void repairsNeutralizedPromoRetirementChecksumMismatch() {
        // V346 is kept as a no-op rather than dropped, so the image and the public repo keep
        // describing the same history. Keeping it means its checksum changes too, hence the
        // same repair.
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo mismatchedV346 = appliedMigration("346", MigrationState.SUCCESS, 111, false);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { mismatchedV346 });

        strategy.migrate(flyway);

        var inOrder = inOrder(flyway);
        inOrder.verify(flyway).repair();
        inOrder.verify(flyway).migrate();
    }

    @Test
    @DisplayName("does NOT repair a checksum mismatch on a migration the CE build never neutralized")
    void doesNotRepairMismatchOnUnrelatedMigration() {
        // The scope guard. A migration edited after being applied is an operator error and
        // must keep failing the boot loudly; only the versions CE deliberately rewrites are
        // allowed to mismatch.
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo mismatchedOther = appliedMigration("240", MigrationState.SUCCESS, 111, false);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { mismatchedOther });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    @Test
    @DisplayName("does not repair a fresh install, where V44 is pending and has no applied checksum")
    void doesNotRepairPendingNeutralizedMigration() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        // isChecksumMatching() is stubbed FALSE on purpose: the null applied checksum must
        // be what stops the repair. Stubbing it true would let the test pass even with the
        // getAppliedChecksum() guard removed, i.e. it would not test its own name.
        MigrationInfo pendingV44 = appliedMigration("44", MigrationState.PENDING, null, false);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { pendingV44 });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    @Test
    @DisplayName("does not repair when the neutralized V44 checksum still matches")
    void doesNotRepairMatchingNeutralizedMigration() {
        MonolithApplication application = new MonolithApplication();
        FlywayMigrationStrategy strategy = application.repairKnownPgvectorFailureThenMigrate();
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService infoService = mock(MigrationInfoService.class);
        MigrationInfo matchingV44 = appliedMigration("44", MigrationState.SUCCESS, 222, true);
        when(flyway.info()).thenReturn(infoService);
        when(infoService.all()).thenReturn(new MigrationInfo[] { matchingV44 });

        strategy.migrate(flyway);

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    private static MigrationInfo migration(String version, MigrationState state) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(migration.getState()).thenReturn(state);
        return migration;
    }

    private static MigrationInfo appliedMigration(
            String version, MigrationState state, Integer appliedChecksum, boolean checksumMatching) {
        MigrationInfo migration = mock(MigrationInfo.class);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion(version));
        when(migration.getState()).thenReturn(state);
        when(migration.getAppliedChecksum()).thenReturn(appliedChecksum);
        when(migration.isChecksumMatching()).thenReturn(checksumMatching);
        return migration;
    }
}
