package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The per-type summary a clone reports ({@code resources}) - what the post-install screen
 * shows the user.
 *
 * <p>It must count what was actually CREATED, including the resources a sub-workflow
 * brings with it: those land in the same lists as the root's and a summary that skipped
 * them would be exactly the kind of silent workspace change this feature removes.
 */
@DisplayName("SnapshotCloneService - install summary of created resources")
class SnapshotCloneServiceResourceSummaryTest {

    private static final String TENANT = "tenant-1";
    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUB_ID = "44444444-4444-4444-4444-444444444444";

    private OrchestratorInternalClient orchestratorClient;
    private AgentClient agentClient;
    private InterfaceClient interfaceClient;
    private DataSourceClient dataSourceClient;
    private SnapshotCloneService service;

    @BeforeEach
    void setUp() {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        agentClient = mock(AgentClient.class);
        interfaceClient = mock(InterfaceClient.class);
        dataSourceClient = mock(DataSourceClient.class);
        service = new SnapshotCloneService(
                orchestratorClient, agentClient, interfaceClient, dataSourceClient,
                mock(StorageBreakdownService.class), new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenAnswer(inv -> Map.of("id", UUID.randomUUID().toString()));

        DataSourceDto clonedDs = mock(DataSourceDto.class);
        when(clonedDs.id()).thenReturn(99L);
        when(dataSourceClient.createFromSnapshot(any(), anyString())).thenReturn(clonedDs);
        InterfaceDto savedIface = mock(InterfaceDto.class);
        when(savedIface.getId()).thenReturn(UUID.fromString("55555555-5555-5555-5555-555555555555"));
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(savedIface);
        when(agentClient.cloneFromSnapshot(any()))
                .thenAnswer(inv -> Map.of("agentId", UUID.randomUUID().toString()));
    }

    private static Map<String, Object> tableNode(String id, String name) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("dataSourceId", id);
        table.put("_snapshot_ds_name", name);
        table.put("_snapshot_ds_sourceType", "INLINE");
        return table;
    }

    private static Map<String, Object> interfaceNode(String id, String name) {
        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("id", id);
        iface.put("_snapshot_htmlTemplate", "<html></html>");
        iface.put("_snapshot_name", name);
        return iface;
    }

    private static Map<String, Object> agentNode(String id, String name) {
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("agentConfigId", id);
        agent.put("_snapshot_agent_name", name);
        return agent;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourcesOf(Map<String, Object> cloneResult) {
        return (Map<String, Object>) cloneResult.get(SnapshotCloneService.RESOURCES_KEY);
    }

    @Test
    @DisplayName("Counts every interface, table and agent the clone created")
    void countsCreatedResources() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(tableNode("42", "Customers"))));
        plan.put("interfaces", new ArrayList<>(List.of(
                interfaceNode("iface-1", "Home"), interfaceNode("iface-2", "Detail"))));
        plan.put("agents", new ArrayList<>(List.of(agentNode("agent-1", "Helper"))));

        Map<String, Object> result = service.cloneFromSnapshot(
                plan, TENANT, PUBLICATION_ID, "App", "desc", null, "org-1");

        assertThat(resourcesOf(result))
                .containsEntry("interfaces", 2)
                .containsEntry("tables", 1)
                .containsEntry("agents", 1);
    }

    @Test
    @DisplayName("Omits types that produced nothing, so a bare workflow reports an empty summary")
    void omitsEmptyTypes() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("cores", List.of());

        Map<String, Object> result = service.cloneFromSnapshot(
                plan, TENANT, PUBLICATION_ID, "App", "desc", null, "org-1");

        assertThat(resourcesOf(result)).isEmpty();
    }

    @Test
    @DisplayName("Counts resources whose snapshot carries no old id - they exist in the workspace all the same")
    void countsResourcesWithoutAnOldId() {
        // Counting from the id mappings would miss these: a mapping only records nodes that
        // had an old id to remap, while the row is created either way and shows up in the
        // user's list. The summary must not under-report what it put there.
        Map<String, Object> idlessTable = tableNode("42", "Customers");
        idlessTable.remove("dataSourceId");
        Map<String, Object> idlessInterface = interfaceNode("iface-1", "Home");
        idlessInterface.remove("id");
        Map<String, Object> idlessAgent = agentNode("agent-1", "Helper");
        idlessAgent.remove("agentConfigId");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", new ArrayList<>(List.of(idlessTable)));
        plan.put("interfaces", new ArrayList<>(List.of(idlessInterface)));
        plan.put("agents", new ArrayList<>(List.of(idlessAgent)));

        Map<String, Object> result = service.cloneFromSnapshot(
                plan, TENANT, PUBLICATION_ID, "App", "desc", null, "org-1");

        assertThat(resourcesOf(result))
                .containsEntry("interfaces", 1)
                .containsEntry("tables", 1)
                .containsEntry("agents", 1);
    }

    @Test
    @DisplayName("A resource that FAILED to clone is not reported as installed")
    void doesNotCountAFailedClone() {
        when(interfaceClient.createInterface(any(InterfaceCreateRequest.class), anyString())).thenReturn(null);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(interfaceNode("iface-1", "Home"))));

        Map<String, Object> result = service.cloneFromSnapshot(
                plan, TENANT, PUBLICATION_ID, "App", "desc", null, "org-1");

        assertThat(resourcesOf(result)).doesNotContainKey("interfaces");
    }

    @Test
    @DisplayName("Includes the resources a sub-workflow brings, plus the sub-workflow itself")
    void countsSubWorkflowResourcesToo() {
        Map<String, Object> subPlan = new LinkedHashMap<>();
        subPlan.put("interfaces", new ArrayList<>(List.of(interfaceNode("sub-iface", "Sub"))));
        subPlan.put("tables", new ArrayList<>(List.of(tableNode("7", "SubTable"))));

        Map<String, Object> subSnapshot = new LinkedHashMap<>();
        subSnapshot.put("name", "Child");
        subSnapshot.put("plan", subPlan);

        Map<String, Object> subs = new LinkedHashMap<>();
        subs.put(SUB_ID, subSnapshot);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("interfaces", new ArrayList<>(List.of(interfaceNode("root-iface", "Root"))));
        plan.put("_snapshot_subworkflows", subs);

        Map<String, Object> result = service.cloneFromSnapshot(
                plan, TENANT, PUBLICATION_ID, "App", "desc", null, "org-1");

        assertThat(resourcesOf(result))
                .as("root interface + the child's own interface, the child's table, and the child workflow row")
                .containsEntry("interfaces", 2)
                .containsEntry("tables", 1)
                .containsEntry("workflows", 1);
    }
}
