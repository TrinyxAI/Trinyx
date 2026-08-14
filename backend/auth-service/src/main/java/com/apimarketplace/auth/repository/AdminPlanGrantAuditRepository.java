package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.AdminPlanGrantAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminPlanGrantAuditRepository extends JpaRepository<AdminPlanGrantAudit, Long> {

    /** Everything that ever changed this account's tier, newest first. */
    List<AdminPlanGrantAudit> findByTargetUserIdOrderByCreatedAtDesc(Long targetUserId);

    /** Everything this admin granted, newest first. */
    List<AdminPlanGrantAudit> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId);
}
