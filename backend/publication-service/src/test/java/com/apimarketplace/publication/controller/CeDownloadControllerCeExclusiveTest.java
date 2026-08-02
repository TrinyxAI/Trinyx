package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * THE invariant that makes a publication "CE exclusive" rather than simply
 * unavailable: the CE download endpoint runs on cloud but SERVES self-hosted
 * installs, so it must NOT apply the edition gate.
 *
 * <p>Without this test, a future author "completing" the gate by adding
 * {@code ceExclusiveGuard.check()} here would cut self-hosted installs off from
 * exactly the apps built for them, and every other test would stay green.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CeDownloadController - CE-exclusive publications stay downloadable")
class CeDownloadControllerCeExclusiveTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AuthClient authClient;

    private CeDownloadController controller;

    @BeforeEach
    void setUp() {
        controller = new CeDownloadController(
                publicationRepository, receiptRepository, creditClient, authClient);
    }

    private WorkflowPublicationEntity ceExclusiveFreePublication(UUID id) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity(
                UUID.randomUUID(), "RAG App", Map.of("agents", List.of()), "publisher-1");
        publication.setId(id);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setCreditsPerUse(0);
        publication.setCeExclusive(true);
        publication.setCeExclusiveFeatures(List.of("CLI_AGENT", "VECTOR_SEARCH"));
        return publication;
    }

    @Test
    @DisplayName("the snapshot download SUCCEEDS for a CE-exclusive publication (never 403)")
    void snapshotDownloadIsNotGated() {
        UUID pubId = UUID.randomUUID();
        when(publicationRepository.findById(pubId))
                .thenReturn(Optional.of(ceExclusiveFreePublication(pubId)));

        ResponseEntity<Map<String, Object>> response = controller.getSnapshot(pubId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("planSnapshot");
    }

    @Test
    @DisplayName("acquire-with-auth is NOT gated either - it is the paid twin of the same download")
    void acquireWithAuthIsNotGated() {
        UUID pubId = UUID.randomUUID();
        WorkflowPublicationEntity publication = ceExclusiveFreePublication(pubId);
        when(publicationRepository.findById(pubId)).thenReturn(Optional.of(publication));
        when(authClient.getDefaultOrganizationIdForUser("7")).thenReturn("cloud-org-1");
        when(receiptRepository.existsByOrganizationIdAndPublicationId("cloud-org-1", pubId))
                .thenReturn(true); // already owned -> free re-download, no credit call

        ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(pubId, 7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsKey("planSnapshot")
                .containsEntry("alreadyOwned", true);
    }

    @Test
    @DisplayName("the response reports the label so the CE side can show the same badge")
    void snapshotCarriesTheLabel() {
        UUID pubId = UUID.randomUUID();
        when(publicationRepository.findById(pubId))
                .thenReturn(Optional.of(ceExclusiveFreePublication(pubId)));

        Map<String, Object> body = controller.getSnapshot(pubId).getBody();

        assertThat(body)
                .containsEntry("ceExclusive", true)
                .containsEntry("ceExclusiveFeatures", List.of("CLI_AGENT", "VECTOR_SEARCH"));
    }
}
