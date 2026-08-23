package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "native-cloud", matchIfMissing = true)
public class EntitlementRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(EntitlementRefreshScheduler.class);

    private final JdbcTemplate jdbc;
    private final ExternalBillingAuthorityService authority;

    public EntitlementRefreshScheduler(JdbcTemplate jdbc,
                                       ExternalBillingAuthorityService authority) {
        this.jdbc = jdbc;
        this.authority = authority;
    }

    @Scheduled(initialDelayString = "${trinyx.entitlement.refresh-initial-delay-ms:60000}",
            fixedDelayString = "${trinyx.entitlement.refresh-delay-ms:300000}")
    public void refreshActiveBindings() {
        var scopes = jdbc.query("""
                SELECT actor.id, binding.install_id, binding.organization_id
                FROM auth.identity_binding_authority_state binding
                JOIN auth.users actor ON actor.principal_id=binding.principal_id
                WHERE binding.status='ACTIVE'
                ORDER BY binding.updated_at
                """, (rs, row) -> new Scope(
                rs.getLong(1), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)));
        for (Scope scope : scopes) {
            try {
                authority.refresh(scope.actorUserId(), scope.installId(), scope.organizationId());
            } catch (Exception failure) {
                log.warn("Entitlement refresh failed for install {} / org {}: {}",
                        scope.installId(), scope.organizationId(), failure.getMessage());
            }
        }
    }

    private record Scope(long actorUserId, UUID installId, UUID organizationId) {}
}
