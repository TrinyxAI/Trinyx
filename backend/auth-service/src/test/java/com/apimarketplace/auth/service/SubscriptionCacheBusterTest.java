package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Regression tests for post-transaction cache invalidation. */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionCacheBuster.fanOutForOwner")
class SubscriptionCacheBusterTest {

    @Mock private GatewayCacheClient gatewayCacheClient;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationMemberRepository organizationMemberRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private SubscriptionCacheBuster buster;

    @Test
    @DisplayName("uses scalar projections for owner and members without traversing lazy entities")
    void fullFanOutUsesScalarProjections() {
        when(userRepository.findProviderIdByUserId(1L)).thenReturn(Optional.of("kc-owner"));
        when(organizationMemberRepository.findProviderIdsForOrganizationsOwnedBy(1L))
                .thenReturn(List.of("kc-owner", "kc-mem1", "kc-mem2"));

        buster.fanOutForOwner(1L, "subscription.upsert");

        verify(gatewayCacheClient, org.mockito.Mockito.atLeastOnce()).invalidateUserCache("kc-owner");
        verify(gatewayCacheClient).invalidateUserCache("kc-mem1");
        verify(gatewayCacheClient).invalidateUserCache("kc-mem2");
        verify(userRepository, never()).findById(any());
        verifyNoInteractions(organizationRepository);
    }

    @Test
    @DisplayName("blank and null provider ids are skipped defensively")
    void invalidProviderIdsAreSkipped() {
        when(userRepository.findProviderIdByUserId(1L)).thenReturn(Optional.of(""));
        when(organizationMemberRepository.findProviderIdsForOrganizationsOwnedBy(1L))
                .thenReturn(Arrays.asList(null, "", "kc-live"));

        buster.fanOutForOwner(1L, "subscription.deleted");

        verify(gatewayCacheClient).invalidateUserCache("kc-live");
        verify(gatewayCacheClient, never()).invalidateUserCache(null);
        verify(gatewayCacheClient, never()).invalidateUserCache("");
    }

    @Test
    @DisplayName("no-op when GatewayCacheClient is null")
    void noOpWhenGatewayClientUnwired() {
        ReflectionTestUtils.setField(buster, "gatewayCacheClient", null);

        buster.fanOutForOwner(1L, "test");

        verifyNoInteractions(userRepository, organizationMemberRepository, organizationRepository);
    }

    @Test
    @DisplayName("no-op when UserRepository is null")
    void noOpWhenUserRepositoryUnwired() {
        ReflectionTestUtils.setField(buster, "userRepository", null);

        buster.fanOutForOwner(1L, "test");

        verifyNoInteractions(organizationMemberRepository, organizationRepository);
        verify(gatewayCacheClient, never()).invalidateUserCache(any());
    }

    @Test
    @DisplayName("projection failure is best-effort and never fails the webhook")
    void swallowsProjectionFailure() {
        when(userRepository.findProviderIdByUserId(1L))
                .thenThrow(new RuntimeException("DB hiccup"));

        buster.fanOutForOwner(1L, "test");

        verify(gatewayCacheClient, never()).invalidateUserCache(any());
    }
}
