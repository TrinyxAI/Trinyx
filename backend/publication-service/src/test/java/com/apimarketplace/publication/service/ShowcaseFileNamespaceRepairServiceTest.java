package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A published showcase must not depend on files the publisher can delete. These tests
 * pin the reporting contract of the sweep that re-homes them, and the two ways it must
 * refuse to make noise: a publication already self-contained, and one with no snapshot.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShowcaseFileNamespaceRepairService")
class ShowcaseFileNamespaceRepairServiceTest {

    private static final UUID PUB_ID = UUID.fromString("7941574e-8d76-4615-8bfe-d6db91cfd173");

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private WorkflowPublicationService publicationService;
    @Mock private jakarta.persistence.EntityManager entityManager;

    private ShowcaseFileNamespaceRepairService service;

    @BeforeEach
    void setUp() {
        service = new ShowcaseFileNamespaceRepairService(publicationRepository, publicationService,
                new PublicationFileUrlResolver(new com.apimarketplace.common.storage.signing.ShowcaseUrlSigner("test-secret-32-bytes-long-enough-for-hmac")), entityManager);
        // The landing pass reports rather than throws, so a test that does not care about it
        // still needs an answer; "nothing to do" is the neutral one.
        lenient().when(publicationService.materializeLanding(any(), any())).thenReturn(landing(0));
    }

    @Test
    @DisplayName("reports a publication whose files were re-homed, with the count")
    void reportsRepairedPublication() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/clip.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(7);

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("status", "repaired").containsEntry("copied", 7);
    }

    @Test
    @DisplayName("a publication already inside the publication namespace reports 0 and copies nothing - the sweep must be re-runnable")
    void alreadySelfContainedIsANoOp() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("_publications/" + PUB_ID + "/snapshot/clip.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(0);

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("status", "already_self_contained").containsEntry("copied", 0);
    }

    @Test
    @DisplayName("a failing publication is reported and does NOT abort the sweep - the next one may be the page someone is waiting on")
    void oneFailureDoesNotAbortTheSweep() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/clip.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub))
                .thenThrow(new IllegalStateException("storage unreachable"));

        Map<String, Object> row = service.repairById(PUB_ID, false);

        // Labelled by pass, and carrying the count from the pass that DID run, so a partial
        // success is distinguishable from nothing happening.
        assertThat(row).containsEntry("status", "error")
                .containsEntry("error", "showcase: storage unreachable")
                .containsEntry("copied", 0);
    }

    @Test
    @DisplayName("dryRun counts the files still outside the namespace and touches NOTHING")
    void dryRunCountsWithoutCopying() {
        Map<String, Object> snapshot = fileRefSnapshot("1/run/clip.mp4");
        WorkflowPublicationEntity pub = pubWithSnapshot(snapshot);
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        Map<String, Object> row = service.repairById(PUB_ID, true);

        assertThat(row).containsEntry("status", "dry_run").containsEntry("wouldCopy", 1);
        verify(publicationService, never()).repairSnapshotFileNamespace(any());
    }

    @Test
    @DisplayName("dryRun does not count a file already re-homed")
    void dryRunIgnoresAlreadyRehomedFiles() {
        WorkflowPublicationEntity pub = pubWithSnapshot(
                fileRefSnapshot("_publications/" + PUB_ID + "/snapshot/clip.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        Map<String, Object> row = service.repairById(PUB_ID, true);

        assertThat(row).containsEntry("status", "already_self_contained");
        verify(publicationService, never()).repairSnapshotFileNamespace(any());
    }

    @Test
    @DisplayName("a publication with nothing to repair is reported as self-contained, not errored - the two copy passes are asked and both answer 0")
    void publicationWithoutSnapshotIsSkipped() {
        // No early gate on the showcase snapshot: it used to skip the whole publication,
        // which silently excluded every AGENT publication (landing page, no showcase).
        WorkflowPublicationEntity pub = pubWithSnapshot(null);
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(0);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(0));

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("status", "already_self_contained").containsEntry("copied", 0);
    }

    @Test
    @DisplayName("an AGENT publication with NO showcase snapshot but a landing page is still repaired - neither surface may gate the other")
    void agentPublicationWithOnlyALandingIsRepaired() {
        WorkflowPublicationEntity pub = pubWithSnapshot(null);
        pub.setAgentSnapshot(landingSnapshot("1/run/hero.png"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(0);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(3));

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("status", "repaired").containsEntry("copied", 3);
    }

    @Test
    @DisplayName("dryRun counts landing files too, not only the showcase items")
    void dryRunCountsLandingFiles() {
        WorkflowPublicationEntity pub = pubWithSnapshot(null);
        pub.setAgentSnapshot(landingSnapshot("1/run/hero.png"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        Map<String, Object> row = service.repairById(PUB_ID, true);

        assertThat(row).containsEntry("status", "dry_run").containsEntry("wouldCopy", 1);
        verify(publicationService, never()).materializeLanding(any(), any());
    }

    // ========================================================================
    // repairAll - the sweep itself
    // ========================================================================

    @Test
    @DisplayName("the sweep pages through EVERY publication and asks for a SORTED page - an unordered offset window is not stable while the loop updates rows, and shifted rows are skipped, not merely repeated")
    void sweepPagesThroughEveryPublicationInAStableOrder() {
        WorkflowPublicationEntity first = pubWithSnapshot(fileRefSnapshot("1/run/a.mp4"));
        WorkflowPublicationEntity second = pubWithSnapshot(fileRefSnapshot("1/run/b.mp4"));
        second.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        // Each page is built from the pageable the SERVICE passed, exactly as a repository
        // does. The second request therefore comes from nextPageable() on a page derived from
        // production input, so asserting the sort on it tests that the sort survives the hop -
        // which is what keeps the window stable for the whole sweep, not just page one.
        List<WorkflowPublicationEntity> pages = List.of(first, second);
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(publicationRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable requested = invocation.getArgument(0);
            int index = call.getAndIncrement();
            return new PageImpl<>(List.of(pages.get(index)),
                    PageRequest.of(index, 1, requested.getSort()), 2);
        });
        when(publicationService.repairSnapshotFileNamespace(any())).thenReturn(1);

        List<Map<String, Object>> rows = service.repairAll(false);

        assertThat(rows).hasSize(2);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(publicationRepository, times(2)).findAll(pageCaptor.capture());
        assertThat(pageCaptor.getAllValues().get(0).getSort().getOrderFor("id")).isNotNull();
        assertThat(pageCaptor.getAllValues().get(1).getSort().getOrderFor("id")).isNotNull();
        // The page is detached at each boundary: publication-service leaves
        // spring.jpa.open-in-view on, so without this every publication visited stays
        // managed for the whole request with its multi-MB JSONB maps.
        verify(entityManager, times(2)).clear();
    }

    @Test
    @DisplayName("the sweep itself opens no transaction - resolved the way Spring resolves it, because the annotation that matters is on the CLASS and an isAnnotationPresent check on the method is tautologically true")
    void theSweepOpensNoTransactionOfItsOwn() throws NoSuchMethodException {
        org.springframework.transaction.annotation.AnnotationTransactionAttributeSource source =
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();

        assertThat(source.getTransactionAttribute(
                ShowcaseFileNamespaceRepairService.class.getMethod("repairAll", boolean.class),
                ShowcaseFileNamespaceRepairService.class)).isNull();
        assertThat(source.getTransactionAttribute(
                ShowcaseFileNamespaceRepairService.class.getMethod("repairById", UUID.class, boolean.class),
                ShowcaseFileNamespaceRepairService.class)).isNull();
    }

    @Test
    @DisplayName("the per-publication workers SUSPEND any transaction - the class is @Transactional, so merely leaving them unannotated gave each its own transaction whose commit flushed the very entity they claim not to write")
    void theWorkersSuspendTheirCallersTransaction() throws NoSuchMethodException {
        // Three writes per repaired publication, and a partial mutation persisted even when
        // the method reported 0. NOT_SUPPORTED is what makes "the caller saves, once" true.
        org.springframework.transaction.annotation.AnnotationTransactionAttributeSource source =
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();

        for (java.lang.reflect.Method m : List.of(
                WorkflowPublicationService.class.getMethod(
                        "materializeLandingFiles", WorkflowPublicationEntity.class, String.class),
                WorkflowPublicationService.class.getMethod(
                        "materializeLanding", WorkflowPublicationEntity.class, String.class),
                WorkflowPublicationService.class.getMethod(
                        "repairSnapshotFileNamespace", WorkflowPublicationEntity.class))) {
            assertThat(source.getTransactionAttribute(m, WorkflowPublicationService.class))
                    .as("%s must not run in a transaction of its own", m.getName())
                    .isNotNull()
                    .extracting(org.springframework.transaction.TransactionDefinition::getPropagationBehavior)
                    .isEqualTo(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        }
    }

    @Test
    @DisplayName("the sweep saves ONCE, after both passes - neither pass persists, so a publication that needed work and never got saved would silently keep its old paths")
    void theSweepSavesOnceAfterBothPasses() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/a.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(2);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(1));

        assertThat(service.repairById(PUB_ID, false)).containsEntry("copied", 3);

        verify(publicationRepository, times(1)).save(pub);
    }

    @Test
    @DisplayName("a publication whose files the sweep may NOT touch is reported as refused, never as clean - an ORG publication whose run belongs to another member, on a snapshot taken before the capture stated the owner, cannot be repaired and the operator has to see that")
    void filesOutOfScopeAreReportedNotSilentlySkipped() {
        // No _sourceTenantId (legacy snapshot) and a path owned by neither the publisher nor
        // this publication: the copy pass refuses it and leaves it at origin. Reporting
        // "nothing to do" would tell an operator the fleet is repaired while these pages still
        // read live files and will 403 again.
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("7/run/from-a-teammate.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(0);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(0));

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("refused", 1);
        assertThat(row).doesNotContainEntry("status", "already_self_contained");
    }

    @Test
    @DisplayName("a publication whose files are all in scope reports no refusal")
    void inScopeFilesAreNotCountedAsRefused() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/mine.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(1);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(0));

        assertThat(service.repairById(PUB_ID, false)).doesNotContainKey("refused");
    }

    @Test
    @DisplayName("a publication that needed nothing is not saved - a no-op sweep must not bump every row's version")
    void nothingToRepairIsNotSaved() {
        WorkflowPublicationEntity pub = pubWithSnapshot(
                fileRefSnapshot("_publications/" + PUB_ID + "/snapshot/a.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(0);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(0));

        service.repairById(PUB_ID, false);

        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("a failing save is reported rather than swallowed - the row would otherwise be listed as repaired while the DB still holds the old paths")
    void aFailingSaveIsReported() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/a.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(1);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(0));
        when(publicationRepository.save(pub)).thenThrow(new IllegalStateException("version conflict"));

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("status", "error");
        assertThat((String) row.get("error")).startsWith("save:");
    }

    @Test
    @DisplayName("a dry run that blows up on one publication does not abort the fleet sweep")
    void aDryRunFailureIsReportedNotThrown() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/a.mp4"));
        // A subtree the walk cannot read. Whatever the cause, one bad row must not cost the
        // operator the other 999 rows of a fleet-wide dry run.
        pub.getShowcaseSnapshot().put("runState", new LinkedHashMap<String, Object>() {
            @Override
            public java.util.Set<Map.Entry<String, Object>> entrySet() {
                throw new IllegalStateException("unreadable snapshot subtree");
            }
        });
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        Map<String, Object> row = service.repairById(PUB_ID, true);

        assertThat(row).containsEntry("status", "error");
    }

    @Test
    @DisplayName("a publication that throws is reported and the sweep CONTINUES to the next one")
    void sweepContinuesPastAFailure() {
        WorkflowPublicationEntity broken = pubWithSnapshot(fileRefSnapshot("1/run/a.mp4"));
        WorkflowPublicationEntity healthy = pubWithSnapshot(fileRefSnapshot("1/run/b.mp4"));
        healthy.setId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        when(publicationRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(broken, healthy), PageRequest.of(0, 50), 2));

        when(publicationService.repairSnapshotFileNamespace(broken))
                .thenThrow(new IllegalStateException("storage unreachable"));
        when(publicationService.repairSnapshotFileNamespace(healthy)).thenReturn(2);

        List<Map<String, Object>> rows = service.repairAll(false);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("status", "error");
        assertThat((String) rows.get(0).get("error")).startsWith("showcase:");
        assertThat(rows.get(1)).containsEntry("status", "repaired").containsEntry("copied", 2);
    }

    @Test
    @DisplayName("a failure in the showcase pass does NOT skip the landing pass - written as a+b the expression short-circuits on the throw, so half the work was silently never attempted")
    void aShowcaseFailureStillRunsTheLandingPass() {
        WorkflowPublicationEntity pub = pubWithSnapshot(fileRefSnapshot("1/run/a.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub))
                .thenThrow(new IllegalStateException("storage unreachable"));
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(4));

        Map<String, Object> row = service.repairById(PUB_ID, false);

        assertThat(row).containsEntry("status", "error").containsEntry("copied", 4);
        verify(publicationService).materializeLanding(pub, "1");
    }

    @Test
    @DisplayName("publications that need nothing are left OUT of the report - on a large install the response has to stay about what was done")
    void sweepOmitsPublicationsThatNeedNothing() {
        WorkflowPublicationEntity clean = pubWithSnapshot(
                fileRefSnapshot("_publications/" + PUB_ID + "/snapshot/a.mp4"));
        when(publicationRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(clean), PageRequest.of(0, 50), 1));
        when(publicationService.repairSnapshotFileNamespace(clean)).thenReturn(0);
        when(publicationService.materializeLanding(clean, "1")).thenReturn(landing(0));

        assertThat(service.repairAll(false)).isEmpty();
    }

    @Test
    @DisplayName("dryRun does NOT count a file under items[].triggerData - the copy pass skips it (an acquirer upload from another tenant), so counting it would promise work the real run never does")
    void dryRunIgnoresTriggerData() {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        // The PUBLISHER's own path on purpose: with a foreign one the test would pass on an
        // implementation that does walk triggerData, since ownership would refuse it anyway.
        ref.put("path", "1/upload/from-an-acquirer.png");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("triggerData", Map.of("upload", ref));
        Map<String, Object> render = new LinkedHashMap<>();
        render.put("items", List.of(item));
        Map<String, Object> byEpoch = new LinkedHashMap<>();
        byEpoch.put("1", render);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("byEpoch", byEpoch);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceRenders", Map.of("iface", entry));
        WorkflowPublicationEntity pub = pubWithSnapshot(snapshot);
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        assertThat(service.repairById(PUB_ID, true)).containsEntry("status", "already_self_contained");
    }

    /** {@code {landingInterface: {data: {hero: <FileRef>}}}} - what the snapshotter embeds. */
    private static Map<String, Object> landingSnapshot(String path) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("_type", "file");
        ref.put("path", path);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hero", ref);
        Map<String, Object> landing = new LinkedHashMap<>();
        landing.put("data", data);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("landingInterface", landing);
        return snapshot;
    }

    @Test
    @DisplayName("an unknown publication id reports not_found rather than throwing")
    void unknownPublicationIsReported() {
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.empty());

        assertThat(service.repairById(PUB_ID, false)).containsEntry("status", "not_found");
    }

    @Test
    @DisplayName("dryRun counts a node file pill still outside the namespace - the sweep mirrors the copy pass branch for branch, and a branch it does not know reads as clean forever")
    void dryRunCountsStepFiles() {
        // The copy pass moves stepFiles; if the sweep did not count it, an operator would be
        // told the fleet is repaired while every published canvas still hangs its file pills
        // off the publisher's live storage.
        WorkflowPublicationEntity pub = pubWithSnapshot(stepFilesSnapshot("1/run/clip.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        Map<String, Object> row = service.repairById(PUB_ID, true);

        assertThat(row).containsEntry("status", "dry_run").containsEntry("wouldCopy", 1);
    }

    @Test
    @DisplayName("dryRun does not count a node file pill already re-homed - the sweep stays re-runnable")
    void dryRunIgnoresRehomedStepFiles() {
        WorkflowPublicationEntity pub = pubWithSnapshot(
                stepFilesSnapshot("_publications/" + PUB_ID + "/run-outputs/abc/clip.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

        assertThat(service.repairById(PUB_ID, true)).containsEntry("status", "already_self_contained");
    }

    @Test
    @DisplayName("a node file pill owned by a third tenant is reported as refused, not as clean")
    void stepFilesOutOfScopeAreReported() {
        WorkflowPublicationEntity pub = pubWithSnapshot(stepFilesSnapshot("7/run/from-a-teammate.mp4"));
        when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));
        when(publicationService.repairSnapshotFileNamespace(pub)).thenReturn(0);
        when(publicationService.materializeLanding(pub, "1")).thenReturn(landing(0));

        assertThat(service.repairById(PUB_ID, false)).containsEntry("refused", 1);
    }

    /** Snapshot carrying only the canvas node file pills: {@code stepFiles.<epoch>.<alias>}. */
    private static Map<String, Object> stepFilesSnapshot(String path) {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", path);
        Map<String, Object> perAlias = new LinkedHashMap<>();
        perAlias.put("download_file", fileRef);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stepFiles", Map.of("1", perAlias));
        return snapshot;
    }

    private static WorkflowPublicationService.LandingCopyResult landing(int copied) {
        return new WorkflowPublicationService.LandingCopyResult(copied, null);
    }

    private WorkflowPublicationEntity pubWithSnapshot(Map<String, Object> snapshot) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity(
                UUID.randomUUID(), "My App", Map.of(), "1");
        pub.setId(PUB_ID);
        pub.setShowcaseSnapshot(snapshot);
        return pub;
    }

    /** Snapshot shaped like the real one: the FileRef sits under interfaceRenders items[].data. */
    private static Map<String, Object> fileRefSnapshot(String path) {
        Map<String, Object> fileRef = new LinkedHashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", path);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("final_video", fileRef);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("data", data);
        Map<String, Object> render = new LinkedHashMap<>();
        render.put("items", List.of(item));
        Map<String, Object> byEpoch = new LinkedHashMap<>();
        byEpoch.put("1", render);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("byEpoch", byEpoch);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("interfaceRenders", Map.of("iface", entry));
        return snapshot;
    }
}
