package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.entitlement.LimitExceededError;
import com.apimarketplace.auth.client.entitlement.LimitExceededException;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.service.CloudLinkService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The CE-remote "create my editable copy" endpoint.
 *
 * <p>Each refusal has to reach the caller in the shape the UI already understands: a
 * VIEWER may not clone a workflow into the workspace, an unlinked install cannot fetch the
 * cloud snapshot, and a workspace at its workflow quota must get the platform-wide 409
 * (the install-time copy used to swallow that denial and silently create nothing).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteMarketplaceController - editable copy of a cloud-acquired application")
@SuppressWarnings("unchecked")
class RemoteMarketplaceControllerEditableWorkflowTest {

    @Mock private RemoteMarketplaceService remoteMarketplaceService;

    private RemoteMarketplaceController controller;

    private static final UUID PUB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TENANT = "buyer-7";
    private static final String ORG = "org-buyer";

    @BeforeEach
    void setUp() {
        controller = new RemoteMarketplaceController(remoteMarketplaceService);
    }

    @Test
    @DisplayName("Returns the copy the service resolved, including whether it was just created")
    void returnsTheCopy() {
        when(remoteMarketplaceService.createEditableWorkflowTwin(PUB, TENANT, ORG))
                .thenReturn(Map.of("workflowId", "wf-copy", "created", true));

        ResponseEntity<?> response = controller.createEditableWorkflow(PUB, TENANT, ORG, "MEMBER");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("workflowId", "wf-copy");
    }

    @Test
    @DisplayName("VIEWER is refused with 403 and the service is never reached (cloning a workflow is a write)")
    void viewerRefused() {
        ResponseEntity<?> response = controller.createEditableWorkflow(PUB, TENANT, ORG, "viewer");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(remoteMarketplaceService);
    }

    @Test
    @DisplayName("An unlinked install reports CLOUD_ACCOUNT_NOT_LINKED (the snapshot lives on the cloud)")
    void unlinkedInstallReportsTheLinkRequirement() {
        when(remoteMarketplaceService.createEditableWorkflowTwin(PUB, TENANT, ORG))
                .thenThrow(new CloudLinkService.CloudAccountNotLinkedException("not linked"));

        ResponseEntity<?> response = controller.createEditableWorkflow(PUB, TENANT, ORG, "MEMBER");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("code", "CLOUD_ACCOUNT_NOT_LINKED");
    }

    @Test
    @DisplayName("A quota denial is RETHROWN so the platform handler emits its 409 - never buried in a 500")
    void quotaDenialIsRethrown() {
        when(remoteMarketplaceService.createEditableWorkflowTwin(PUB, TENANT, ORG))
                .thenThrow(new LimitExceededException(
                        LimitExceededError.of(ResourceType.WORKFLOW, "FREE", 5, 5, "upgrade")));

        assertThatThrownBy(() -> controller.createEditableWorkflow(PUB, TENANT, ORG, "MEMBER"))
                .isInstanceOf(LimitExceededException.class);
    }

    @Test
    @DisplayName("An application that is not installed is a 400, not a server error")
    void notInstalledIsABadRequest() {
        when(remoteMarketplaceService.createEditableWorkflowTwin(PUB, TENANT, ORG))
                .thenThrow(new IllegalArgumentException("Application is not installed in this workspace"));

        ResponseEntity<?> response = controller.createEditableWorkflow(PUB, TENANT, ORG, "MEMBER");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("error", "Application is not installed in this workspace");
    }

    @Test
    @DisplayName("Any other failure is a 500 with a generic message (no internals leaked)")
    void unexpectedFailureIsAServerError() {
        when(remoteMarketplaceService.createEditableWorkflowTwin(PUB, TENANT, ORG))
                .thenThrow(new RuntimeException("orchestrator exploded"));

        ResponseEntity<?> response = controller.createEditableWorkflow(PUB, TENANT, ORG, "MEMBER");

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("error", "Failed to create the editable copy");
        verify(remoteMarketplaceService, never()).acquirePublication(any(), any(), any());
    }
}
