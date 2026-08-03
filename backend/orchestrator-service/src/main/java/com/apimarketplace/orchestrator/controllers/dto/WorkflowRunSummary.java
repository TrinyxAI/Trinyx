package com.apimarketplace.orchestrator.controllers.dto;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Summary DTO for workflow run responses.
 *
 * <p>{@code lastFireAt} is the {@code started_at} of the most recent epoch
 * header in {@code workflow_epochs} for this run (null if no epoch has fired
 * yet). For reusable trigger runs whose {@code startedAt} reflects run birth
 * (often days old), this is the field the run history panel should display
 * as "last execution". Callers without epoch data fall back to {@code startedAt}.
 */
public record WorkflowRunSummary(
    UUID id,
    String runId,
    @JsonIgnore String tenantId,
    RunStatus status,
    String executionMode,
    Instant startedAt,
    Instant endedAt,
    Long durationMs,
    Integer totalNodes,
    Map<String, Object> triggerPayload,
    Map<String, Object> metadata,
    Map<String, Object> plan,
    Integer planVersion,
    Integer currentEpoch,
    Instant lastFireAt,
    /**
     * How long the latest epoch spent EXECUTING, in milliseconds: from its first
     * node starting to its last node finishing. Null when nothing has executed yet.
     *
     * <p>This is what the run history shows for a run that has not terminated.
     * {@code durationMs} above measures the whole run and stays null for a reusable
     * run (which never ends), while the span from {@code startedAt} to a stamped
     * {@code endedAt} measures that run's LIFETIME - days once
     * {@code cancelStaleRuns} closes it - not how long the workflow takes.
     *
     * <p>Not the epoch's own {@code duration_ms} either: that is stamped at CLOSE
     * time, and an epoch closes only when it is reconciled (the next fire, a resume,
     * a restart recovery sweep), which can be hours or days after the last node
     * finished. Prod reported 32h42m for epochs that really execute in seconds.
     *
     * <p>If the latest epoch is still in flight this is the window closed SO FAR,
     * and it grows as the epoch progresses.
     */
    Long lastEpochDurationMs
) {}
