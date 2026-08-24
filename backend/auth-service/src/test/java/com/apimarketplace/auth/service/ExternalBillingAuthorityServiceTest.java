package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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

    @Test
    void organizationMemberUsesOwnerInstallAndKeepsDistinctPrincipalAndPayer() {
        long memberId = 22L;
        long ownerId = 11L;
        UUID organizationId = UUID.randomUUID();
        UUID installId = UUID.randomUUID();
        UUID memberPrincipal = UUID.randomUUID();
        UUID ownerBillingSubject = UUID.randomUUID();

        User actor = mock(User.class);
        when(actor.getPrincipalId()).thenReturn(memberPrincipal);
        User payer = mock(User.class);
        when(payer.getBillingSubjectId()).thenReturn(ownerBillingSubject);
        UserRepository users = mock(UserRepository.class);
        when(users.findById(memberId)).thenReturn(Optional.of(actor));
        when(users.findById(ownerId)).thenReturn(Optional.of(payer));
        ScopeJdbc jdbc = new ScopeJdbc(ownerId);

        ExternalBillingAuthorityService service = new ExternalBillingAuthorityService(
                jdbc, users, mock(SubscriptionRepository.class), mock(PlanRepository.class),
                mock(PlanResolutionService.class), mock(TrinyxAssertionService.class),
                new ObjectMapper(), 72);

        ExternalBillingAuthorityService.AuthorityScope scope =
                service.validateScope(memberId, installId, organizationId);

        assertThat(scope.actor()).isSameAs(actor);
        assertThat(scope.payer()).isSameAs(payer);
        assertThat(scope.organizationRole()).isEqualTo("MEMBER");
        assertThat(memberPrincipal).isNotEqualTo(ownerBillingSubject);
        assertThat(jdbc.installChecks).singleElement().satisfies(check -> {
            assertThat(check.sql()).contains("link.tenant_id=?");
            assertThat(check.sql()).contains("link.install_id=?");
            assertThat(check.sql()).contains("link.organization_id=?");
            assertThat(check.args()).containsExactly(ownerId, installId, organizationId.toString());
        });
        verify(users).findById(memberId);
        verify(users).findById(ownerId);
    }

    private static Subscription subscription(String status) {
        Subscription value = new Subscription();
        value.setStatus(status);
        return value;
    }

    private static final class ScopeJdbc extends JdbcTemplate {
        private final long ownerId;
        private final List<InstallCheck> installChecks = new ArrayList<>();

        private ScopeJdbc(long ownerId) {
            this.ownerId = ownerId;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            if (!sql.contains("auth.organization_member")) {
                return List.of();
            }
            try {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString(1)).thenReturn("member");
                return List.of(mapper.mapRow(rs, 0));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("SELECT owner_id")) {
                return requiredType.cast(ownerId);
            }
            if (sql.contains("publication.ce_cloud_links")) {
                installChecks.add(new InstallCheck(sql, List.of(args)));
                return requiredType.cast(1);
            }
            throw new AssertionError("Unexpected SQL: " + sql);
        }
    }

    private record InstallCheck(String sql, List<Object> args) {}
}
