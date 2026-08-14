package com.apimarketplace.common.storage;

/**
 * The JSON field names of the run-context adoption request, shared by the two sides that speak it.
 *
 * <p>{@code POST /api/internal/storage/adopt-run-context} carries a plain JSON object rather than a
 * typed DTO, because storage-client and storage-service have no module in common beyond this one.
 * Written out by hand on each side, the seven names were a convention held together only by two
 * tests agreeing with each other - and a rename made in one module *and its own test* would still
 * ship, with the other side silently reading a key nobody sends. Naming them once makes the
 * compiler carry the contract instead.</p>
 *
 * <p>The one name worth knowing: the caller's parameter is {@code stepAlias}, the wire calls it
 * {@link #STEP_KEY}, because it lands in the {@code step_key} column.</p>
 */
public final class AdoptRunContextFields {

    /** Storage row ids to adopt (JSON array of UUID strings). */
    public static final String IDS = "ids";
    /** Workflow that produced them. Adoption is a no-op without it. */
    public static final String WORKFLOW_ID = "workflowId";
    /** Run that produced them. */
    public static final String RUN_ID = "runId";
    /** Producing step; the caller knows it as {@code stepAlias}. */
    public static final String STEP_KEY = "stepKey";
    /** Run epoch, already resolved past the engine's 0 sentinel by the caller. */
    public static final String EPOCH = "epoch";
    /** Spawn index within the epoch. */
    public static final String SPAWN = "spawn";
    /** Loop/split item index, or null when not item-scoped. */
    public static final String ITEM_INDEX = "itemIndex";

    /** Response field: how many rows were actually adopted. */
    public static final String ADOPTED = "adopted";

    private AdoptRunContextFields() {
    }
}
