package com.apimarketplace.publication.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sub-agent count an agent acquisition reports in its install summary.
 *
 * <p>The number must exclude the agent the user knowingly acquired and include every other
 * one that landed in their workspace, exactly once. Two shapes make a naive count wrong:
 * subtracting one for "the root" under-reports when the root's snapshot carries no id (it is
 * then absent from the mapping and the subtraction eats a real sub-agent), and counting raw
 * values over-reports when one cloned agent is recorded under two old keys.
 */
@DisplayName("AgentPublicationService.countSubAgents - agents installed BESIDES the acquired one")
class AgentPublicationServiceInstallSummaryTest {

    private static final UUID ROOT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static Map<String, String> mapping(String... oldNewPairs) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < oldNewPairs.length; i += 2) {
            mapping.put(oldNewPairs[i], oldNewPairs[i + 1]);
        }
        return mapping;
    }

    @Test
    @DisplayName("Excludes the root when the mapping contains it")
    void excludesTheRoot() {
        Map<String, String> agents = mapping(
                "old-root", ROOT.toString(),
                "old-sub-1", UUID.randomUUID().toString(),
                "old-sub-2", UUID.randomUUID().toString());

        assertThat(AgentPublicationService.countSubAgents(agents, ROOT)).isEqualTo(2);
    }

    @Test
    @DisplayName("Counts every sub-agent when the root is ABSENT from the mapping (its snapshot carried no id)")
    void countsAllWhenTheRootIsAbsent() {
        // A blind "-1" would report 1 here: it subtracts a root that was never counted.
        Map<String, String> agents = mapping(
                "old-sub-1", UUID.randomUUID().toString(),
                "old-sub-2", UUID.randomUUID().toString());

        assertThat(AgentPublicationService.countSubAgents(agents, ROOT)).isEqualTo(2);
    }

    @Test
    @DisplayName("Counts a sub-agent ONCE even when it is recorded under two different old keys")
    void countsEachClonedAgentOnce() {
        // The recursion records a sub-agent both under the parent's key and under its own
        // entity id; those come from different sources and need not be identical strings.
        // Counting raw values would tell the user about an agent that does not exist.
        String clonedSubAgent = UUID.randomUUID().toString();
        Map<String, String> agents = mapping(
                "old-root", ROOT.toString(),
                "sub-key-from-parent", clonedSubAgent,
                "sub-key-from-entity", clonedSubAgent);

        assertThat(AgentPublicationService.countSubAgents(agents, ROOT)).isEqualTo(1);
    }

    @Test
    @DisplayName("A lone agent has no sub-agents to report")
    void singleAgentHasNoSubAgents() {
        assertThat(AgentPublicationService.countSubAgents(mapping("old-root", ROOT.toString()), ROOT)).isZero();
        assertThat(AgentPublicationService.countSubAgents(Map.of(), ROOT)).isZero();
        assertThat(AgentPublicationService.countSubAgents(null, ROOT)).isZero();
    }
}
