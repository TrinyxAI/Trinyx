package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.callback.AgentContextBuilder;
import com.apimarketplace.conversation.service.ai.schema.HelpSeenRegistry;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Both the end-of-turn persistence and the end-of-turn WS replay run AFTER a park has
 * ended, so either can put back on screen a card the user already dismissed. One rule
 * governs both: only an ANSWER settles a card.
 *
 * <p>These go through the real methods rather than the predicate, because the failure that
 * matters is a call site losing its guard - a predicate-only test stays green through that.
 */
@DisplayName("ConversationAgentService - what survives a park, at end of turn")
class ConversationAgentServiceApprovalGateReplayTest {

    private EventBus eventBus;
    private PendingActionService pendingActionService;
    private ConversationAgentService service;

    @BeforeEach
    void setUp() {
        eventBus = mock(EventBus.class);
        pendingActionService = mock(PendingActionService.class);
        service = new ConversationAgentService(
            mock(AgentContextBuilder.class),
            mock(AgentObservabilityClient.class),
            mock(AgentConfigProvider.class),
            mock(CreditConsumptionClient.class),
            mock(MessageService.class),
            pendingActionService,
            mock(ToolResultService.class),
            new ObjectMapper(),
            mock(AgentClient.class),
            mock(StreamStateService.class),
            eventBus,
            mock(HelpSeenRegistry.class),
            mock(MessageRepository.class),
            "http://localhost:8087"
        );
    }

    /** A gated tool result carrying the outcome the park ended with (null = never parked). */
    private static AgentExecutionResponseDto gatedResult(String gateDecision) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("toolAuthorizationRequired", true);
        metadata.put("rule", "workflow:execute");
        metadata.put("toolName", "workflow");
        metadata.put("action", "execute");
        metadata.put("toolCallId", "call-9");
        if (gateDecision != null) {
            metadata.put("approvalCardEmitted", true);
            metadata.put("approvalGateDecision", gateDecision);
        }
        Map<String, Object> toolResult = new HashMap<>();
        toolResult.put("metadata", metadata);
        return new AgentExecutionResponseDto(
            true, null, null, List.of(toolResult), 0, null, null, 0L, null, null,
            null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("A card the user REFUSED is neither persisted nor replayed")
    void refusedCardIsNotResurrected() {
        service.persistPendingActionIfNeeded("conv-1", gatedResult("denied"));
        service.emitPendingApprovalIfPresent("conv-1", gatedResult("denied"));

        // Either one would put a dismissed card back in front of the user: the persisted row
        // on the next page load, the WS replay immediately.
        verify(pendingActionService, never()).addPendingActions(anyString(), any());
        verifyNoInteractions(eventBus);
    }

    @Test
    @DisplayName("A card left over from a STOPPED turn is settled too - it must not come back")
    void stoppedCardIsNotResurrected() {
        service.persistPendingActionIfNeeded("conv-1", gatedResult("stopped"));
        service.emitPendingApprovalIfPresent("conv-1", gatedResult("stopped"));

        // Bringing it back on the next page load would invite the user to authorize a turn
        // they cancelled, and that click would find no park listening: it does nothing at all.
        verify(pendingActionService, never()).addPendingActions(anyString(), any());
        verifyNoInteractions(eventBus);
    }

    @Test
    @DisplayName("A park that EXPIRED left its card waiting, so it is both persisted and replayed")
    void expiredCardSurvives() {
        service.persistPendingActionIfNeeded("conv-1", gatedResult("expired"));
        service.emitPendingApprovalIfPresent("conv-1", gatedResult("expired"));

        // Nobody answered: the card is still on screen and must survive a refresh, and the
        // replay is the safety net for a client that subscribed after the card went out.
        verify(pendingActionService).addPendingActions(anyString(), any());
        verify(eventBus).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("A park the gate could not run at all leaves the card fully pending")
    void unavailableParkLeavesTheCardPending() {
        service.persistPendingActionIfNeeded("conv-1", gatedResult("unavailable"));
        service.emitPendingApprovalIfPresent("conv-1", gatedResult("unavailable"));

        verify(pendingActionService).addPendingActions(anyString(), any());
        verify(eventBus).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("A result that never went through a park behaves exactly as before the gate")
    void unparkedResultIsUnaffected() {
        service.persistPendingActionIfNeeded("conv-1", gatedResult(null));
        service.emitPendingApprovalIfPresent("conv-1", gatedResult(null));

        verify(pendingActionService).addPendingActions(anyString(), any());
        verify(eventBus).publish(anyString(), anyString());
    }

    @Test
    @DisplayName("The replayed payload is the card itself, not a stripped copy")
    void replayedCardCarriesItsIdentity() {
        service.emitPendingApprovalIfPresent("conv-1", gatedResult("expired"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(anyString(), payload.capture());
        // Without the rule the frontend cannot match this to the card already on screen and
        // would show a second one.
        assertThat(payload.getValue()).contains("workflow:execute").contains("call-9");
    }

    @Test
    @DisplayName("The two paths agree: what one drops, the other drops")
    void bothPathsUseTheSameRule() {
        // They diverged once: persistence kept an expired card (correct) while the replay
        // dropped every gate-published card, so a user hitting the subscribe race saw
        // nothing until a refresh. Same input, same decision, on both.
        for (String decision : new String[] {"denied", "stopped", "expired", "unavailable", null}) {
            EventBus bus = mock(EventBus.class);
            PendingActionService pending = mock(PendingActionService.class);
            ConversationAgentService svc = new ConversationAgentService(
                mock(AgentContextBuilder.class), mock(AgentObservabilityClient.class),
                mock(AgentConfigProvider.class), mock(CreditConsumptionClient.class),
                mock(MessageService.class), pending, mock(ToolResultService.class),
                new ObjectMapper(), mock(AgentClient.class), mock(StreamStateService.class),
                bus, mock(HelpSeenRegistry.class), mock(MessageRepository.class),
                "http://localhost:8087");

            svc.persistPendingActionIfNeeded("conv-1", gatedResult(decision));
            svc.emitPendingApprovalIfPresent("conv-1", gatedResult(decision));

            boolean settled = "denied".equals(decision) || "stopped".equals(decision);
            verify(pending, settled ? never() : org.mockito.Mockito.times(1))
                .addPendingActions(anyString(), any());
            verify(bus, settled ? never() : org.mockito.Mockito.times(1))
                .publish(anyString(), anyString());
        }
    }
}
