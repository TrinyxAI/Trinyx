package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowRelationRef;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowRelations;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Assembly contract for the sub-workflow relation graph: from a flat list of
 * {@code [parentId, childId]} plan edges, each requested workflow gets the workflows that CALL it
 * (parents) and the ones it CALLS (children), with names resolved only inside the caller's
 * workspace.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowRelationServiceTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrgAccessGuard orgAccessGuard;

    @InjectMocks private WorkflowRelationService service;

    private static final String ORG = "org-1";
    private static final String USER = "user-1";
    private static final String ROLE = "MEMBER";

    private static final UUID PARENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CHILD = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID LONER = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void noRestrictionsByDefault() {
        lenient().when(orgAccessGuard.getRestrictedResourceIds(anyString(), anyString(), anyString(), any()))
                .thenReturn(Set.of());
    }

    /** One raw projection row as the native edge query returns it. */
    private static Object[] edge(UUID parentId, Object childId) {
        return new Object[]{parentId, childId};
    }

    private void givenEdges(Object[]... rows) {
        when(workflowRepository.findSubWorkflowEdgesByOrganization(ORG)).thenReturn(List.of(rows));
    }

    private void givenNames(Map<UUID, String> names) {
        when(workflowRepository.findIdNamePairsInOrganization(anyCollection(), any()))
                .thenAnswer(invocation -> {
                    Collection<?> ids = invocation.getArgument(0);
                    List<Object[]> rows = new ArrayList<>();
                    for (Object id : ids) {
                        String name = names.get(id);
                        if (name != null) rows.add(new Object[]{id, name});
                    }
                    return rows;
                });
    }

    @Test
    @DisplayName("the called workflow lists its caller as a parent, and the caller lists it as a child")
    void resolvesBothDirectionsOfOneEdge() {
        givenEdges(edge(PARENT, CHILD.toString()));
        givenNames(Map.of(PARENT, "Orchestrator", CHILD, "Enricher"));

        Map<UUID, WorkflowRelations> result = service.resolve(List.of(PARENT, CHILD), ORG, USER, ROLE);

        assertThat(result.get(CHILD).parents()).extracting(WorkflowRelationRef::name).containsExactly("Orchestrator");
        assertThat(result.get(CHILD).children()).isEmpty();
        assertThat(result.get(PARENT).children()).extracting(WorkflowRelationRef::name).containsExactly("Enricher");
        assertThat(result.get(PARENT).parents()).isEmpty();
    }

    @Test
    @DisplayName("a workflow called twice by the same plan appears once - the menu lists workflows, not call sites")
    void deduplicatesRepeatedCallsToTheSameChild() {
        givenEdges(edge(PARENT, CHILD.toString()), edge(PARENT, CHILD.toString()));
        givenNames(Map.of(CHILD, "Enricher"));

        WorkflowRelations relations = service.resolveOne(PARENT, ORG, USER, ROLE);

        assertThat(relations.children()).hasSize(1);
    }

    @Test
    @DisplayName("a workflow that calls itself is neither its own parent nor its own child")
    void dropsSelfCall() {
        givenEdges(edge(PARENT, PARENT.toString()));

        WorkflowRelations relations = service.resolveOne(PARENT, ORG, USER, ROLE);

        assertThat(relations.isEmpty()).isTrue();
        verify(workflowRepository, never()).findIdNamePairsInOrganization(anyCollection(), anyString());
    }

    @Test
    @DisplayName("a sub-workflow id that is not a UUID names no workflow to open, so it is dropped")
    void dropsNonUuidChildReference() {
        givenEdges(edge(PARENT, "{{trigger.output.workflowId}}"));

        assertThat(service.resolveOne(PARENT, ORG, USER, ROLE).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an edge whose end the member is restricted from is dropped in BOTH directions")
    void dropsRestrictedEnds() {
        when(orgAccessGuard.getRestrictedResourceIds(ORG, USER, "workflow", ROLE))
                .thenReturn(Set.of(CHILD.toString()));
        givenEdges(edge(PARENT, CHILD.toString()));

        Map<UUID, WorkflowRelations> result = service.resolve(List.of(PARENT, CHILD), ORG, USER, ROLE);

        assertThat(result.get(PARENT).children()).isEmpty();
        assertThat(result.get(CHILD).parents()).isEmpty();
    }

    @Test
    @DisplayName("a child living outside the workspace keeps its id but never its name")
    void withholdsTheNameOfAnUnresolvableChild() {
        givenEdges(edge(PARENT, OTHER.toString()));
        givenNames(Map.of()); // OTHER resolves to nothing inside this workspace

        List<WorkflowRelationRef> children = service.resolveOne(PARENT, ORG, USER, ROLE).children();

        assertThat(children).hasSize(1);
        assertThat(children.get(0).id()).isEqualTo(OTHER.toString());
        assertThat(children.get(0).name()).isNull();
        assertThat(children.get(0).resolved()).isFalse();
    }

    @Test
    @DisplayName("named relations come first, A-Z case-insensitively, with the unresolvable ones last")
    void ordersNamedRelationsFirst() {
        UUID zebra = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID apple = UUID.fromString("66666666-6666-6666-6666-666666666666");
        givenEdges(edge(PARENT, zebra.toString()), edge(PARENT, OTHER.toString()), edge(PARENT, apple.toString()));
        givenNames(Map.of(zebra, "zebra flow", apple, "Apple flow"));

        assertThat(service.resolveOne(PARENT, ORG, USER, ROLE).children())
                .extracting(WorkflowRelationRef::name)
                .containsExactly("Apple flow", "zebra flow", null);
    }

    @Test
    @DisplayName("a workflow with no relation still gets an entry, so the card grid can hide its indicator")
    void returnsAnEmptyEntryForAnUnrelatedWorkflow() {
        givenEdges(edge(PARENT, CHILD.toString()));
        givenNames(Map.of(PARENT, "Orchestrator", CHILD, "Enricher"));

        Map<UUID, WorkflowRelations> result = service.resolve(List.of(PARENT, CHILD, LONER), ORG, USER, ROLE);

        assertThat(result).containsKey(LONER);
        assertThat(result.get(LONER).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("edges between workflows nobody asked about are not resolved - no name is fetched for them")
    void ignoresEdgesThatTouchNoRequestedWorkflow() {
        UUID strangerA = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID strangerB = UUID.fromString("88888888-8888-8888-8888-888888888888");
        givenEdges(edge(strangerA, strangerB.toString()));

        assertThat(service.resolveOne(PARENT, ORG, USER, ROLE).isEmpty()).isTrue();
        verify(workflowRepository, never()).findIdNamePairsInOrganization(anyCollection(), anyString());
    }

    @Test
    @DisplayName("without an active workspace nothing is queried - the workspace IS the scope")
    void returnsEmptyWithoutAnOrganization() {
        Map<UUID, WorkflowRelations> result = service.resolve(List.of(PARENT), "", USER, ROLE);

        assertThat(result.get(PARENT).isEmpty()).isTrue();
        verify(workflowRepository, never()).findSubWorkflowEdgesByOrganization(any());
    }

    @Test
    @DisplayName("an empty id list is answered without touching the database")
    void returnsEmptyForNoIds() {
        assertThat(service.resolve(List.of(), ORG, USER, ROLE)).isEmpty();
        verify(workflowRepository, never()).findSubWorkflowEdgesByOrganization(any());
    }

    @Test
    @DisplayName("a chained workflow reports the one above it AND the one below it")
    void reportsBothEndsOfAChain() {
        givenEdges(edge(PARENT, CHILD.toString()), edge(CHILD, OTHER.toString()));
        givenNames(Map.of(PARENT, "Top", CHILD, "Middle", OTHER, "Bottom"));

        WorkflowRelations middle = service.resolveOne(CHILD, ORG, USER, ROLE);

        assertThat(middle.parents()).extracting(WorkflowRelationRef::name).containsExactly("Top");
        assertThat(middle.children()).extracting(WorkflowRelationRef::name).containsExactly("Bottom");
    }
}
