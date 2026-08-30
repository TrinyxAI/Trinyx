package com.apimarketplace.orchestrator.controllers.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * The sub-workflow neighbourhood of ONE workflow.
 *
 * @param parents  workflows whose plan calls this one through a {@code core:sub_workflow} node
 * @param children workflows this one calls through its own {@code core:sub_workflow} nodes
 */
public record WorkflowRelations(List<WorkflowRelationRef> parents, List<WorkflowRelationRef> children) {

    private static final WorkflowRelations EMPTY = new WorkflowRelations(List.of(), List.of());

    public static WorkflowRelations empty() {
        return EMPTY;
    }

    /**
     * True when this workflow has no sub-workflow neighbour at all - nothing for the UI to offer.
     *
     * <p>{@code @JsonIgnore} is load-bearing, not decoration: on a record this reads as the bean
     * getter for a property named {@code empty}, so without it every response carried an
     * {@code "empty": true} field that nothing asked for and no client should start reading. Caught
     * by the e2e, which pins the payload shape exactly.
     */
    @JsonIgnore
    public boolean isEmpty() {
        return parents.isEmpty() && children.isEmpty();
    }
}
