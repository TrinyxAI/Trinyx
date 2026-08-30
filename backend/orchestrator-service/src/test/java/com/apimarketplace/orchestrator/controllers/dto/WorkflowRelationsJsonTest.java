package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire shape of the relations payload.
 *
 * <p>Records make every public method a candidate property, so a helper written for the SERVICE
 * silently becomes part of the API. {@code isEmpty()} did exactly that: every response carried an
 * {@code "empty": true} field that no client asked for and that would become impossible to remove
 * once one started reading it. Caught by the e2e, pinned here so it cannot come back the next time
 * someone adds a convenience method to one of these records.
 */
class WorkflowRelationsJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("the payload carries parents and children, and nothing else")
    void serialisesExactlyTheTwoDirections() throws Exception {
        WorkflowRelations relations = new WorkflowRelations(
                List.of(WorkflowRelationRef.of("11111111-1111-1111-1111-111111111111", "Caller")),
                List.of());

        Map<String, Object> json = asMap(mapper.writeValueAsString(relations));

        assertThat(json).containsOnlyKeys("parents", "children");
    }

    @Test
    @DisplayName("an empty neighbourhood is two empty lists, with no derived flag alongside them")
    void serialisesAnEmptyNeighbourhoodWithoutHelpers() throws Exception {
        String json = mapper.writeValueAsString(WorkflowRelations.empty());

        assertThat(json).isEqualTo("{\"parents\":[],\"children\":[]}");
    }

    @Test
    @DisplayName("a relation the workspace cannot resolve keeps its id, drops its name, and says so")
    void serialisesAnUnresolvedRef() throws Exception {
        Map<String, Object> json =
                asMap(mapper.writeValueAsString(WorkflowRelationRef.of("22222222-2222-2222-2222-222222222222", null)));

        assertThat(json).containsOnlyKeys("id", "name", "resolved");
        assertThat(json.get("name")).isNull();
        assertThat(json.get("resolved")).isEqualTo(false);
    }

    private Map<String, Object> asMap(String json) throws Exception {
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    }
}
