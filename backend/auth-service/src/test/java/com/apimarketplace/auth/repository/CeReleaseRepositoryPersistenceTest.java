package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeRelease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence tests for {@link CeRelease}, against an actual EntityManager.
 *
 * <p>These exist because ten rounds of mock-based tests could not see the defect that mattered:
 * {@code CeRelease} carries an assigned, always-non-null {@code @Id}, so Spring Data's
 * {@code isNew()} is false and {@code save()} takes the {@code merge()} branch rather than
 * {@code persist()}. Merge issues its own snapshot read and emits an UPDATE, so a row that appeared
 * after a lock-free {@code SELECT ... FOR UPDATE} was silently overwritten - with no primary-key
 * violation to stop it, contrary to what the javadoc claimed for two rounds.
 *
 * <p>A mock repository cannot express any of that: it returns whatever it is told to.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = CeReleaseRepositoryPersistenceTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        // The entity lives in the `auth` schema, which an empty H2 does not have.
        "spring.datasource.url=jdbc:h2:mem:ce_release;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("CeRelease persistence - what save() actually does")
class CeReleaseRepositoryPersistenceTest {

    /**
     * JPA only. auth-service's application class component-scans widely, so the default slice
     * drags in beans needing metrics, edition providers and more - none of which say anything
     * about how this row is written.
     */
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CeRelease.class)
    @EnableJpaRepositories(basePackageClasses = CeReleaseRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private CeReleaseRepository repository;

    @Test
    @DisplayName("saving a fresh entity with the singleton id UPDATES an existing row rather than failing")
    void saveOnAnExistingIdUpdatesRatherThanConflicting() {
        // The behaviour the design must account for. If this ever started throwing instead, the
        // "absent row races at the primary key" reasoning would become true - but it does not
        // today, which is why the row is seeded to exist (V419) instead.
        repository.saveAndFlush(new CeRelease("0.3.0", "https://example.test/3", false, null));

        repository.saveAndFlush(new CeRelease("0.1.22", "https://example.test/pin", false, null));

        assertThat(repository.count())
                .as("the singleton id must never produce a second row")
                .isEqualTo(1);
        assertThat(repository.findById(CeRelease.SINGLETON_ID))
                .get()
                .extracting(CeRelease::getLatestVersion)
                .as("save() merges onto the existing row; it does not conflict")
                .isEqualTo("0.1.22");
    }

    @Test
    @DisplayName("the locked query returns the row and its version, which is what the write guard reads")
    void lockedQueryReturnsTheRow() {
        repository.saveAndFlush(new CeRelease("0.2.7", null, false, null));

        Optional<CeRelease> locked = repository.findByIdForUpdate(CeRelease.SINGLETON_ID);

        assertThat(locked).get().extracting(CeRelease::getLatestVersion).isEqualTo("0.2.7");
    }

    @Test
    @DisplayName("the locked query returns empty when the row is absent, which is why V419 seeds it")
    void lockedQueryOnAnAbsentRowReturnsEmpty() {
        // Stated explicitly because the whole atomicity argument rests on it: there is nothing
        // here to lock. FOR UPDATE on no rows is a no-op, so two writers would both proceed.
        assertThat(repository.findByIdForUpdate(CeRelease.SINGLETON_ID)).isEmpty();
    }

    @Test
    @DisplayName("a row can hold a NULL version, which both the store and the feed read as 'nothing announced'")
    void aNullVersionRowIsValid() {
        // V419 seeds exactly this shape, so it has to round-trip: the column is nullable and the
        // entity maps it without complaint.
        repository.saveAndFlush(new CeRelease(null, null, false, null));

        assertThat(repository.findById(CeRelease.SINGLETON_ID))
                .get()
                .extracting(CeRelease::getLatestVersion)
                .isNull();
    }

    @Test
    @DisplayName("applying a new release in place keeps the singleton id and every field")
    void applyUpdatesEveryFieldInPlace() {
        repository.saveAndFlush(new CeRelease(null, null, false, null));

        CeRelease row = repository.findById(CeRelease.SINGLETON_ID).orElseThrow();
        row.apply("0.4.1", "https://example.test/4", true, "2026-07-31T00:00:00Z");
        repository.saveAndFlush(row);

        CeRelease reloaded = repository.findById(CeRelease.SINGLETON_ID).orElseThrow();
        assertThat(reloaded.getLatestVersion()).isEqualTo("0.4.1");
        assertThat(reloaded.getReleaseUrl()).isEqualTo("https://example.test/4");
        assertThat(reloaded.isSecurityFix()).isTrue();
        assertThat(reloaded.getPublishedAt()).isEqualTo("2026-07-31T00:00:00Z");
        assertThat(reloaded.getId()).isEqualTo(CeRelease.SINGLETON_ID);
        assertThat(repository.count()).isEqualTo(1);
    }
}
