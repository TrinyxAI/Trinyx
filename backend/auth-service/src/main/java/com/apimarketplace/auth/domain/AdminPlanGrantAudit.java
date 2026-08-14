package com.apimarketplace.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One row per attempt to grant a comp plan, successful or not.
 *
 * <p>Admin CREDIT grants have always been traceable (the actor is written into
 * {@code credit_ledger.description}); admin PLAN grants were not, leaving no way to answer
 * "who moved this account to TEAM?" once the 30-day log retention passed. This is that record.
 */
@Entity
@Table(name = "admin_plan_grant_audit")
public class AdminPlanGrantAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The admin who acted. Null only when the gateway forwarded no identity. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "target_user_id")
    private Long targetUserId;

    /** Present when the target was named by email rather than id. */
    @Column(name = "target_email", length = 255)
    private String targetEmail;

    @Column(name = "requested_plan", nullable = false, length = 32)
    private String requestedPlan;

    @Column(name = "previous_plan", length = 32)
    private String previousPlan;

    @Column(name = "succeeded", nullable = false)
    private boolean succeeded;

    /** Stable reason token on failure (has_paid_subscription, user_not_found, ...). */
    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }

    public String getTargetEmail() { return targetEmail; }
    public void setTargetEmail(String targetEmail) { this.targetEmail = targetEmail; }

    public String getRequestedPlan() { return requestedPlan; }
    public void setRequestedPlan(String requestedPlan) { this.requestedPlan = requestedPlan; }

    public String getPreviousPlan() { return previousPlan; }
    public void setPreviousPlan(String previousPlan) { this.previousPlan = previousPlan; }

    public boolean isSucceeded() { return succeeded; }
    public void setSucceeded(boolean succeeded) { this.succeeded = succeeded; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
