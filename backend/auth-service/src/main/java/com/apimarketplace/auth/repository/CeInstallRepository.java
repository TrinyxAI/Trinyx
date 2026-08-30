package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeInstall;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * Read access to the single {@link CeInstall} row holding this install's anonymous identity.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository} so that read is
 * the ONLY operation that exists. The identity is seeded by the migration and must never change:
 * an id that moved under a live install would count it twice for the rest of its life, and nothing
 * would report an error. Inheriting {@code save} and {@code delete} would leave that guarantee as a
 * comment the type system contradicts.
 */
@org.springframework.stereotype.Repository
public interface CeInstallRepository extends Repository<CeInstall, Short> {

    Optional<CeInstall> findById(Short id);
}
