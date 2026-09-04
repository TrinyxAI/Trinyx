package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases only reservations for which no provider dispatch was recorded.
 * Ambiguous outcomes are escalated for reconciliation and are never auto-released.
 */
@Component
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "paid-monolith-authority")
public class CloudCreditReservationExpiryJob {
    private static final Logger log =
            LoggerFactory.getLogger(CloudCreditReservationExpiryJob.class);
    private final CloudCreditAuthorityService authority;

    public CloudCreditReservationExpiryJob(CloudCreditAuthorityService authority) {
        this.authority = authority;
    }

    @Scheduled(fixedDelayString = "${billing.external.reservation-sweep-ms:60000}")
    public void expire() {
        authority.expireDueReservations();
        int staleUnknown = authority.escalateStaleUnknownOutcomes();
        if (staleUnknown > 0) {
            log.error("{} ambiguous provider outcomes exceeded the reconciliation SLA; "
                    + "holds were retained and require explicit COMMIT or RELEASE",
                    staleUnknown);
        }
    }
}
