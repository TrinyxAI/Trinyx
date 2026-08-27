package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.CreateConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.GlobalExceptionHandler;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.service.ConversationCommandService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.PendingActionResumeService;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import com.apimarketplace.conversation.service.approval.ToolApprovalGateResolver;
import com.apimarketplace.conversation.service.approval.ToolAuthorizationApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConversationControllerTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ConversationCommandService conversationCommandService;

    @Mock
    private ConversationQueryService conversationQueryService;

    @Mock
    private MessageService messageService;

    @Mock
    private PendingActionService pendingActionService;

    @Mock
    private PendingActionResumeService pendingActionResumeService;

    @Mock
    private ServiceApprovalService serviceApprovalService;

    @Mock
    private ToolAuthorizationApprovalService toolAuthorizationApprovalService;

    /** Releases a tool call the agent parked on this card; a no-op when nothing is parked. */
    @Mock
    private ToolApprovalGateResolver toolApprovalGateResolver;

    @InjectMocks
    private ConversationController conversationController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(conversationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createConversationReturnsCreated() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-123");
        dto.setUserId("user-1");
        when(conversationCommandService.createConversation(any())).thenReturn(dto);

        CreateConversationDto payload = new CreateConversationDto("title", "model", "provider");

        mockMvc.perform(post("/api/conversations")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("conv-123"));
    }

    @Test
    void createConversationReturnsUnauthorizedWithoutUserHeader() throws Exception {
        CreateConversationDto payload = new CreateConversationDto("title", "model", "provider");

        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());

        verify(conversationCommandService, never()).createConversation(any());
    }

    @Test
    void getConversationReturns404WhenMissing() throws Exception {
        when(conversationQueryService.getConversationById("missing", "user-1", null))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/conversations/{conversationId}", "missing")
                        .header("X-User-ID", "user-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void addMessageReturnsConflictWhenConversationInactive() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(messageService.addMessage(eq("conv-1"), any(MessageDto.class)))
                .thenThrow(new ConversationInactiveException("conv-1"));

        mockMvc.perform(post("/api/conversations/{conversationId}/messages", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messagePayload()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_INACTIVE"));
    }

    @Test
    void addMessageReturnsBadRequestWhenPayloadInvalid() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(messageService.addMessage(eq("conv-1"), any(MessageDto.class)))
                .thenThrow(new InvalidMessageException("missing role"));

        mockMvc.perform(post("/api/conversations/{conversationId}/messages", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messagePayload()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MESSAGE"));
    }

    @Test
    void approveToolAuthorizationPersistsAndClearsPending() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rule", "application:acquire",
                                "remember", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rule").value("application:acquire"))
                .andExpect(jsonPath("$.remembered").value(true));

        verify(toolAuthorizationApprovalService).approve("conv-1", "application:acquire", true);
        // Only this rule's card is cleared so other parallel cards stay pending.
        verify(pendingActionService).clearOnePendingAction("conv-1", "auth:application:acquire");
        // No toolCallId in the body means no call is parked - releasing must be a no-op,
        // not a stray verdict key that a later call could inherit.
        verify(toolApprovalGateResolver).resolve("conv-1", null, true);
    }

    @Test
    void approveToolAuthorizationReleasesTheParkedCallWhenGivenItsId() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(toolApprovalGateResolver.resolve("conv-1", "call-9", true)).thenReturn(true);

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rule", "workflow:execute",
                                "remember", false,
                                "toolCallId", "call-9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parkedCallReleased").value(true));

        verify(toolApprovalGateResolver).resolve("conv-1", "call-9", true);
        // NO single-shot grant when the call was released. That grant is consumed at the
        // start of the next turn, and a released call resumes inside the turn already
        // running - so it would survive and let the NEXT call of this rule run with no card.
        // Releasing is the authorization; writing both would widen what the user allowed.
        verify(toolAuthorizationApprovalService, never()).approve(any(), any(), anyBoolean());
    }

    @Test
    void approveToolAuthorizationStillGrantsOnceWhenNothingWasParked() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        // The hold ended before the user clicked (or there never was one).
        when(toolApprovalGateResolver.resolve("conv-1", "call-9", true)).thenReturn(false);

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rule", "workflow:execute",
                                "remember", false,
                                "toolCallId", "call-9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parkedCallReleased").value(false));

        // Nothing was released, so the resume turn is the only way forward and it needs the
        // grant - without it the very call the user just authorized asks again.
        verify(toolAuthorizationApprovalService).approve("conv-1", "workflow:execute", false);
    }

    @Test
    void approveToolAuthorizationPersistsAlwaysEvenWhenTheParkedCallWasReleased() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(toolApprovalGateResolver.resolve("conv-1", "call-9", true)).thenReturn(true);

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rule", "workflow:execute",
                                "remember", true,
                                "toolCallId", "call-9"))))
                .andExpect(status().isOk());

        // "Always allow" is a standing decision about future turns, not a way to let this
        // one call through - releasing the call must not swallow it.
        verify(toolAuthorizationApprovalService).approve("conv-1", "workflow:execute", true);
    }

    @Test
    void denyToolAuthorizationReleasesTheParkedCallAsRefused() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(toolApprovalGateResolver.resolve("conv-1", "call-9", false)).thenReturn(true);

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/deny", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rule", "workflow:execute",
                                "toolCallId", "call-9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parkedCallReleased").value(true));

        // Refused immediately rather than left parked until the gate's deadline.
        verify(toolApprovalGateResolver).resolve("conv-1", "call-9", false);
    }

    @Test
    void resolveApprovalGateReleasesTheParkedCallForAConnectCard() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));
        when(toolApprovalGateResolver.resolve("conv-1", "call-7", true)).thenReturn(true);

        mockMvc.perform(post("/api/conversations/{conversationId}/approval-gate/resolve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "gateKey", "call-7",
                                "approved", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parkedCallReleased").value(true));

        verify(toolApprovalGateResolver).resolve("conv-1", "call-7", true);
        // Connecting a service grants no rule - there is only a parked call to let through.
        verify(toolAuthorizationApprovalService, never()).approve(any(), any(), anyBoolean());
    }

    @Test
    void resolveApprovalGateDeniesWhenTheCallerDidNotSayYes() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/approval-gate/resolve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("gateKey", "call-7"))))
                .andExpect(status().isOk());

        // An omitted decision is not consent: releasing as approved would run a sensitive
        // action for real on the strength of a field the caller never sent.
        verify(toolApprovalGateResolver).resolve("conv-1", "call-7", false);
    }

    @Test
    void resolveApprovalGateAcceptsTheStringFormOfApproved() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/approval-gate/resolve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gateKey\":\"call-7\",\"approved\":\"true\"}"))
                .andExpect(status().isOk());

        // Fail-closed must not mean "silently deny a client that said yes in a different shape".
        verify(toolApprovalGateResolver).resolve("conv-1", "call-7", true);
    }

    @Test
    void resolveApprovalGateRejectsAMissingGateKey() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/approval-gate/resolve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approved", true))))
                .andExpect(status().isBadRequest());

        verify(toolApprovalGateResolver, never()).resolve(any(), any(), anyBoolean());
    }

    @Test
    void resolveApprovalGateRefusesAnUnauthenticatedCaller() throws Exception {
        mockMvc.perform(post("/api/conversations/{conversationId}/approval-gate/resolve", "conv-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("gateKey", "call-7"))))
                .andExpect(status().isUnauthorized());

        verify(toolApprovalGateResolver, never()).resolve(any(), any(), anyBoolean());
    }

    @Test
    void resolveApprovalGateRefusesAConversationTheCallerCannotSee() throws Exception {
        when(conversationQueryService.getConversationById("conv-1", "user-2", null))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/conversations/{conversationId}/approval-gate/resolve", "conv-1")
                        .header("X-User-ID", "user-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("gateKey", "call-7"))))
                .andExpect(status().isNotFound());

        // A stranger must not be able to release someone else's parked call.
        verify(toolApprovalGateResolver, never()).resolve(any(), any(), anyBoolean());
    }

    @Test
    void approveToolAuthorizationRejectsMissingRule() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/approve", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("remember", true))))
                .andExpect(status().isBadRequest());

        verify(toolAuthorizationApprovalService, never()).approve(any(), any(), anyBoolean());
    }

    @Test
    void denyToolAuthorizationClearsPendingWithoutResuming() throws Exception {
        ConversationDto dto = new ConversationDto();
        dto.setId("conv-1");
        dto.setUserId("user-1");
        when(conversationQueryService.getConversationById("conv-1", "user-1", null))
                .thenReturn(Optional.of(dto));

        mockMvc.perform(post("/api/conversations/{conversationId}/tool-authorization/deny", "conv-1")
                        .header("X-User-ID", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rule", "application:acquire"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.denied").value(true));

        // Deny clears only the named rule's card (rule supplied), leaving siblings pending.
        verify(pendingActionService).clearOnePendingAction("conv-1", "auth:application:acquire");
        verify(toolAuthorizationApprovalService, never()).approve(any(), any(), anyBoolean());
    }

    private String messagePayload() throws Exception {
        Map<String, Object> payload = Map.of(
                "role", "user",
                "content", "hello",
                "model", "model-x",
                "timestamp", "now",
                "toolCalls", "[]"
        );
        return objectMapper.writeValueAsString(payload);
    }
}
