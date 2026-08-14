package com.apimarketplace.orchestrator.execution.v2.adhoc;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.CoreNodeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.persistence.OutputSchemaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The orchestration around a standalone node: what plan the engine's builder is handed, how the
 * single node is picked out of it, and how outcomes are turned into values.
 *
 * <p>The builder and the mapper are mocked here on purpose. Wiring the real ones drags in the
 * template engine and the node-definition registry, and a test that mocks THOSE would assert
 * nothing about a real node anyway. Whether a real node really runs is an end-to-end question
 * and is answered end-to-end; what belongs here is the part that has no other home: the shape of
 * the synthetic plan, which is where a mistake is silent rather than loud.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdHocNodeExecutionServiceTest {

    @Mock private CoreNodeBuilder coreNodeBuilder;
    @Mock private ExecutionServiceInjector serviceInjector;
    @Mock private OutputSchemaMapper outputSchemaMapper;
    @Mock private ExecutionNode node;
    @Mock private com.apimarketplace.orchestrator.services.credit.NodeCreditGate nodeCreditGate;

    private AdHocNodeExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AdHocNodeExecutionService(coreNodeBuilder, serviceInjector, outputSchemaMapper, nodeCreditGate);
        lenient().when(nodeCreditGate.denyOrNull(any(), any())).thenReturn(null);
        lenient().when(outputSchemaMapper.transformToDbSchema(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** Make the mocked builder deposit our node, as the real one would. */
    @SuppressWarnings("unchecked")
    private void builderProduces(ExecutionNode produced) {
        doAnswer(inv -> {
            Map<String, ExecutionNode> map = inv.getArgument(0);
            map.put("core:probe", produced);
            return null;
        }).when(coreNodeBuilder).createCoreNodes(any(), any(), any());
    }

    private AdHocNodeRequest request(String type, Map<String, Object> config) {
        return new AdHocNodeRequest(type, AdHocNodeTypeResolver.configKey(type), config, Map.of(),
                "tenant-1", "org-1", "OWNER", "Probe");
    }

    private WorkflowPlan capturePlan(AdHocNodeRequest request) {
        builderProduces(node);
        when(node.execute(any())).thenReturn(NodeExecutionResult.success("core:probe", Map.of()));
        service.execute(request);
        ArgumentCaptor<WorkflowPlan> captor = ArgumentCaptor.forClass(WorkflowPlan.class);
        org.mockito.Mockito.verify(coreNodeBuilder).createCoreNodes(any(), captor.capture(), any());
        return captor.getValue();
    }

    @Nested
    @DisplayName("the synthetic plan")
    class SyntheticPlan {

        @Test
        @DisplayName("Should place the config under the type's nested key, where the engine reads it")
        void shouldNestConfigUnderTypeKey() {
            // Depositing config at the core's top level builds a node that runs and reads
            // nothing: the failure is a silently empty node, not an error.
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("action", "none");
            config.put("folder", "INBOX");

            WorkflowPlan plan = capturePlan(request("email_inbox", config));

            assertThat(plan.getCores()).hasSize(1);
            Core core = plan.getCores().get(0);
            assertThat(core.type()).isEqualTo("email_inbox");
            assertThat(core.emailInboxConfig()).isNotNull();
            assertThat(core.emailInboxConfig().action())
                    .as("reading is the default action; there is no action called read")
                    .isEqualTo("none");
            assertThat(core.emailInboxConfig().folder()).isEqualTo("INBOX");
        }

        @Test
        @DisplayName("Should never hand the builder a null plan")
        void shouldAlwaysProvideAPlan() {
            // Several nodes dereference context.plan().getId() to namespace the files they
            // produce, some inside a local try/catch: a null plan does not fail loudly, it
            // silently drops the file from the output.
            WorkflowPlan plan = capturePlan(request("transform", Map.of()));

            assertThat(plan).isNotNull();
            // The PLAN id must be a real UUID: the parser validates it and quietly swaps in a
            // random one otherwise, so a prefixed value would be discarded unnoticed.
            assertThat(java.util.UUID.fromString(plan.getId())).isNotNull();
            assertThat(plan.getTenantId()).isEqualTo("tenant-1");
        }

        @Test
        @DisplayName("Should hold exactly one core and no trigger, step or edge")
        void shouldHoldExactlyOneCore() {
            WorkflowPlan plan = capturePlan(request("transform", Map.of()));

            assertThat(plan.getCores()).hasSize(1);
            assertThat(plan.getTriggers()).isEmpty();
            assertThat(plan.getMcps()).isEmpty();
            assertThat(plan.getEdges()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the execution context")
    class Context {

        @Test
        @DisplayName("Should carry the workspace identity and leave workflowRunId null")
        void shouldCarryOrgAndNoRunId() {
            // A synthetic workflowRunId is what downstream billing reads to choose its scope:
            // it would create pricing pins pointing at a run that will never close.
            builderProduces(node);
            when(node.execute(any())).thenReturn(NodeExecutionResult.success("core:probe", Map.of()));

            service.execute(request("transform", Map.of()));

            ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
            org.mockito.Mockito.verify(node).execute(captor.capture());
            ExecutionContext ctx = captor.getValue();
            assertThat(ctx.workflowRunId()).isNull();
            assertThat(ctx.runId()).startsWith(AdHocNodeExecutionService.RUN_ID_PREFIX);
            assertThat(ctx.organizationId()).isEqualTo("org-1");
            assertThat(ctx.organizationRole()).isEqualTo("OWNER");
            assertThat(ctx.tenantId()).isEqualTo("tenant-1");
        }

        @Test
        @DisplayName("Should feed run_input into BOTH the trigger data and the step outputs")
        void shouldFeedRunInputBothWays() {
            // A template written for a real workflow has to resolve here the same way, otherwise
            // the probe is green and the workflow is not.
            builderProduces(node);
            when(node.execute(any())).thenReturn(NodeExecutionResult.success("core:probe", Map.of()));

            service.execute(new AdHocNodeRequest("transform", "transform", Map.of(),
                    Map.of("upstream", Map.of("value", 42)), "tenant-1", null, null, "Probe"));

            ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
            org.mockito.Mockito.verify(node).execute(captor.capture());
            assertThat(captor.getValue().triggerData()).containsKey("upstream");
            assertThat(captor.getValue().stepOutputs()).containsKey("upstream");
        }
    }

    @Nested
    @DisplayName("credits")
    class Credits {

        @Test
        @DisplayName("Should refuse to run when the workspace is out of credits, before any side effect")
        void shouldHonourTheCreditGate() {
            // The engine gates EVERY node on credits. Running outside the engine is not a reason
            // to run for free: an exhausted workspace could otherwise keep spending on paid
            // providers one probe at a time.
            builderProduces(node);
            when(nodeCreditGate.denyOrNull(any(), any())).thenReturn(
                    NodeExecutionResult.failure("core:probe", "Out of credits"));

            AdHocNodeResult result = service.execute(request("http_request", Map.of()));

            assertThat(result.status()).isEqualTo(AdHocNodeResult.FAILED);
            assertThat(result.error()).contains("Out of credits");
            org.mockito.Mockito.verify(node, org.mockito.Mockito.never()).execute(any());
        }
    }

    @Nested
    @DisplayName("outcomes are values, never exceptions")
    class Outcomes {

        @Test
        @DisplayName("Should report a build that produced no node instead of throwing")
        void shouldReportEmptyBuild() {
            doAnswer(inv -> null).when(coreNodeBuilder).createCoreNodes(any(), any(), any());

            AdHocNodeResult result = service.execute(request("transform", Map.of()));

            assertThat(result.status()).isEqualTo(AdHocNodeResult.FAILED);
            assertThat(result.error()).contains("could not be built");
        }

        @Test
        @DisplayName("Should turn a node that throws into a failure value")
        void shouldCatchNodeException() {
            // A stack trace turned into an error string is how a DSN reaches a transcript.
            builderProduces(node);
            when(node.execute(any())).thenThrow(new IllegalStateException("connection refused"));

            AdHocNodeResult result = service.execute(request("database", Map.of()));

            assertThat(result.status()).isEqualTo(AdHocNodeResult.FAILED);
            assertThat(result.error()).contains("connection refused");
        }

        @Test
        @DisplayName("Should report an awaiting-signal node as its own state, not as a failure")
        void shouldReportAwaitingSignalDistinctly() {
            // The node did not go wrong, it asked to be resumed. Calling that a failure sends
            // the agent hunting for a configuration mistake that does not exist.
            builderProduces(node);
            when(node.execute(any())).thenReturn(
                    NodeExecutionResult.awaitingSignal("core:probe",
                            com.apimarketplace.orchestrator.domain.execution.SignalType.USER_APPROVAL, Map.of()));

            AdHocNodeResult result = service.execute(request("transform", Map.of()));

            assertThat(result.status()).isEqualTo(AdHocNodeResult.AWAITING_SIGNAL);
            assertThat(result.note()).contains("workflow(action='execute')");
        }

        @Test
        @DisplayName("Should fall back to the raw output when the schema mapper cannot map it")
        void shouldFallBackToRawOutput() {
            builderProduces(node);
            when(node.execute(any())).thenReturn(
                    NodeExecutionResult.success("core:probe", Map.of("raw", "value")));
            when(outputSchemaMapper.transformToDbSchema(any(), any()))
                    .thenThrow(new IllegalArgumentException("no mapper"));

            AdHocNodeResult result = service.execute(request("transform", Map.of()));

            assertThat(result.status()).isEqualTo(AdHocNodeResult.COMPLETED);
            assertThat(result.output()).containsEntry("raw", "value");
        }
    }
}
