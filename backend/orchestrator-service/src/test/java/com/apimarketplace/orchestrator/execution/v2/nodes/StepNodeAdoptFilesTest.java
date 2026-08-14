package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that a step files the files it produced under its own run.
 *
 * <p>A catalog API answering with binary (the ElevenLabs text-to-speech case) is uploaded from
 * catalog-service, which knows the tenant and nothing else, so its storage row carries no workflow
 * and the Files browser shows it at the ROOT instead of inside "xAI Video Sequence / Run 12". The
 * step is the first place that knows both the file and the run context, and adopts it here.</p>
 *
 * <p>The other half of the contract matters just as much: filing is cosmetic, so nothing about it
 * may change whether the step succeeded.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StepNode - adopting produced files into the run")
class StepNodeAdoptFilesTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private ToolsGateway mockToolsGateway;
    @Mock private FileStorageService mockFileStorage;
    @Mock private ServiceRegistry mockRegistry;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                new HashMap<>(), mockPlan);
        when(mockPlan.getId()).thenReturn("wf-1");
        when(mockRegistry.getToolsGateway()).thenReturn(mockToolsGateway);
        when(mockRegistry.getFileStorageService()).thenReturn(mockFileStorage);
    }

    /** A step wired the way the engine wires it: services pulled from the registry. */
    private StepNode wiredNode() {
        Step step = new Step("elevenlabs-tts", "mcp", "Text to speech", null, Map.of(), null, null, null);
        StepNode node = new StepNode("mcp:elevenlabs_tts", step);
        node.acceptServices(mockRegistry);
        return node;
    }

    /** The output shape a binary catalog response produces: a FileRef under the projected key. */
    private static Map<String, Object> outputWithFile(String id) {
        return Map.of("audio", Map.of(
                "_type", "file",
                "path", "1/general/catalog-binary/c31d2812_elevenlabs-text-to-speech.mp3",
                "name", "elevenlabs-text-to-speech.mp3",
                "mimeType", "audio/mpeg",
                "size", 218800,
                "id", id));
    }

    private void toolReturns(ExecutionResult result) {
        when(mockToolsGateway.executeTool(any(ToolRef.class), any(), eq("tenant-1"), any())).thenReturn(result);
    }

    @Nested
    @DisplayName("files what the step produced")
    class Files {

        @Test
        @DisplayName("adopts the FileRef in the output under this run, step and tenant")
        void adoptsProducedFile() {
            toolReturns(new ExecutionResult(true, outputWithFile("f-1"), List.of(), List.of()));
            StepNode node = wiredNode();

            node.execute(context);

            ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(mockFileStorage).adoptRunContext(eq("tenant-1"), ids.capture(), eq("wf-1"),
                    eq("run-1"), eq("mcp:elevenlabs_tts"), anyInt(), anyInt(), any());
            assertThat(ids.getValue()).containsExactly("f-1");
        }

        @Test
        @DisplayName("does not call storage at all when the step produced no file")
        void noFileNoCall() {
            toolReturns(new ExecutionResult(true, Map.of("text", "hello"), List.of(), List.of()));

            wiredNode().execute(context);

            verify(mockFileStorage, never()).adoptRunContext(anyString(), any(), anyString(),
                    any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("a FAILED step adopts nothing - it has no output to claim")
        void failedStepAdoptsNothing() {
            toolReturns(new ExecutionResult(false, outputWithFile("f-1"),
                    List.of(Map.of("message", "boom")), List.of()));

            NodeExecutionResult result = wiredNode().execute(context);

            assertThat(result.isFailure()).isTrue();
            verify(mockFileStorage, never()).adoptRunContext(anyString(), any(), anyString(),
                    any(), any(), anyInt(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("filing never decides whether the step succeeded")
    class NeverBreaksTheStep {

        @Test
        @DisplayName("the step still succeeds when adoption throws")
        void adoptionFailureDoesNotFailTheStep() {
            // storage-service down, a network blip: the file is stored and is already reachable
            // from this step's output. Losing its folder must not lose the step.
            toolReturns(new ExecutionResult(true, outputWithFile("f-1"), List.of(), List.of()));
            when(mockFileStorage.adoptRunContext(anyString(), any(), anyString(), any(), any(),
                    anyInt(), anyInt(), any())).thenThrow(new RuntimeException("storage unreachable"));

            NodeExecutionResult result = wiredNode().execute(context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.output()).containsKey("audio");
        }

        @Test
        @DisplayName("the step still succeeds when no storage service is wired at all")
        void noStorageServiceIsFine() {
            // Test profiles and the local/mock storage mode leave it unset.
            when(mockRegistry.getFileStorageService()).thenReturn(null);
            toolReturns(new ExecutionResult(true, outputWithFile("f-1"), List.of(), List.of()));

            assertThat(wiredNode().execute(context).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("the output handed downstream is untouched by the adoption")
        void outputIsUnchanged() {
            toolReturns(new ExecutionResult(true, outputWithFile("f-1"), List.of(), List.of()));
            when(mockFileStorage.adoptRunContext(anyString(), any(), anyString(), any(), any(),
                    anyInt(), anyInt(), any())).thenReturn(1);

            NodeExecutionResult result = wiredNode().execute(context);

            // Downstream nodes resolve templates against this map; adoption is a storage-side
            // correction and must leave the step's own contract alone.
            @SuppressWarnings("unchecked")
            Map<String, Object> audio = (Map<String, Object>) result.output().get("audio");
            assertThat(audio).containsEntry("id", "f-1").containsEntry("mimeType", "audio/mpeg");
        }
    }
}
