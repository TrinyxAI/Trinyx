package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeInstall;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence tests for the install identity.
 *
 * <p>{@link CeInstallRepository} extends the bare {@code Repository} marker with a single
 * hand-declared {@code findById}, which is a shape that fails at CONTEXT STARTUP rather than at
 * compile time if Spring Data cannot resolve it. Every other test of this feature hands the
 * repository to Mockito, and the entity itself is mocked in {@code CeInstallIdProviderTest}, so
 * without this class nothing ties {@code CeInstall.getInstallId()} to the {@code install_id} column
 * or {@code SINGLETON_ID} to the row the migration seeds. It was previously instantiated only as a
 * side effect of the ping repository's package scan, which is not coverage.
 */
@DataJpaTest
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(
        replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes = CeInstallRepositoryPersistenceTest.JpaOnly.class)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ce_install;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE SCHEMA IF NOT EXISTS auth",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@DisplayName("CeInstall persistence - the identity read resolves against a real EntityManager")
class CeInstallRepositoryPersistenceTest {

    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CeInstall.class)
    @EnableJpaRepositories(basePackageClasses = CeInstallRepository.class)
    static class JpaOnly {
    }

    @Autowired
    private CeInstallRepository repository;

    @Autowired
    private EntityManager entityManager;

    /** Writes the row the migration seeds; the repository deliberately exposes no save. */
    private UUID seedIdentity() {
        UUID id = UUID.randomUUID();
        entityManager.createNativeQuery(
                        "INSERT INTO auth.ce_install (id, install_id) VALUES (?, ?)")
                .setParameter(1, CeInstall.SINGLETON_ID)
                .setParameter(2, id)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return id;
    }

    @Test
    @DisplayName("reads the seeded identity by its singleton id")
    void readsTheSeededIdentity() {
        UUID seeded = seedIdentity();

        // SINGLETON_ID is the constant the provider passes and the migration writes; if the two
        // ever disagree the provider silently finds nothing and every install goes uncounted, with
        // one DEBUG line to show for it.
        assertThat(repository.findById(CeInstall.SINGLETON_ID))
                .isPresent()
                .get()
                .extracting(CeInstall::getInstallId)
                .isEqualTo(seeded);
    }

    @Test
    @DisplayName("an absent identity is empty rather than an error")
    void absentIdentityIsEmpty() {
        // What a CE install looks like between booting and its migration finishing. The provider
        // treats it as "not readable yet" and retries on the next poll, so this must not throw.
        assertThat(repository.findById(CeInstall.SINGLETON_ID)).isEmpty();
    }

    @Test
    @DisplayName("the repository offers reads only, by type")
    void repositoryExposesNoWrites() {
        // The claim "there is no write path and no per-install tracker here" is made in the
        // repository javadoc, the migration header and the public README. Inheriting JpaRepository
        // would hand out save, delete and findAll for free and quietly make all three false, which
        // is exactly how this kind of guarantee decays.
        assertThat(CeInstallRepository.class.getDeclaredMethods())
                .extracting(java.lang.reflect.Method::getName)
                .containsExactly("findById");
        assertThat(org.springframework.data.repository.CrudRepository.class
                .isAssignableFrom(CeInstallRepository.class))
                .as("extending CrudRepository or JpaRepository would restore the write methods")
                .isFalse();
    }
}
