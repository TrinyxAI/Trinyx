package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * What may run inside a loop.
 *
 * <p>Every node between a loop-back's target and its source re-executes each iteration. Some
 * nodes cannot be re-entered coherently - they keep one set of state per run - and the engine
 * cannot fix that, so the workflow is rejected at authoring time rather than misbehaving at
 * run time. Nodes that merely repeat a side effect are warned about instead: repeating may well
 * be the point.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("BackEdgeSafetyValidator")
class BackEdgeSafetyValidatorTest {

    @Mock
    private WorkflowBuilderSession session;

    private final BackEdgeSafetyValidator validator = new BackEdgeSafetyValidator();

    @BeforeEach
    void defaults() {
        when(session.getTriggers()).thenReturn(List.of(Map.of("label", "Start")));
        when(session.getMcps()).thenReturn(List.of());
        when(session.getCores()).thenReturn(List.of());
        when(session.getInterfaces()).thenReturn(List.of());
        when(session.getTables()).thenReturn(List.of());
    }

    private static Map<String, Object> forward(String from, String to) {
        return Map.of("from", from, "to", to);
    }

    private static Map<String, Object> backEdge(String from, String to) {
        return Map.of("from", from, "to", to, "backEdge", Map.of("maxIterations", 5));
    }

    private ValidationResult validateWith(List<Map<String, Object>> edges) {
        when(session.getEdges()).thenReturn(edges);
        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, new ValidationGraphAnalyzer(session), result);
        return result;
    }

    @Test
    @DisplayName("a plain loop over ordinary steps is accepted")
    void plainLoopIsAccepted() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch"), Map.of("label", "Parse")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "mcp:parse"),
            backEdge("mcp:parse", "mcp:fetch")
        ));

        assertThat(result.getErrors()).noneMatch(e -> e.code().startsWith("BACK_EDGE_"));
    }

    @Test
    @DisplayName("a fork inside the loop is rejected: it claims its branches once per run")
    void forkInsideLoopIsRejected() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch"), Map.of("label", "Parse")));
        when(session.getCores()).thenReturn(List.of(Map.of("label", "Split Work", "type", "fork")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:split_work"),
            forward("core:split_work", "mcp:parse"),
            backEdge("mcp:parse", "mcp:fetch")
        ));

        assertThat(result.getErrors())
            .anyMatch(e -> e.code().equals("BACK_EDGE_UNSAFE_NODE_IN_LOOP"));
    }

    @Test
    @DisplayName("the SAME fork outside the loop is fine")
    void forkOutsideLoopIsAccepted() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch"), Map.of("label", "Parse")));
        when(session.getCores()).thenReturn(List.of(Map.of("label", "Split Work", "type", "fork")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "mcp:parse"),
            backEdge("mcp:parse", "mcp:fetch"),
            forward("mcp:parse", "core:split_work")
        ));

        assertThat(result.getErrors()).noneMatch(e -> e.code().equals("BACK_EDGE_UNSAFE_NODE_IN_LOOP"));
    }

    @Test
    @DisplayName("a trigger inside the loop is rejected")
    void triggerInsideLoopIsRejected() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch")));

        // The loop-back re-enters the trigger itself.
        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            backEdge("mcp:fetch", "trigger:start")
        ));

        assertThat(result.getErrors())
            .anyMatch(e -> e.code().equals("BACK_EDGE_TRIGGER_IN_LOOP"));
    }

    @Test
    @DisplayName("an interface inside the loop is rejected: it has no iteration dimension")
    void interfaceInsideLoopIsRejected() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch")));
        when(session.getInterfaces()).thenReturn(List.of(Map.of("label", "Review")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "interface:review"),
            backEdge("interface:review", "mcp:fetch")
        ));

        assertThat(result.getErrors())
            .anyMatch(e -> e.code().equals("BACK_EDGE_INTERFACE_IN_LOOP"));
    }

    @Test
    @DisplayName("Regression: an interface AFTER a loop is accepted - it is not inside it")
    void interfaceAfterTheLoopIsAccepted() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch")));
        when(session.getInterfaces()).thenReturn(List.of(Map.of("label", "Review")));
        when(session.getCores()).thenReturn(List.of(Map.of("label", "Spin", "type", "loop")));

        // A run report placed after a loop is the normal shape of every sequence workflow. The
        // span was walked from the HUB, and once the port is stripped :body and :exit look the
        // same, so the walk went down the exit path too and everything after the loop counted as
        // inside it. Walking from the BODY entry - what the engine actually resets - keeps the
        // tail out.
        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "core:spin"),
            forward("core:spin:body", "mcp:fetch"),
            backEdge("mcp:fetch", "core:spin:iterate"),
            forward("core:spin:exit", "interface:review")
        ));

        assertThat(result.getErrors())
            .as("the interface sits on the exit path, not in the body")
            .noneMatch(e -> e.code().equals("BACK_EDGE_INTERFACE_IN_LOOP"));
    }

    @Test
    @DisplayName("a repeated side effect is a warning, not an error")
    void repeatedSideEffectIsWarned() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch")));
        when(session.getCores()).thenReturn(List.of(Map.of("label", "Notify", "type", "send_email")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:notify"),
            backEdge("core:notify", "mcp:fetch")
        ));

        assertThat(result.getErrors()).noneMatch(e -> e.code().startsWith("BACK_EDGE_"));
        assertThat(result.getWarnings())
            .anyMatch(w -> w.code().equals("BACK_EDGE_REPEATED_SIDE_EFFECT"));
    }

    @Test
    @DisplayName("regression: a While loop reports NOTHING, even with unsafe nodes after it")
    void whileLoopIsNotInspected() {
        // The span of a loop NODE cannot be derived from the session graph: once ports are
        // stripped, its body and its exit are indistinguishable, so the walk ran down the exit
        // path and flagged everything AFTER the loop. These restrictions therefore apply to
        // declared back-edges only, and a While loop must come out completely clean.
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Attempt")));
        when(session.getCores()).thenReturn(List.of(
            Map.of("label", "Retry", "type", "loop"),
            Map.of("label", "Split Work", "type", "fork")));
        when(session.getInterfaces()).thenReturn(List.of(Map.of("label", "Review")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "core:retry"),
            forward("core:retry:body", "mcp:attempt"),
            forward("mcp:attempt", "core:retry:iterate"),
            forward("core:retry:exit", "core:split_work"),
            forward("core:split_work", "interface:review")
        ));

        assertThat(result.getErrors()).noneMatch(e -> e.code().startsWith("BACK_EDGE_"));
        assertThat(result.getWarnings()).noneMatch(w -> w.code().startsWith("BACK_EDGE_"));
    }

    @Test
    @DisplayName("a loop-back on a node that also continues forward can never run")
    void unportedLoopBackOnANodeThatAlsoContinuesIsRejected() {
        // The engine only takes a loop-back when the source produced no forward successor, so
        // this loop is dead on arrival - and silently so.
        when(session.getMcps()).thenReturn(List.of(
            Map.of("label", "Fetch"), Map.of("label", "Parse"), Map.of("label", "Store")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "mcp:parse"),
            forward("mcp:parse", "mcp:store"),
            backEdge("mcp:parse", "mcp:fetch")
        ));

        assertThat(result.getErrors())
            .anyMatch(e -> e.code().equals("BACK_EDGE_SOURCE_ALSO_CONTINUES"));
    }

    @Test
    @DisplayName("the same loop-back on a BRANCH is the normal shape and is accepted")
    void portedLoopBackAlongsideAForwardBranchIsAccepted() {
        // A Decision's 'else' loops while its 'if' carries on: the branch the loop sits on has
        // no forward target, so selecting it enters the loop.
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch"), Map.of("label", "Done")));
        when(session.getCores()).thenReturn(List.of(Map.of("label", "Check", "type", "decision")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:check"),
            forward("core:check:if", "mcp:done"),
            backEdge("core:check:else", "mcp:fetch")
        ));

        assertThat(result.getErrors()).noneMatch(e -> e.code().startsWith("BACK_EDGE_"));
    }

    @Test
    @DisplayName("a workflow with no loop is not inspected at all")
    void noLoopMeansNoFindings() {
        when(session.getMcps()).thenReturn(List.of(Map.of("label", "Fetch")));
        when(session.getCores()).thenReturn(List.of(Map.of("label", "Split Work", "type", "fork")));

        ValidationResult result = validateWith(List.of(
            forward("trigger:start", "mcp:fetch"),
            forward("mcp:fetch", "core:split_work")
        ));

        assertThat(result.getErrors()).noneMatch(e -> e.code().startsWith("BACK_EDGE_"));
        assertThat(result.getWarnings()).noneMatch(w -> w.code().startsWith("BACK_EDGE_"));
    }
}
