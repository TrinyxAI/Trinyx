package com.apimarketplace.orchestrator.execution.v2.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a delivery landing on a replica that still holds the SAME split's context from an
 * EARLIER epoch replayed that epoch's items and per-item results.
 *
 * <p>Prod, 2026-08-14, run {@code run_<id>}: epoch 96 spawned 4 mails on pod A;
 * epoch 97 spawned 1 mail on pod B, but the async classify delivery was consumed by pod A, whose
 * in-memory context (key {@code core:each_mail:0}, no epoch in it) was still epoch 96's. The
 * restore saw "already exists" and skipped, so the fan-out ran 4 items and the move node resolved
 * {@code {{core:snapshot.output.uid}}} to epoch 96's item 0 - a mail filed six hours earlier -
 * and failed with "No message with UID 1025 in folder INBOX". Same shape on epochs 80 -> 81.
 */
@DisplayName("SplitContextManager - a context left by an older epoch must not be reused")
class SplitContextStaleEpochRestoreTest {

    private static final String RUN = "run_<id>";
    private static final String SPLIT = "core:each_mail";
    private static final String AGENT = "agent:classify_mail";

    private SplitContextManager podA;

    @BeforeEach
    void setUp() {
        podA = new SplitContextManager();
    }

    /** The resume blob a pending agent / signal carries, as produced for a split delivery. */
    private static Map<String, Object> resumeData(List<Object> items, int subItemIndex) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("splitNodeId", SPLIT);
        data.put("items", items);
        data.put("itemIndex", subItemIndex);
        data.put("workflowItemIndex", 0);
        return data;
    }

    @Nested
    @DisplayName("The prod failure")
    class TheProdFailure {

        @Test
        @DisplayName("a delivery for epoch 97 rebuilds the epoch-96 context this pod still holds")
        void deliveryForNewerEpochRebuildsStaleContext() {
            // Pod A ran epoch 96: 4 mails, and it cached each item's snapshot output.
            podA.createContext(RUN, SPLIT, 0, null, List.of("m1022", "m1023", "m1024", "m1025"), 96);
            podA.storeResults(RUN, SPLIT, 0, "core:snapshot",
                List.of(uid(1025), uid(1024), uid(1023), uid(1022)));

            // Epoch 97 spawned ONE mail on the other pod; its agent delivery lands here.
            podA.restoreContext(RUN, AGENT, resumeData(List.of("m1026"), 0), 97);

            SplitContext ctx = podA.getContext(RUN, SPLIT, 0).orElseThrow();
            assertEquals(1, ctx.itemCount(),
                "the fan-out must size on epoch 97's single mail, not epoch 96's four");
            assertEquals(97, ctx.epoch());
            assertEquals(List.of("m1026"), ctx.items());
            assertTrue(ctx.getAllResults().isEmpty(),
                "epoch 96's per-item results must be gone so the epoch-scoped durable backfill "
                    + "serves epoch 97's values: reading item 0 must not hand back uid 1025, the "
                    + "mail epoch 96 had already moved out of INBOX");
        }
    }

    @Nested
    @DisplayName("What must NOT change")
    class WhatMustNotChange {

        @Test
        @DisplayName("a second delivery of the SAME epoch keeps the live context and its results")
        void sameEpochDeliveryPreservesResults() {
            // 4 items in epoch 96 = 4 deliveries; the first restores, the rest must not clobber.
            podA.createContext(RUN, SPLIT, 0, null, List.of("a", "b", "c", "d"), 96);
            podA.storeResults(RUN, SPLIT, 0, "core:snapshot", List.of(uid(1), uid(2), uid(3), uid(4)));
            SplitContext before = podA.getContext(RUN, SPLIT, 0).orElseThrow();

            podA.restoreContext(RUN, AGENT, resumeData(List.of("a", "b", "c", "d"), 2), 96);

            SplitContext after = podA.getContext(RUN, SPLIT, 0).orElseThrow();
            assertSame(before, after, "same epoch: the live context must be reused, not rebuilt");
            assertEquals(4, after.getResults("core:snapshot").size());
        }

        @Test
        @DisplayName("a LATE delivery for an older epoch leaves the newer context alone")
        void olderEpochDeliveryDoesNotOverwriteNewerContext() {
            podA.createContext(RUN, SPLIT, 0, null, List.of("current"), 97);

            podA.restoreContext(RUN, AGENT, resumeData(List.of("old1", "old2", "old3"), 0), 96);

            SplitContext ctx = podA.getContext(RUN, SPLIT, 0).orElseThrow();
            assertEquals(97, ctx.epoch());
            assertEquals(List.of("current"), ctx.items(),
                "the epoch currently executing must not be overwritten by a late arrival");
        }

        @Test
        @DisplayName("an unknown epoch on either side keeps the pre-fix skip")
        void unknownEpochKeepsLegacySkip() {
            // Legacy creator (no epoch) + epoch-aware delivery.
            podA.createContext(RUN, SPLIT, 0, null, List.of("a", "b"));
            podA.restoreContext(RUN, AGENT, resumeData(List.of("z"), 0), 97);
            assertEquals(2, podA.getContext(RUN, SPLIT, 0).orElseThrow().itemCount());

            // Epoch-aware creator + legacy delivery (3-arg overload).
            SplitContextManager podB = new SplitContextManager();
            podB.createContext(RUN, SPLIT, 0, null, List.of("a", "b"), 96);
            podB.restoreContext(RUN, AGENT, resumeData(List.of("z"), 0));
            assertEquals(2, podB.getContext(RUN, SPLIT, 0).orElseThrow().itemCount());
        }

        @Test
        @DisplayName("a pod with no context at all still restores from the resume data")
        void coldPodStillRestores() {
            podA.restoreContext(RUN, AGENT, resumeData(List.of("m1026"), 0), 97);

            Optional<SplitContext> ctx = podA.getContext(RUN, SPLIT, 0);
            assertTrue(ctx.isPresent(), "the cross-pod restore is what makes a delivery work at all");
            assertEquals(1, ctx.get().itemCount());
            assertEquals(97, ctx.get().epoch());
        }

        @Test
        @DisplayName("empty or malformed resume data is still a no-op")
        void malformedResumeDataIsNoOp() {
            podA.createContext(RUN, SPLIT, 0, null, List.of("a", "b"), 96);

            podA.restoreContext(RUN, AGENT, null, 97);
            podA.restoreContext(RUN, AGENT, Map.of(), 97);
            Map<String, Object> noItems = new LinkedHashMap<>();
            noItems.put("splitNodeId", SPLIT);
            podA.restoreContext(RUN, AGENT, noItems, 97);

            assertEquals(2, podA.getContext(RUN, SPLIT, 0).orElseThrow().itemCount());
        }
    }

    @Nested
    @DisplayName("The epoch stamp itself")
    class TheEpochStamp {

        @Test
        @DisplayName("the stamp survives every per-item result write")
        void epochSurvivesResultWrites() {
            podA.createContext(RUN, SPLIT, 0, null, List.of("a", "b"), 96);

            podA.storeItemResult(RUN, SPLIT, 0, "core:snapshot", 1, 2, uid(7));
            podA.storeResults(RUN, SPLIT, 0, "core:other", List.of(uid(8), uid(9)));

            SplitContext ctx = podA.getContext(RUN, SPLIT, 0).orElseThrow();
            assertEquals(96, ctx.epoch(),
                "a context that loses its stamp while accumulating results reads as 'unknown' "
                    + "and the next epoch reuses it again");
        }

        @Test
        @DisplayName("the stamp survives clearResults and clearAllResults (the rerun paths)")
        void epochSurvivesResultClearing() {
            SplitContext ctx = SplitContext.create(SPLIT, List.of("a", "b"), 96)
                .withResults("core:snapshot", List.of(uid(1), uid(2)));

            assertEquals(96, ctx.clearResults("core:snapshot").epoch());
            assertEquals(96, ctx.clearAllResults().epoch());
        }

        @Test
        @DisplayName("a context built without an epoch is stamped UNKNOWN, which is NOT epoch 0")
        void legacyCreateIsUnknownNotZero() {
            // 0 is a real epoch (AUTO-mode contexts run at epoch 0), so the sentinel must sit
            // outside the valid range or an unattributed context would read as the oldest one
            // and be rebuilt under every delivery.
            assertEquals(SplitContext.UNKNOWN_EPOCH, SplitContext.create(SPLIT, List.of("a")).epoch());
            assertTrue(SplitContext.UNKNOWN_EPOCH < 0);

            SplitContextManager podB = new SplitContextManager();
            podB.createContext(RUN, SPLIT, 0, null, List.of("a", "b"), 0);
            podB.restoreContext(RUN, AGENT, resumeData(List.of("z"), 0), 1);
            assertEquals(1, podB.getContext(RUN, SPLIT, 0).orElseThrow().itemCount(),
                "epoch 0 must behave as a real epoch: epoch 1 supersedes it");
        }

        @Test
        @DisplayName("the epoch-bucket overload stamps the run-level copy it also writes")
        void epochScopedCreateStampsTheRunLevelCopy() {
            // This overload writes BOTH buckets; only the run-level one is ever read, so the
            // stamp has to be on it or the staleness check silently never fires for it.
            podA.createContext(RUN, 96, SPLIT, 0, null, List.of("a", "b"));

            assertEquals(96, podA.getContext(RUN, SPLIT, 0).orElseThrow().epoch());
            assertEquals(96, podA.getContext(RUN, 96, SPLIT, 0, null).orElseThrow().epoch());
        }
    }

    @Nested
    @DisplayName("Documented boundaries (NOT covered - pinned so a later change is deliberate)")
    class DocumentedBoundaries {

        @Test
        @DisplayName("a NESTED scope (key .../sN) is not inspected: the resume blob carries no parent scope")
        void nestedScopeIsNotRebuilt() {
            podA.createContext(RUN, SPLIT, 0, "s1", List.of("old1", "old2", "old3"), 96);

            podA.restoreContext(RUN, AGENT, resumeData(List.of("m1026"), 0), 97);

            assertEquals(3, podA.getContext(RUN, SPLIT, 0, "s1").orElseThrow().itemCount(),
                "the nested scope is untouched - restoreContext only resolves the base key, and "
                    + "findActiveContext prefers the scoped one when a parentScopeKey is in play");
            assertEquals(96, podA.getContext(RUN, SPLIT, 0, "s1").orElseThrow().epoch());

            // The consequence that actually bites: the pod now holds TWO scopes for one split,
            // disagreeing on both epoch and size, and which one a reader gets depends on whether
            // a parentScopeKey is in play. Anyone adding /sN handling must make this pair agree.
            SplitContext base = podA.getContext(RUN, SPLIT, 0).orElseThrow();
            SplitContext scoped = podA.getContext(RUN, SPLIT, 0, "s1").orElseThrow();
            assertEquals(97, base.epoch());
            assertEquals(1, base.itemCount());
            assertNotEquals(base.epoch(), scoped.epoch(), "the two scopes diverge by epoch");
            assertNotEquals(base.itemCount(), scoped.itemCount(), "and by fan-out size");
        }

        @Test
        @DisplayName("the rebuild costs an in-flight older epoch exactly what the split node's own create already costs it")
        void olderInFlightEpochLosesTheSameThingEitherWay() {
            // The javadoc justifies the rebuild by claiming it is not a NEW hazard: the split
            // node's createContext already takes that slot unconditionally. This proves the two
            // paths leave byte-for-byte the same state, so the claim is checkable rather than
            // asserted. All epochs of a run share ONE entry per key (nothing reads runId:epoch).
            SplitContextManager viaRestore = new SplitContextManager();
            SplitContextManager viaSplitNode = new SplitContextManager();
            for (SplitContextManager pod : List.of(viaRestore, viaSplitNode)) {
                pod.createContext(RUN, SPLIT, 0, null, List.of("a", "b", "c", "d"), 96);
                pod.storeResults(RUN, SPLIT, 0, "core:snapshot",
                    List.of(uid(1), uid(2), uid(3), uid(4)));
            }

            // Pod 1: epoch 97 arrives as a delivery. Pod 2: epoch 97's split runs here instead.
            viaRestore.restoreContext(RUN, AGENT, resumeData(List.of("m1026"), 0), 97);
            viaSplitNode.createContext(RUN, SPLIT, 0, null, List.of("m1026"), 97);

            SplitContext restored = viaRestore.getContext(RUN, SPLIT, 0).orElseThrow();
            SplitContext recreated = viaSplitNode.getContext(RUN, SPLIT, 0).orElseThrow();
            assertEquals(recreated.epoch(), restored.epoch());
            assertEquals(recreated.items(), restored.items());
            assertEquals(recreated.getAllResults(), restored.getAllResults());
            assertTrue(restored.getAllResults().isEmpty(),
                "epoch 96's results are gone from memory either way (recoverable from the "
                    + "epoch-scoped durable store); its item list is not");
        }
    }

    private static Map<String, Object> uid(int uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uid", String.valueOf(uid));
        return out;
    }
}
