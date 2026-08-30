package com.apimarketplace.orchestrator.controllers.dto;

/**
 * One end of a sub-workflow relation, as the UI needs to render it: a row to click.
 *
 * @param id   the related workflow's id - always present, because it comes from a plan the caller
 *             can already read
 * @param name the related workflow's name, or {@code null} when it could not be resolved inside the
 *             caller's workspace (deleted, or living in another workspace). The name is the only
 *             field that carries information the caller might not already hold, so it is the one
 *             that is withheld
 * @param resolved whether {@code name} was resolved. Split out from a null-name test so the client
 *                 never has to infer "unavailable" from a falsy string, and so a row that cannot be
 *                 opened can be rendered as such rather than silently dropped
 */
public record WorkflowRelationRef(String id, String name, boolean resolved) {

    public static WorkflowRelationRef of(String id, String name) {
        return name == null || name.isBlank()
                ? new WorkflowRelationRef(id, null, false)
                : new WorkflowRelationRef(id, name, true);
    }
}
