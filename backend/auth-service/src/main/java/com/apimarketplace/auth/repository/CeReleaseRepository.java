package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeRelease;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for the single {@link CeRelease} row backing the public CE release feed.
 *
 * <p>Always addressed by {@link CeRelease#SINGLETON_ID}: the table's CHECK constraint makes any
 * other id impossible, so there is no "find the newest" query to get wrong.
 */
@Repository
public interface CeReleaseRepository extends JpaRepository<CeRelease, Short> {

    /**
     * The announced row, locked for update.
     *
     * <p>Used by the write path so its "is this newer?" check and the write itself are one atomic
     * step. Under READ COMMITTED a plain read lets two concurrent announces both see the
     * pre-state, both pass the check and both write, so the older one can land last: the exact
     * fleet-walked-backwards outcome the check exists to prevent. Cheap here - the row is written
     * a handful of times a year.
     *
     * <p>This only works because the row ALWAYS EXISTS (seeded with a null version by V419):
     * {@code SELECT ... FOR UPDATE} takes no lock on an absent row, so before that seed the
     * guarantee above was false exactly when it mattered most.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CeRelease r where r.id = :id")
    Optional<CeRelease> findByIdForUpdate(Short id);
}
