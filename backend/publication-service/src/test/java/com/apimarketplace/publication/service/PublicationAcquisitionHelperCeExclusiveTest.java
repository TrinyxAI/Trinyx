package com.apimarketplace.publication.service;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@code validateAcquirable} is the shared chokepoint of the workflow and agent
 * acquire paths, so the edition gate has to fire there - including for a
 * receipt holder, who could otherwise reinstall onto cloud a publication that
 * cannot run there.
 */
@DisplayName("PublicationAcquisitionHelper - CE-exclusive gate")
class PublicationAcquisitionHelperCeExclusiveTest {

    private PublicationAcquisitionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PublicationAcquisitionHelper(
                mock(WorkflowPublicationRepository.class),
                mock(PublicationReceiptRepository.class),
                null);
    }

    private static AppEditionProvider edition(String value) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", value);
        return new AppEditionProvider(env);
    }

    private static WorkflowPublicationEntity ceExclusivePublication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setCeExclusive(true);
        publication.setCeExclusiveFeatures(List.of("VECTOR_SEARCH"));
        return publication;
    }

    @Test
    @DisplayName("managed cloud refuses a CE-exclusive publication on a first acquisition")
    void cloudRefusesFirstAcquire() {
        helper.setCeExclusiveGuard(new CeExclusiveAcquisitionGuard(edition("cloud")));

        assertThatThrownBy(() -> helper.validateAcquirable(ceExclusivePublication(), false))
                .isInstanceOf(CeExclusivePublicationException.class);
    }

    @Test
    @DisplayName("managed cloud refuses it for a RECEIPT HOLDER too - a reinstall cannot run either")
    void cloudRefusesReinstall() {
        helper.setCeExclusiveGuard(new CeExclusiveAcquisitionGuard(edition("cloud")));

        assertThatThrownBy(() -> helper.validateAcquirable(ceExclusivePublication(), true))
                .isInstanceOf(CeExclusivePublicationException.class);
    }

    @Test
    @DisplayName("self-hosted lets it through and keeps the normal visibility/status rules")
    void selfHostedAllows() {
        helper.setCeExclusiveGuard(new CeExclusiveAcquisitionGuard(edition("ce")));

        assertThatCode(() -> helper.validateAcquirable(ceExclusivePublication(), false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("without the guard wired the helper behaves exactly as before")
    void noGuardIsNoOp() {
        assertThatCode(() -> helper.validateAcquirable(ceExclusivePublication(), false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the edition gate runs BEFORE the private/inactive checks, so cloud gets the real reason")
    void editionGateRunsFirst() {
        helper.setCeExclusiveGuard(new CeExclusiveAcquisitionGuard(edition("cloud")));
        WorkflowPublicationEntity publication = ceExclusivePublication();
        publication.setVisibility(PublicationVisibility.PRIVATE);

        // PRIVATE would normally raise IllegalArgumentException("Publication is private").
        assertThatThrownBy(() -> helper.validateAcquirable(publication, false))
                .isInstanceOf(CeExclusivePublicationException.class);
    }
}
