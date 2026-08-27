package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate and the streaming callback both ask this class "is the user being asked for
 * something, and what?". They must get the same answer: if they disagreed, a parked call
 * would either show no card (nothing to click, turn hangs) or two.
 */
@DisplayName("ApprovalCardExtractor - recognising what a tool result asks the user")
class ApprovalCardExtractorTest {

    private final ApprovalCardExtractor extractor = new ApprovalCardExtractor(new ObjectMapper());

    private static ToolResult success(String content, Map<String, Object> metadata) {
        return ToolResult.builder()
                .toolCall(new ToolCall("call-1", "catalog", Map.of("action", "execute"), null))
                .success(true)
                .content(content)
                .metadata(metadata)
                .build();
    }

    @Test
    @DisplayName("serviceApprovalRequested metadata yields a SERVICE card carrying the services")
    void serviceApprovalMetadataYieldsServiceCard() {
        var card = extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "reason", "Credential required for Gmail",
                "services", List.of(Map.of(
                        "serviceType", "gmail",
                        "serviceName", "Gmail",
                        "iconSlug", "gmail")))));

        assertThat(card).isPresent();
        assertThat(card.get().kind()).isEqualTo(ApprovalCardExtractor.ApprovalCard.Kind.SERVICE);
        assertThat(card.get().services()).singleElement()
                .satisfies(s -> assertThat(s).containsEntry("serviceType", "gmail"));
        assertThat(card.get().reason()).isEqualTo("Credential required for Gmail");
        assertThat(card.get().dedupKey()).isEqualTo("svc:connect:gmail");
    }

    @Test
    @DisplayName("Several services share one dedup key, ordered so the SAME set always matches itself")
    void multipleServicesGetAStableSortedKey() {
        var first = extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "services", List.of(Map.of("serviceType", "slack"), Map.of("serviceType", "gmail")))));
        var second = extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "services", List.of(Map.of("serviceType", "gmail"), Map.of("serviceType", "slack")))));

        // The provider decides the order of a tool's services, and it is not stable across
        // calls. An order-sensitive key would make the same request look like two different
        // cards, so the user would be asked to connect the same pair twice in one turn.
        assertThat(first.get().dedupKey()).isEqualTo("svc:connect:gmail,slack");
        assertThat(second.get().dedupKey()).isEqualTo(first.get().dedupKey());
    }

    @Test
    @DisplayName("A DIFFERENT set of services is a different card, not a merge")
    void aDifferentServiceSetIsADifferentCard() {
        var pair = extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "services", List.of(Map.of("serviceType", "gmail"), Map.of("serviceType", "slack")))));
        var single = extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "services", List.of(Map.of("serviceType", "gmail")))));

        // Merging them would drop Slack from the request, and the agent would then be
        // released on a connection it does not have.
        assertThat(single.get().dedupKey()).isNotEqualTo(pair.get().dedupKey());
    }

    @Test
    @DisplayName("needsAttention changes the dedup key so a reconnect card is not merged into a connect card")
    void needsAttentionUsesItsOwnDedupKey() {
        var card = extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "needsAttention", true,
                "services", List.of(Map.of("serviceType", "slack")))));

        assertThat(card).isPresent();
        assertThat(card.get().needsAttention()).isTrue();
        assertThat(card.get().dedupKey()).isEqualTo("svc:attention:slack");
    }

    @Test
    @DisplayName("serviceApprovalRequested with an empty services list asks for nothing")
    void emptyServicesAskForNothing() {
        assertThat(extractor.extract(success("{}", Map.of(
                "serviceApprovalRequested", true,
                "services", List.of())))).isEmpty();
    }

    @Test
    @DisplayName("toolAuthorizationRequired metadata yields an AUTHORIZATION card keyed by rule")
    void authorizationMetadataYieldsAuthorizationCard() {
        var card = extractor.extract(success("{}", Map.of(
                "toolAuthorizationRequired", true,
                "rule", "workflow:execute",
                "toolCallId", "call-1")));

        assertThat(card).isPresent();
        assertThat(card.get().kind()).isEqualTo(ApprovalCardExtractor.ApprovalCard.Kind.AUTHORIZATION);
        assertThat(card.get().dedupKey()).isEqualTo("auth:workflow:execute");
        assertThat(card.get().authorizationMetadata()).containsEntry("toolCallId", "call-1");
    }

    @Test
    @DisplayName("toolAuthorizationRequired without a rule asks for nothing")
    void authorizationWithoutRuleAsksForNothing() {
        assertThat(extractor.extract(success("{}", Map.of("toolAuthorizationRequired", true)))).isEmpty();
    }

    @Test
    @DisplayName("approval_needed JSON in the content yields a SERVICE card (catalog credential pre-flight)")
    void approvalNeededContentYieldsServiceCard() {
        String content = """
                {"status":"approval_needed","serviceType":"notion","serviceName":"Notion",
                 "iconSlug":"notion","toolName":"Search","toolId":"tool-9",
                 "message":"Credential required for Notion"}""";

        var card = extractor.extract(success(content, null));

        assertThat(card).isPresent();
        assertThat(card.get().kind()).isEqualTo(ApprovalCardExtractor.ApprovalCard.Kind.SERVICE);
        assertThat(card.get().services()).singleElement()
                .satisfies(s -> assertThat(s).containsEntry("iconSlug", "notion"));
        assertThat(card.get().reason()).isEqualTo("Credential required for Notion");
        assertThat(card.get().dedupKey()).isEqualTo("svc:connect:notion");
    }

    @Test
    @DisplayName("Content merely mentioning approval_needed without the status field asks for nothing")
    void mentionWithoutStatusAsksForNothing() {
        assertThat(extractor.extract(success("{\"note\":\"approval_needed is a status\"}", null))).isEmpty();
    }

    @Test
    @DisplayName("Malformed JSON content is ignored rather than throwing")
    void malformedContentIsIgnored() {
        assertThat(extractor.extract(success("approval_needed {not json", null))).isEmpty();
    }

    @Test
    @DisplayName("A FAILED result never asks for permission - the gates always signal via success")
    void failedResultNeverAsks() {
        ToolResult failure = ToolResult.builder()
                .toolCall(new ToolCall("call-1", "catalog", Map.of(), null))
                .success(false)
                .error("boom")
                .metadata(Map.of("toolAuthorizationRequired", true, "rule", "catalog:execute"))
                .build();

        assertThat(extractor.extract(failure)).isEmpty();
    }

    @Test
    @DisplayName("An ordinary successful result asks for nothing")
    void ordinaryResultAsksForNothing() {
        assertThat(extractor.extract(success("{\"ok\":true}", Map.of("source", "catalog")))).isEmpty();
    }

    @Test
    @DisplayName("A null result asks for nothing")
    void nullResultAsksForNothing() {
        assertThat(extractor.extract(null)).isEmpty();
    }
}
