package com.apimarketplace.auth.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases abandoned holds; late provider settlements remain accountable for 24 hours. */
@Component
@ConditionalOnProperty(name = "billing.authority.mode",
        havingValue = "paid-monolith-authority")
public class CloudCreditReservationExpiryJob {
    private final CloudCreditAuthorityService authority;

    public CloudCreditReservationExpiryJob(CloudCreditAuthorityService authority) {
        this.authority = authority;
    }

    @Scheduled(fixedDelayString = "${billing.external.reservation-sweep-ms:60000}")
    public void expire() {
        authority.expireDueReservations();
    }
}
