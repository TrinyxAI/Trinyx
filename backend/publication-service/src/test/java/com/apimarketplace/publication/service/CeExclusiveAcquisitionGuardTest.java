package com.apimarketplace.publication.service;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The edition gate that makes a publication "CE exclusive" rather than simply
 * unavailable: managed cloud refuses the install, every self-hosted edition
 * installs it normally.
 */
@DisplayName("CeExclusiveAcquisitionGuard")
class CeExclusiveAcquisitionGuardTest {

    private static AppEditionProvider edition(String value) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", value);
        return new AppEditionProvider(env);
    }

    private static WorkflowPublicationEntity publication(boolean ceExclusive) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setCeExclusive(ceExclusive);
        publication.setCeExclusiveFeatures(ceExclusive ? List.of("CLI_AGENT") : List.of());
        return publication;
    }

    @Test
    @DisplayName("managed cloud refuses a CE-exclusive publication and names the features")
    void cloudRefusesCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"));

        assertThatThrownBy(() -> guard.check(publication(true)))
                .isInstanceOf(CeExclusivePublicationException.class)
                .hasMessage(CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(CeExclusivePublicationException.class))
                .extracting(CeExclusivePublicationException::getFeatures)
                .isEqualTo(List.of("CLI_AGENT"));
    }

    @Test
    @DisplayName("dedicated cloud is managed cloud too, so it refuses as well")
    void dedicatedCloudRefusesCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("dedicated-cloud"));

        assertThatThrownBy(() -> guard.check(publication(true)))
                .isInstanceOf(CeExclusivePublicationException.class);
    }

    @Test
    @DisplayName("Community Edition installs a CE-exclusive publication - that is the point")
    void ceInstallsCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("ce"));

        assertThatCode(() -> guard.check(publication(true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("self-hosted enterprise installs it too (self-hosted, not managed cloud)")
    void selfHostedEnterpriseInstallsCeExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("self-hosted-enterprise"));

        assertThatCode(() -> guard.check(publication(true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a normal publication is never blocked, including on managed cloud")
    void cloudAllowsNonExclusive() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"));

        assertThatCode(() -> guard.check(publication(false))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null publication is a no-op rather than an NPE")
    void nullPublicationIsNoOp() {
        CeExclusiveAcquisitionGuard guard = new CeExclusiveAcquisitionGuard(edition("cloud"));

        assertThatCode(() -> guard.check(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the refusal message names the constraint and the resolution, with no internals")
    void messageIsUserFacing() {
        assertThat(CeExclusiveAcquisitionGuard.BLOCKED_MESSAGE)
                .contains("self-hosted")
                .doesNotContain("/api/")
                .doesNotContain("null");
    }
}
