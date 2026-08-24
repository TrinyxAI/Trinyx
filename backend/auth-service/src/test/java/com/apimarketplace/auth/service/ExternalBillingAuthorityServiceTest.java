package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Subscription;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalBillingAuthorityServiceTest {

    @Test
    void activeSubscriptionWinsOverNewerCanceledHistory() {
        Subscription canceled = subscription("canceled");
        Subscription active = subscription("active");

        assertThat(ExternalBillingAuthorityService.selectAuthoritativeSubscription(
                List.of(canceled, active))).isSameAs(active);
    }

    @Test
    void trialingWinsOverPastDueAndCanceled() {
        Subscription canceled = subscription("canceled");
        Subscription pastDue = subscription("past_due");
        Subscription trialing = subscription("trialing");

        assertThat(ExternalBillingAuthorityService.selectAuthoritativeSubscription(
                List.of(canceled, pastDue, trialing))).isSameAs(trialing);
    }

    @Test
    void pastDueWinsWhenNoActiveOrTrialingSubscriptionExists() {
        Subscription canceled = subscription("canceled");
        Subscription pastDue = subscription("past_due");

        assertThat(ExternalBillingAuthorityService.selectAuthoritativeSubscription(
                List.of(canceled, pastDue))).isSameAs(pastDue);
    }

    @Test
    void unsupportedHistoryDoesNotBecomeAuthoritative() {
        assertThat(ExternalBillingAuthorityService.selectAuthoritativeSubscription(
                List.of(subscription("incomplete"), subscription("paused")))).isNull();
    }

    private static Subscription subscription(String status) {
        Subscription value = new Subscription();
        value.setStatus(status);
        return value;
    }
}
