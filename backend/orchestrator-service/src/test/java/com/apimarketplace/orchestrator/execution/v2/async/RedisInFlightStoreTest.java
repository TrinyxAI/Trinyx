package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Round-trip tests for {@link RedisInFlightStore} serialization. The store talks to Redis,
 * but the serialization layer is pure Java and can be tested in isolation - these tests
 * pin the schema so a refactor of {@code toInFlightMap} / {@code fromInFlightMap} cannot
 * silently drop a field across the JVM-crash boundary.
 */
class RedisInFlightStoreTest {

    @Test
    @DisplayName("toInFlightMapAndFromInFlightMapRoundTripPreservesAllPendingAgentFieldsPlusResultPayload: schema is stable across the crash boundary")
    void roundTripPreservesAllFields() {
        Instant start = Instant.parse("2026-05-22T21:00:48Z");
        Instant completed = Instant.parse("2026-05-22T21:00:52Z");
        PendingAgent pending = new PendingAgent(
            "cid-1", "run_test", "agent:classify", "Classify", "trigger:cron",
            46, 7, "item-7", "classify", "tenant-1",
            Map.of("subject", "Email 7"), Map.of("input", "data"),
            "conv-1", "stream-1", "exec-1", "deepseek-chat",
            "system prompt", "user prompt", start, "org-1");

        AgentResultMessage result = new AgentResultMessage(
            "cid-1", "run_test", "agent:classify",
            Map.of("selected_category", "urgent"),
            true, null, "classify", completed);

        Map<String, Object> serialized = RedisInFlightStore.toInFlightMap(pending, result);
        RedisInFlightStore.InFlightEntry round = RedisInFlightStore.fromInFlightMap(serialized);

        assertThat(round).isNotNull();
        assertThat(round.pending().correlationId()).isEqualTo("cid-1");
        assertThat(round.pending().runId()).isEqualTo("run_test");
        assertThat(round.pending().nodeId()).isEqualTo("agent:classify");
        assertThat(round.pending().dagTriggerId()).isEqualTo("trigger:cron");
        assertThat(round.pending().epoch()).isEqualTo(46);
        assertThat(round.pending().itemIndex()).isEqualTo(7);
        assertThat(round.pending().organizationId()).isEqualTo("org-1");
        assertThat(round.pending().resolvedInputData()).containsEntry("input", "data");
        assertThat(round.pending().startedAt()).isEqualTo(start);

        assertThat(round.result().correlationId()).isEqualTo("cid-1");
        assertThat(round.result().success()).isTrue();
        assertThat(round.result().result()).containsEntry("selected_category", "urgent");
        assertThat(round.result().completedAt()).isEqualTo(completed);
    }

    @Test
    @DisplayName("roundTripPreservesFailedResultWithErrorMessage: failure-path crash recovery still carries the error context")
    void failedResultRoundTrip() {
        PendingAgent pending = new PendingAgent(
            "cid-fail", "run_x", "agent:classify", "Classify", "trigger:cron",
            1, 0, null, "classify", "tenant-1",
            null, null, null, null, null, "deepseek-chat", null, null,
            Instant.now(), "org-1");

        AgentResultMessage result = new AgentResultMessage(
            "cid-fail", "run_x", "agent:classify", null,
            false, "Rate limit exceeded (429)", "classify", Instant.now());

        Map<String, Object> map = RedisInFlightStore.toInFlightMap(pending, result);
        RedisInFlightStore.InFlightEntry round = RedisInFlightStore.fromInFlightMap(map);

        assertThat(round.result().success()).isFalse();
        assertThat(round.result().errorMessage()).isEqualTo("Rate limit exceeded (429)");
    }

    @Test
    @DisplayName("toInFlightMapJsonSerializesViaJacksonWithoutLossOfTypedFields: the wire format is JSON, the codec is the same Jackson instance used by RedisPendingAgentStore")
    void jacksonRoundTrip() throws Exception {
        PendingAgent pending = new PendingAgent(
            "cid-j", "run", "agent:a", "A", "trigger:t", 0, 0, null, "agent", "t1",
            null, null, null, null, null, "m", null, null, Instant.now(), "o1");
        AgentResultMessage result = new AgentResultMessage(
            "cid-j", "run", "agent:a", Map.of("k", "v"), true, null, "agent", Instant.now());

        Map<String, Object> map = RedisInFlightStore.toInFlightMap(pending, result);
        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(map);
        Map<String, Object> parsed = om.readValue(json, om.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        RedisInFlightStore.InFlightEntry round = RedisInFlightStore.fromInFlightMap(parsed);

        assertThat(round.pending().correlationId()).isEqualTo("cid-j");
        assertThat(round.result().result()).containsEntry("k", "v");
    }

    // ── hasOtherInFlightForEpoch: the async-drain guard against premature epoch close ──
    // The filter over listAll() is what stops the first-delivered fork-branch agent from
    // pruning the epoch while siblings are consumed-but-not-yet-delivered (which stranded
    // a downstream merge). listAll() talks to Redis, so it is stubbed via a spy here.

    private static RedisInFlightStore.InFlightEntry entry(
            String correlationId, String runId, String dagTriggerId, int epoch) {
        PendingAgent p = new PendingAgent(
            correlationId, runId, "agent:x", "X", dagTriggerId, epoch, 0, "0", "agent",
            "t1", null, null, null, null, null, "deepseek-chat", null, null, Instant.now(), "o1");
        AgentResultMessage r = new AgentResultMessage(
            correlationId, runId, "agent:x", Map.of(), true, null, "agent", Instant.now());
        return new RedisInFlightStore.InFlightEntry(p, r);
    }

    /**
     * Both guards read through the per-run index rather than {@code listAll()}: a keyspace SCAN
     * on the epoch-close path of every reusable trigger, inside a transaction holding an advisory
     * lock and a row lock, is not acceptable. Stubbing {@code listForRun} keeps these cases about
     * the FILTER (epoch / trigger / exclude) they exist to pin; the index read itself, including
     * stale-member pruning, is covered separately below.
     */
    private static RedisInFlightStore storeReturning(List<RedisInFlightStore.InFlightEntry> staged) {
        RedisInFlightStore store = spy(new RedisInFlightStore(mock(StringRedisTemplate.class), new ObjectMapper()));
        doReturn(staged).when(store).listForRun(org.mockito.ArgumentMatchers.anyString());
        return store;
    }

    @Test
    @DisplayName("hasOtherInFlightForEpochSeesSiblingConsumedButNotDelivered: a staged sibling in the same epoch (excluding self) is detected")
    void hasOtherDetectsSibling() {
        RedisInFlightStore store = storeReturning(List.of(
            entry("cid-self", "run-1", "trigger:ask", 1),
            entry("cid-sibling", "run-1", "trigger:ask", 1)));

        assertThat(store.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "cid-self")).isTrue();
    }

    @Test
    @DisplayName("hasOtherInFlightForEpochExcludesSelf: the last delivery, whose only staged entry is its own, reports drained")
    void hasOtherExcludesSelf() {
        RedisInFlightStore store = storeReturning(List.of(
            entry("cid-self", "run-1", "trigger:ask", 1)));

        assertThat(store.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "cid-self")).isFalse();
    }

    @Test
    @DisplayName("hasOtherInFlightForEpochScopesByRunTriggerEpoch: staged entries from other runs/triggers/epochs do not block this epoch's reset")
    void hasOtherScopesStrictly() {
        RedisInFlightStore store = storeReturning(List.of(
            entry("cid-otherrun", "run-2", "trigger:ask", 1),
            entry("cid-othertrigger", "run-1", "trigger:other", 1),
            entry("cid-otherepoch", "run-1", "trigger:ask", 2)));

        assertThat(store.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, "cid-self")).isFalse();
    }

    @Test
    @DisplayName("hasOtherInFlightForEpochWithNullExcludeMatchesAnyEntryInScope: recovery-style callers without a self entry")
    void hasOtherNullExcludeMatchesAll() {
        RedisInFlightStore store = storeReturning(List.of(
            entry("cid-a", "run-1", "trigger:ask", 1)));

        assertThat(store.hasOtherInFlightForEpoch("run-1", "trigger:ask", 1, null)).isTrue();
    }

    // ── tryClaimReplay / releaseReplayClaim (cross-pod replay claim barrier) ─────

    @Test
    @DisplayName("firstReplicaWinsTheReplayClaimSecondReplicaIsRefused")
    void replayClaimSingleWinner() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(template.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.when(ops.setIfAbsent(
                org.mockito.ArgumentMatchers.eq(RedisInFlightStore.REPLAY_CLAIM_PREFIX + "cid-1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(java.util.concurrent.TimeUnit.class)))
            .thenReturn(Boolean.TRUE, Boolean.FALSE);
        RedisInFlightStore store = new RedisInFlightStore(template, new ObjectMapper());

        assertThat(store.tryClaimReplay("cid-1")).isTrue();
        assertThat(store.tryClaimReplay("cid-1")).isFalse();
    }

    // ── hasAnyInFlightForRun: the run-wide counterpart, for the reset guard ──
    // ReusableTriggerService.hasActiveSignalsForTrigger has a legacy path that holds no
    // (triggerId, epoch) to scope by, and the registry it used to consult alone is empty for
    // the whole of a delivery. Without this lookup that path resets the epoch mid-delivery and
    // the node downstream of the agent is never dispatched.

    @Test
    @DisplayName("hasAnyInFlightForRunSeesAnEntryWhateverItsTriggerOrEpoch: the run-wide guard is not scoped by epoch")
    void hasAnyInFlightForRunIgnoresTriggerAndEpoch() {
        RedisInFlightStore store = storeReturning(List.of(
            entry("cid-other-epoch", "run-1", "trigger:ask", 7)));

        assertThat(store.hasAnyInFlightForRun("run-1"))
            .as("the caller has no epoch to compare, so ANY in-flight agent for the run blocks the reset")
            .isTrue();
    }

    // ── listForRun: the index read itself, against a stubbed Redis ──
    // This is where run-scoping and stale-member pruning actually live now, so they are pinned
    // against the real code path rather than through a stub.

    private static RedisInFlightStore storeOverIndex(
            java.util.Map<String, java.util.Set<String>> index,
            java.util.Map<String, String> values) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(template.opsForSet()).thenReturn(setOps);
        org.mockito.Mockito.when(template.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(setOps.members(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> index.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.when(valueOps.get(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        return new RedisInFlightStore(template, new ObjectMapper());
    }

    private static String stagedJson(String correlationId, String runId, String dagTriggerId, int epoch)
            throws Exception {
        RedisInFlightStore.InFlightEntry e = entry(correlationId, runId, dagTriggerId, epoch);
        return new ObjectMapper().writeValueAsString(
            RedisInFlightStore.toInFlightMap(e.pending(), e.result()));
    }

    @Test
    @DisplayName("listForRunReadsOnlyThisRunsIndex: a concurrent run's agent must not freeze this run's cycle")
    void listForRunIsScopedToTheRun() throws Exception {
        RedisInFlightStore store = storeOverIndex(
            java.util.Map.of(RedisInFlightStore.RUN_INDEX_PREFIX + "run-2", java.util.Set.of("cid-elsewhere")),
            java.util.Map.of(RedisInFlightStore.KEY_PREFIX + "cid-elsewhere",
                stagedJson("cid-elsewhere", "run-2", "trigger:ask", 1)));

        assertThat(store.hasAnyInFlightForRun("run-1"))
            .as("run-1's index is empty, so run-2's in-flight agent is invisible here")
            .isFalse();
        assertThat(store.hasAnyInFlightForRun("run-2")).isTrue();
    }

    @Test
    @DisplayName("listForRunPrunesAMemberWhoseValueIsGone: a stale index entry must never freeze a run forever")
    void listForRunPrunesStaleMembers() {
        // The value was deleted (or expired) but the index member survived - e.g. clear()
        // succeeded and nothing removed the member. If the guard trusted membership alone, this
        // run's epoch could never reset again.
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(template.opsForSet()).thenReturn(setOps);
        org.mockito.Mockito.when(template.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(setOps.members(RedisInFlightStore.RUN_INDEX_PREFIX + "run-1"))
            .thenReturn(java.util.Set.of("cid-stale"));
        org.mockito.Mockito.when(valueOps.get(RedisInFlightStore.KEY_PREFIX + "cid-stale")).thenReturn(null);

        RedisInFlightStore store = new RedisInFlightStore(template, new ObjectMapper());

        assertThat(store.hasAnyInFlightForRun("run-1"))
            .as("a member that resolves to nothing is not an in-flight agent")
            .isFalse();
        // Verified on the mock rather than through an Answer: SetOperations.remove is varargs,
        // and a single-value matcher does not bind the vararg array reliably.
        org.mockito.Mockito.verify(setOps)
            .remove(RedisInFlightStore.RUN_INDEX_PREFIX + "run-1", "cid-stale");
    }

    // ── stage(): the WRITE half of the index, which every per-run guard depends on ──
    // A typo here (wrong key, wrong member, no index at all) leaves the readers permanently
    // blind while every read-side test stays green, so the write is pinned explicitly.

    @Test
    @DisplayName("stageIndexesTheEntryUnderItsRunBeforeWritingTheValue: index first, so a value is never queryable without being findable")
    void stageWritesIndexBeforeValue() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(template.opsForSet()).thenReturn(setOps);
        org.mockito.Mockito.when(template.opsForValue()).thenReturn(valueOps);

        RedisInFlightStore.InFlightEntry e = entry("cid-w", "run-w", "trigger:ask", 3);
        new RedisInFlightStore(template, new ObjectMapper()).stage(e.pending(), e.result());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(setOps, valueOps);
        order.verify(setOps).add(RedisInFlightStore.RUN_INDEX_PREFIX + "run-w", "cid-w");
        order.verify(valueOps).set(
            org.mockito.ArgumentMatchers.eq(RedisInFlightStore.KEY_PREFIX + "cid-w"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any());
        // The index must expire with the entries it points at; an index outliving them would
        // keep a run's epoch resets deferred long after every agent finished.
        org.mockito.Mockito.verify(template).expire(
            org.mockito.ArgumentMatchers.eq(RedisInFlightStore.RUN_INDEX_PREFIX + "run-w"),
            org.mockito.ArgumentMatchers.eq(RedisInFlightStore.DEFAULT_TTL.toMillis()),
            org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("stageStillWritesTheValueWhenTheIndexWriteFails: recovery keeps working, but the guards are told they went blind")
    void stageWritesValueEvenIfIndexFails() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(template.opsForSet()).thenReturn(setOps);
        org.mockito.Mockito.when(template.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(setOps.add(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>any()))
            .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        RedisInFlightStore.InFlightEntry e = entry("cid-noidx", "run-noidx", "trigger:ask", 1);
        new RedisInFlightStore(template, new ObjectMapper()).stage(e.pending(), e.result());

        // The value is still written: AgentRecoveryService.replayInFlightEntries scans the value
        // namespace directly, so crash recovery must not be sacrificed for the guards.
        org.mockito.Mockito.verify(valueOps).set(
            org.mockito.ArgumentMatchers.eq(RedisInFlightStore.KEY_PREFIX + "cid-noidx"),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any());
        // Retried once before giving up - the caller cannot come back later.
        org.mockito.Mockito.verify(setOps, org.mockito.Mockito.times(2))
            .add(RedisInFlightStore.RUN_INDEX_PREFIX + "run-noidx", "cid-noidx");
    }

    @Test
    @DisplayName("stageThenLookUpRoundTrip: a staged entry is visible to the guard that reads the index")
    void stageThenGuardSeesIt() {
        java.util.Map<String, java.util.Set<String>> index = new java.util.HashMap<>();
        java.util.Map<String, String> values = new java.util.HashMap<>();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.SetOperations<String, String> setOps =
            mock(org.springframework.data.redis.core.SetOperations.class);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
            mock(org.springframework.data.redis.core.ValueOperations.class);
        org.mockito.Mockito.when(template.opsForSet()).thenReturn(setOps);
        org.mockito.Mockito.when(template.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.when(setOps.add(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<String>any()))
            .thenAnswer(inv -> {
                index.computeIfAbsent(inv.getArgument(0), k -> new java.util.HashSet<>())
                     .add(inv.getArgument(1));
                return 1L;
            });
        org.mockito.Mockito.doAnswer(inv -> { values.put(inv.getArgument(0), inv.getArgument(1)); return null; })
            .when(valueOps).set(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(setOps.members(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> index.get(inv.<String>getArgument(0)));
        org.mockito.Mockito.when(valueOps.get(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> values.get(inv.<String>getArgument(0)));

        RedisInFlightStore store = new RedisInFlightStore(template, new ObjectMapper());
        RedisInFlightStore.InFlightEntry e = entry("cid-rt", "run-rt", "trigger:ask", 2);
        store.stage(e.pending(), e.result());

        assertThat(store.hasOtherInFlightForEpoch("run-rt", "trigger:ask", 2, null))
            .as("what stage() writes must be what the reset guard reads - the whole fix depends on it")
            .isTrue();
        assertThat(store.hasAnyInFlightForRun("run-rt")).isTrue();
        assertThat(store.hasOtherInFlightForEpoch("run-rt", "trigger:ask", 2, "cid-rt"))
            .as("and the entry must be exemptable by its own delivery")
            .isFalse();
    }

    @Test
    @DisplayName("indexPrefixCannotCollideWithTheValueNamespace: listAll's SCAN would WRONGTYPE on a set key")
    void indexPrefixDoesNotCollideWithValuePrefix() {
        // listAll() runs SCAN MATCH KEY_PREFIX + "*" and GETs every hit. If the index prefix ever
        // matched that pattern, every recovery tick would hit WRONGTYPE on a set key.
        assertThat(RedisInFlightStore.RUN_INDEX_PREFIX).doesNotStartWith(RedisInFlightStore.KEY_PREFIX);
        assertThat(RedisInFlightStore.KEY_PREFIX).doesNotStartWith(RedisInFlightStore.RUN_INDEX_PREFIX);
    }

    @Test
    @DisplayName("listForRunFailsOpenWhenRedisIsDown: a hiccup restores the prior behaviour instead of freezing the cycle")
    void listForRunFailsOpen() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(template.opsForSet())
            .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        RedisInFlightStore store = new RedisInFlightStore(template, new ObjectMapper());

        // Deliberate asymmetry, and it is the safer of the two: an unreachable Redis re-exposes
        // the original race for the duration of the outage, whereas failing closed would defer
        // every epoch reset on the deployment for as long as Redis is down.
        assertThat(store.hasAnyInFlightForRun("run-1")).isFalse();
    }

    @Test
    @DisplayName("hasAnyInFlightForRunIsFalseWhenNothingIsStaged")
    void hasAnyInFlightForRunEmpty() {
        RedisInFlightStore store = storeReturning(List.of());

        assertThat(store.hasAnyInFlightForRun("run-1")).isFalse();
    }

    @Test
    @DisplayName("hasAnyInFlightForRunTreatsANullRunIdAsNothingInFlight")
    void hasAnyInFlightForRunNullRunId() {
        RedisInFlightStore store = storeReturning(List.of(
            entry("cid-1", "run-1", "trigger:ask", 1)));

        assertThat(store.hasAnyInFlightForRun(null)).isFalse();
    }

    @Test
    @DisplayName("replayClaimFailsOpenOnRedisErrorSoAtLeastOnceDeliveryIsPreserved")
    void replayClaimFailsOpen() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        org.mockito.Mockito.when(template.opsForValue())
            .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        RedisInFlightStore store = new RedisInFlightStore(template, new ObjectMapper());

        assertThat(store.tryClaimReplay("cid-1")).isTrue();
    }

    @Test
    @DisplayName("releaseReplayClaimDeletesTheMarkerAndSwallowsRedisErrors")
    void releaseReplayClaimDeletesMarker() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        RedisInFlightStore store = new RedisInFlightStore(template, new ObjectMapper());

        store.releaseReplayClaim("cid-1");
        org.mockito.Mockito.verify(template).delete(RedisInFlightStore.REPLAY_CLAIM_PREFIX + "cid-1");

        org.mockito.Mockito.when(template.delete(org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        store.releaseReplayClaim("cid-1"); // must not throw
    }
}
