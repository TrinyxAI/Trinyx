package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Request DTO for persisting a generated asset as an interface.
 *
 * <p>Mirrors {@link AgentBrowseInterfaceRequest} so the frontend's
 * visualize-card pipeline can persist + re-fetch the result via
 * {@code interfaceId}. {@code data} carries a generation tool result
 * verbatim, in either producer shape:
 * <ul>
 *   <li>the unified generation tool: {@code {model, kind, provider, file,
 *       billed_quantity, billed_unit, provider_response}}, where {@code file}
 *       is one canonical FileRef;</li>
 *   <li>the legacy image tool: {@code {images: [FileRef...], count, provider,
 *       billing_model, prompt}}.</li>
 * </ul>
 * The receiving service normalizes both, so a sender picks whichever shape its
 * tool already produces and reshapes nothing.
 *
 * <p><b>The name stays, and the fields are only ever added to.</b> Two
 * independently deployed services meet on this class: orchestrator-service
 * serializes it, interface-service deserializes it off
 * {@code POST /api/internal/interfaces/image-generation}. During any rolling
 * deploy one side is older than the other, so renaming the class, the endpoint
 * or a JSON property would drop the results posted by whichever side rolled
 * last. The name is now historical rather than descriptive: it persists a video
 * as readily as an image.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageGenerationInterfaceRequest {

    private String name;
    private String description;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("agent_id")
    private String agentId;

    private Map<String, Object> data;

    /**
     * What was asked for, for a producer whose result does not repeat it.
     *
     * <p>The generation tool's output is what came back (model, kind, provider,
     * file, billed size); the prompt is an input, so it is not in there. It is
     * carried here rather than injected into {@code data} so the payload stays
     * the tool result verbatim. Omitted by the legacy image tool, whose own
     * payload already carries {@code prompt}, and ignored when both are set.
     */
    private String prompt;

    @JsonProperty("organization_id")
    private String organizationId;

    public ImageGenerationInterfaceRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
}
