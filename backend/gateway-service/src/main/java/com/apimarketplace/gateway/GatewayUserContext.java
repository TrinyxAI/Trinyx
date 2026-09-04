package com.apimarketplace.gateway;

import java.util.List;
import java.util.Set;

record GatewayUserContext(
        Long userId,
        String providerId,
        Set<String> roles,
        String defaultOrganizationId,
        String defaultOrganizationRole,
        List<Membership> memberships,
        String principalId,
        String billingSubjectId,
        String installId
) {
    record Membership(String orgId, String role) {}

    String roleFor(String organizationId) {
        if (organizationId == null) return defaultOrganizationRole;
        if (memberships == null) return null;
        return memberships.stream()
                .filter(membership -> organizationId.equals(membership.orgId()))
                .map(Membership::role)
                .findFirst()
                .orElse(null);
    }
}
