package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.BillingCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, Long> {

    /**
     * Trouve un client de facturation par son ID utilisateur
     */
    Optional<BillingCustomer> findByUserId(Long userId);

    /**
     * Trouve un client de facturation par son ID Stripe
     */
    Optional<BillingCustomer> findByProviderCustomerId(String providerCustomerId);

    /**
     * Verifie si un utilisateur a deja un client de facturation
     */
    boolean existsByUserId(Long userId);

    /**
     * PESSIMISTIC_WRITE on a user's billing-customer row, used to serialise
     * subscription provisioning for that user.
     *
     * <p>There is exactly one billing_customer per user (unique index on {@code user_id}),
     * so locking it is a per-user mutex that works ACROSS pods - which matters, because
     * the duplicate-subscription race this exists to close is driven by concurrent
     * {@code resolveUser} calls that can land on different auth replicas.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bc FROM BillingCustomer bc WHERE bc.user.id = :userId")
    Optional<BillingCustomer> findByUserIdForUpdate(@Param("userId") Long userId);
}
