package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the editable-copy contract after 2026-08-14: acquiring an application creates the
 * run-only APPLICATION clone and NOTHING ELSE. The freely-editable, DECOUPLED WORKFLOW copy
 * is minted only when the user explicitly asks for it, and asking twice returns the copy
 * they already have.
 *
 * <p>The regression this guards is concrete: the copy re-clones the SAME snapshot, so
 * creating it on every install gave each acquirer two of every interface, table and agent
 * the application carries - stacked in their lists, billed twice against the INTERFACE /
 * DATA entitlements, and left behind on uninstall.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - the editable WORKFLOW copy is on demand, not per install")
class WorkflowPublicationServiceDuplicateAcquireTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;
    @Mock private EditableWorkflowTwinService editableWorkflowTwinService;

    private WorkflowPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String APP_CLONE_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String COPY_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient,
                new com.apimarketplace.publication.service.PublicationFileUrlResolver(new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac")));
        ReflectionTestUtils.setField(service, "editableWorkflowTwinService", editableWorkflowTwinService);
    }

    private WorkflowPublicationEntity activePublication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setTitle("Cool App");
        publication.setPublisherId("publisher-1");
        publication.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        publication.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        publication.setCreditsPerUse(0);
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of());
        publication.setPlanSnapshot(plan);
        return publication;
    }

    private void wireFirstTimeAcquire(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(false);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        // The application clone succeeds and returns its (run-only) workflow id.
        lenient().when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(Map.of("workflowId", APP_CLONE_ID, "title", "Cool App"));
    }

    @Test
    @DisplayName("Acquire creates the run-only APPLICATION clone ONLY - no editable copy, so no second set of interfaces / tables / agents")
    void acquireCreatesApplicationOnly() {
        WorkflowPublicationEntity publication = activePublication();
        wireFirstTimeAcquire(publication);

        Map<String, Object> result = service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG);

        assertThat(result.get("workflowId")).isEqualTo(APP_CLONE_ID);
        // The exact regression: a second clone of the same snapshot ran on every install.
        verify(snapshotCloneService, never())
                .duplicateToEditableWorkflow(any(), anyString(), anyString(), any(), any(), any(), any(), anyString());
        verifyNoInteractions(editableWorkflowTwinService);
    }

    @Test
    @DisplayName("Acquire response carries the per-type summary of what the clone created")
    void acquireReportsWhatItCreated() {
        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(false);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any(), anyString()))
                .thenReturn(Map.of("workflowId", APP_CLONE_ID, "title", "Cool App",
                        SnapshotCloneService.RESOURCES_KEY, Map.of("interfaces", 1, "tables", 2)));

        Map<String, Object> result = service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG);

        assertThat(result).containsEntry(SnapshotCloneService.RESOURCES_KEY, Map.of("interfaces", 1, "tables", 2));
    }

    @Test
    @DisplayName("On demand: delegates to the twin service keyed to the INSTALLED application clone, and hands it the publication's own plan")
    void onDemandCreatesTheCopy() {
        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(Map.of("id", APP_CLONE_ID));
        when(editableWorkflowTwinService.resolveOrCreate(
                eq(PUBLICATION_ID), eq(APP_CLONE_ID), eq(BUYER), eq(BUYER_ORG), eq("Cool App"), any()))
                .thenReturn(Map.of("workflowId", COPY_ID, "title", "Cool App", "created", true));

        Map<String, Object> result = service.createEditableWorkflowTwin(PUBLICATION_ID, BUYER, BUYER_ORG);

        assertThat(result)
                .containsEntry("workflowId", COPY_ID)
                .containsEntry("title", "Cool App")
                .containsEntry("created", true);

        // The source is a SUPPLIER (resolved only when a copy is really cloned) and it must
        // yield the publication's own snapshot - not the acquirer's already-remapped clone.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<EditableWorkflowTwinService.TwinSource>> source =
                ArgumentCaptor.forClass((Class<Supplier<EditableWorkflowTwinService.TwinSource>>) (Class<?>) Supplier.class);
        verify(editableWorkflowTwinService).resolveOrCreate(
                eq(PUBLICATION_ID), eq(APP_CLONE_ID), eq(BUYER), eq(BUYER_ORG), eq("Cool App"), source.capture());
        EditableWorkflowTwinService.TwinSource resolved = source.getValue().get();
        assertThat(resolved.planSnapshot()).isSameAs(publication.getPlanSnapshot());
        assertThat(resolved.title()).isEqualTo("Cool App");
    }

    @Test
    @DisplayName("On demand: a publication with no plan is refused when the source is resolved, not silently cloned empty")
    void onDemandRefusesAPublicationWithoutAPlan() {
        WorkflowPublicationEntity publication = activePublication();
        publication.setPlanSnapshot(null);
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(Map.of("id", APP_CLONE_ID));
        when(editableWorkflowTwinService.resolveOrCreate(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ((Supplier<?>) inv.getArgument(5)).get(); // the twin service resolves it to clone
                    return Map.of();
                });

        assertThatThrownBy(() -> service.createEditableWorkflowTwin(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no plan");
    }

    @Test
    @DisplayName("On demand: an unwired twin service reports the feature unavailable instead of pretending to copy")
    void onDemandWithoutTheTwinServiceIsUnavailable() {
        ReflectionTestUtils.setField(service, "editableWorkflowTwinService", null);
        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(Map.of("id", APP_CLONE_ID));

        assertThatThrownBy(() -> service.createEditableWorkflowTwin(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    @DisplayName("On demand: a FAILED install lookup aborts instead of claiming the application is not installed")
    void onDemandDoesNotMistakeALookupFailureForAnUninstalledApp() {
        // The strict lookup throws on a 5xx / transport error. Degrading that to null would
        // tell a user who owns the app that they do not have it.
        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenThrow(new IllegalStateException("orchestrator unreachable"));

        assertThatThrownBy(() -> service.createEditableWorkflowTwin(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("not installed");

        verifyNoInteractions(editableWorkflowTwinService);
    }

    @Test
    @DisplayName("On demand refuses when the application is not installed in this workspace (an uninstalled app has nothing to copy)")
    void onDemandRequiresTheApplicationToBeInstalled() {
        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.findBySourcePublicationStrict(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(null);

        assertThatThrownBy(() -> service.createEditableWorkflowTwin(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not installed");

        verifyNoInteractions(editableWorkflowTwinService);
    }
}
