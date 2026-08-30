package com.apimarketplace.publication.service;

import com.apimarketplace.common.storage.url.FileProxyUrls;
import com.apimarketplace.common.storage.url.StorageKeys;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes already-published showcases self-contained: copies every file they still
 * reference in the publisher's own storage into {@code _publications/{pubId}/}.
 *
 * <p>A share is a snapshot, and a snapshot that reads the publisher's live files is
 * not one: the day the publisher deletes that run's output, the published page loses
 * its media and nothing in the publication can bring it back. The publish path has
 * copied files since {@code copyFileRefsInRunState} landed, but two populations never
 * went through it - publications made before it existed, and any publication whose
 * interface data carried a file reference flattened into a URL string, which the
 * normalizer did not recognise until {@code FileProxyUrls}.
 *
 * <p>Idempotent and re-runnable: a publication already self-contained reports
 * {@code copied: 0} and issues no copy. Meant to be triggered once from the internal
 * admin endpoint after deployment, exactly like
 * {@link ShowcaseSnapshotBackfillService}.
 */
@Service
public class ShowcaseFileNamespaceRepairService {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseFileNamespaceRepairService.class);

    /**
     * Small on purpose. Within a page every publication stays managed (open-in-view keeps one
     * EntityManager for the request), each holding up to three multi-MB JSONB maps plus
     * Hibernate's loaded-state copy of each for dirty checking. Fifty of those is hundreds of
     * megabytes resident on a large fleet; the sweep is not in a hurry.
     */
    private static final int PAGE_SIZE = 10;

    private final WorkflowPublicationRepository publicationRepository;
    private final WorkflowPublicationService publicationService;
    private final PublicationFileUrlResolver urlResolver;

    private final EntityManager entityManager;

    public ShowcaseFileNamespaceRepairService(WorkflowPublicationRepository publicationRepository,
                                              WorkflowPublicationService publicationService,
                                              PublicationFileUrlResolver urlResolver,
                                              EntityManager entityManager) {
        this.publicationRepository = publicationRepository;
        this.publicationService = publicationService;
        this.urlResolver = urlResolver;
        this.entityManager = entityManager;
    }

    /**
     * Repair every publication that carries a showcase snapshot.
     *
     * @param dryRun when true, report what WOULD be copied without copying anything
     * @return one result row per publication that needed work; publications already
     *         self-contained are omitted so the response stays readable on a large install
     */
    public List<Map<String, Object>> repairAll(boolean dryRun) {
        List<Map<String, Object>> results = new ArrayList<>();
        int scanned = 0;
        // Deliberately NOT @Transactional, on two counts. One transaction spanning the whole
        // fleet would hold a pooled connection through thousands of blocking download+upload
        // round trips. And the per-publication methods are themselves @Transactional, so a
        // failure inside one would mark a SHARED transaction rollback-only: catching it here
        // would keep the loop going and then lose every repair already made when the commit
        // finally threw. With no outer transaction each publication commits on its own, so
        // the catch below means what it says.
        //
        // Sorted paging: the loop UPDATEs rows as it goes, and an offset window with no
        // ORDER BY is not stable under those writes - rows shift across the boundary and are
        // silently SKIPPED, which is the one thing a sweep whose value is "nothing left
        // behind" must not do.
        Pageable page = PageRequest.of(0, PAGE_SIZE, Sort.by("id"));
        Page<WorkflowPublicationEntity> batch;
        do {
            batch = publicationRepository.findAll(page);
            for (WorkflowPublicationEntity pub : batch.getContent()) {
                scanned++;
                Map<String, Object> row = repairOne(pub, dryRun);
                if (row != null) results.add(row);
            }
            // Detach the page. This service does not open a transaction, but
            // spring.jpa.open-in-view is on (publication-service never turned it off), so a
            // single EntityManager is bound for the whole request: without this every
            // publication visited stays managed, holding up to three multi-MB JSONB maps,
            // and each per-save flush deep-compares all of them - quadratic, and an OOM on a
            // large fleet. Clearing also removes any question about what a rolled-back
            // publication leaves behind in the shared context.
            entityManager.clear();
            page = batch.nextPageable();
        } while (batch.hasNext());
        log.info("[ShowcaseSnapshot/repair] scanned {} publication(s), {} needed work (dryRun={})",
                scanned, results.size(), dryRun);
        return results;
    }

    /**
     * File references the sweep is not allowed to touch: neither in the publication's own
     * namespace nor owned by anyone this publication may read from. See the note where this
     * is reported.
     */
    private int countRefused(WorkflowPublicationEntity pub) {
        try {
            return countPendingOutOfScope(pub);
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot/repair] could not count out-of-scope files for pub {}: {}",
                    pub.getId(), e.getMessage());
            return 0;
        }
    }

    /** Repair a single publication by id. Always reports, even when nothing was needed. */
    public Map<String, Object> repairById(UUID publicationId, boolean dryRun) {
        return publicationRepository.findById(publicationId)
                .map(pub -> {
                    Map<String, Object> row = repairOne(pub, dryRun);
                    return row != null ? row : selfContained(pub);
                })
                .orElseGet(() -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("publicationId", publicationId.toString());
                    row.put("status", "not_found");
                    return row;
                });
    }

    /** @return a result row when the publication needed work, {@code null} when it did not */
    private Map<String, Object> repairOne(WorkflowPublicationEntity pub, boolean dryRun) {
        if (dryRun) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("publicationId", pub.getId().toString());
            row.put("publisherId", pub.getPublisherId());
            try {
                int pending = countPending(pub);
                if (pending == 0) return null;
                row.put("wouldCopy", pending);
                row.put("status", "dry_run");
            } catch (Exception e) {
                // One malformed snapshot must not abort a fleet-wide dry run, for the same
                // reason the real run reports per publication.
                log.error("[ShowcaseSnapshot/repair] dry run failed for pub {}: {}",
                        pub.getId(), e.getMessage(), e);
                row.put("status", "error");
                row.put("error", e.getMessage());
            }
            return row;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicationId", pub.getId().toString());
        row.put("publisherId", pub.getPublisherId());

        // Both surfaces an anonymous visitor renders: the showcase items and the landing
        // page. An AGENT publication has only the second, so neither may gate the other -
        // and "gate" includes failing. Written as `a() + b()` the whole expression
        // short-circuits on a throw, so a showcase failure silently skipped the landing pass
        // and reported an error that did not say half the work was never attempted.
        int copied = 0;
        List<String> errors = new ArrayList<>(2);
        try {
            copied += publicationService.repairSnapshotFileNamespace(pub);
        } catch (Exception e) {
            errors.add("showcase: " + e.getMessage());
            log.error("[ShowcaseSnapshot/repair] showcase pass failed for pub {}: {}",
                    pub.getId(), e.getMessage(), e);
        }
        // The landing pass reports rather than throws (it must not be able to fail a publish),
        // so its outcome is read, not caught. Treating a silent 0 as success is how a pass
        // that failed every single file used to be reported as nothing to do.
        WorkflowPublicationService.LandingCopyResult landing =
                publicationService.materializeLanding(pub, pub.getPublisherId());
        copied += landing.copied();
        if (landing.failed()) {
            errors.add("landing: " + landing.error());
        }

        int refused = countRefused(pub);
        if (errors.isEmpty() && copied == 0 && refused == 0) return null;
        if (refused > 0) {
            // Out of scope for this publication, so the sweep cannot move them - most often an
            // ORG publication whose showcase run belongs to another member, on a snapshot taken
            // before the capture started stating `_sourceTenantId`. Saying "clean" there would
            // tell an operator the fleet is repaired when those pages still read live files and
            // will 403 again. A re-publish is the remedy, and it has to be visible.
            row.put("refused", refused);
        }

        // ONE save, after both passes. Neither pass persists: two saves means two merges, and
        // the second works from a version the first has already moved past the moment the
        // entity is detached - which is one `open-in-view: false` away from being normal.
        if (copied > 0) {
            try {
                publicationRepository.save(pub);
            } catch (Exception e) {
                errors.add("save: " + e.getMessage());
                log.error("[ShowcaseSnapshot/repair] save failed for pub {}: {}",
                        pub.getId(), e.getMessage(), e);
            }
        }
        row.put("copied", copied);
        if (errors.isEmpty()) {
            row.put("status", "repaired");
        } else {
            // One publication must never abort the sweep: the next one may be the broken
            // page someone is waiting on. A partial success is reported as an error WITH its
            // copied count, so the operator can tell "nothing happened" from "half happened".
            row.put("status", "error");
            row.put("error", String.join("; ", errors));
        }
        return row;
    }

    private Map<String, Object> selfContained(WorkflowPublicationEntity pub) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("publicationId", pub.getId().toString());
        row.put("copied", 0);
        row.put("status", "already_self_contained");
        return row;
    }

    /**
     * What the repair would touch on this publication, across every surface an anonymous
     * visitor renders.
     *
     * <p>Mirrors the copy passes branch for branch instead of walking the whole snapshot, in
     * two respects. It skips {@code items[].triggerData}, which the copy deliberately does
     * not touch (it can carry an acquirer upload from another tenant). And it counts a
     * reference written as a URL STRING only where the copy actually normalizes one -
     * {@code items[].data} and the landing payload - because {@code runState},
     * {@code aggregatedSteps} and {@code stepFiles} are walked for FileRef maps only, so a URL
     * string there would be counted forever and never copied.
     */
    private int countPending(WorkflowPublicationEntity pub) {
        String publisherId = pub.getPublisherId();
        UUID publicationId = pub.getId();
        int total = 0;
        Map<String, Object> showcase = pub.getShowcaseSnapshot();
        if (showcase != null) {
            String runOwner = showcase.get("_sourceTenantId") instanceof String t ? t : null;
            Scope scope = new Scope(publisherId, runOwner, publicationId);
            total += countFileRefs(showcase.get("runState"), scope, false);
            total += countFileRefs(showcase.get("aggregatedSteps"), scope, false);
            // stepFiles is FileRef maps and nothing else, so it counts like runState: no URL
            // strings. It has to be counted at all because the copy pass moves it - a sweep
            // that skipped it would report "clean" on a publication whose node file pills
            // still read the publisher's live storage.
            total += countFileRefs(showcase.get("stepFiles"), scope, false);
            Object renders = showcase.get("interfaceRenders");
            if (renders instanceof Map<?, ?> rendersMap) {
                for (Object entry : rendersMap.values()) {
                    if (!(entry instanceof Map<?, ?> entryMap)) continue;
                    total += countRenderItems(asMap(entryMap).get("defaultRender"), scope);
                    Object byEpoch = asMap(entryMap).get("byEpoch");
                    if (byEpoch instanceof Map<?, ?> epochs) {
                        for (Object perEpoch : epochs.values()) total += countRenderItems(perEpoch, scope);
                    }
                }
            }
        }
        total += countLandingPending(pub.getAgentSnapshot(), false, publisherId, publicationId);
        total += countLandingPending(pub.getPlanSnapshot(),
                pub.getPublicationType() == WorkflowPublicationEntity.PublicationType.INTERFACE,
                publisherId, publicationId);
        return total;
    }

    /**
     * FileRef maps the copy pass would refuse: not already re-homed, and owned by nobody this
     * publication may read from.
     */
    private int countPendingOutOfScope(WorkflowPublicationEntity pub) {
        String publisherId = pub.getPublisherId();
        UUID publicationId = pub.getId();
        Map<String, Object> showcase = pub.getShowcaseSnapshot();
        if (showcase == null) return 0;
        String runOwner = showcase.get("_sourceTenantId") instanceof String t ? t : null;
        Scope scope = new Scope(publisherId, runOwner, publicationId);
        return countOutOfScope(showcase.get("runState"), scope)
                + countOutOfScope(showcase.get("aggregatedSteps"), scope)
                + countOutOfScope(showcase.get("interfaceRenders"), scope)
                + countOutOfScope(showcase.get("stepFiles"), scope);
    }

    private int countOutOfScope(Object node, Scope scope) {
        if (node instanceof Map<?, ?> map) {
            Object type = map.get("_type");
            Object path = map.get("path");
            if ("file".equals(type) && path instanceof String p && !p.isBlank()) {
                return !p.startsWith("_publications/") && !scope.allows(p) ? 1 : 0;
            }
            int total = 0;
            for (Object v : map.values()) total += countOutOfScope(v, scope);
            return total;
        }
        if (node instanceof List<?> list) {
            int total = 0;
            for (Object v : list) total += countOutOfScope(v, scope);
            return total;
        }
        return 0;
    }

    /** Who may own a file here; mirrors the copy pass's own scope. */
    private record Scope(String publisherId, String sourceTenantId, UUID publicationId) {
        String namespace() {
            return publicationId == null ? null : "_publications/" + publicationId + "/";
        }

        /** Mirrors {@code WorkflowPublicationService.CopyScope.allows}. */
        boolean allows(String path) {
            if (!StorageKeys.isWellFormed(path)) return false;
            String ns = namespace();
            if (ns != null && path.startsWith(ns)) return true;
            String owner = StorageKeys.namespaceOf(path);
            return owner != null && (owner.equals(publisherId) || owner.equals(sourceTenantId));
        }
    }

    /** Only {@code items[].data} - the exact scope of {@code walkInterfaceRenderItems}. */
    private int countRenderItems(Object renderEntry, Scope scope) {
        if (!(renderEntry instanceof Map<?, ?> entry)) return 0;
        Object items = asMap(entry).get("items");
        if (!(items instanceof List<?> itemList)) return 0;
        int total = 0;
        for (Object item : itemList) {
            if (item instanceof Map<?, ?> itemMap) {
                total += countFileRefs(asMap(itemMap).get("data"), scope, true);
            }
        }
        return total;
    }

    /**
     * @param dataAtTopLevel an INTERFACE publication has no separate landing interface: the
     *                       resource is the landing, and its payload is {@code plan.data}
     */
    private int countLandingPending(Map<String, Object> snapshot, boolean dataAtTopLevel,
                                     String publisherId, UUID publicationId) {
        if (snapshot == null) return 0;
        if (dataAtTopLevel) {
            return countFileRefs(snapshot.get("data"), new Scope(publisherId, null, publicationId), true);
        }
        Object landing = snapshot.get("landingInterface");
        if (!(landing instanceof Map<?, ?> landingMap)) return 0;
        String landingOwner = asMap(landingMap)
                .get(LandingInterfaceSnapshotter.INTERNAL_SOURCE_TENANT_KEY) instanceof String t ? t : null;
        return countFileRefs(asMap(landingMap).get("data"),
                new Scope(publisherId, landingOwner, publicationId), true);
    }

    /**
     * Count what the repair would re-home in this subtree: a FileRef map whose path is not
     * already under {@code _publications/}, plus - where {@code countUrlStrings} says the copy
     * normalizes them - a URL string the resolver would trust.
     *
     * <p>The string case is asked of {@link PublicationFileUrlResolver} rather than of
     * {@code WorkflowPublicationService}, which is {@code @Transactional}: one call per string
     * node there would begin and commit a JPA transaction for each, hundreds of thousands of
     * them on a fleet-wide dry run, for a pure function of its arguments.
     */
    private int countFileRefs(Object node, Scope scope, boolean countUrlStrings) {
        if (node instanceof String str) {
            if (!countUrlStrings) return 0;
            return urlResolver.wouldRehome(str, scope.publisherId(), scope.sourceTenantId(),
                    scope.namespace()) ? 1 : 0;
        }
        if (node instanceof Map<?, ?> map) {
            Object type = map.get("_type");
            Object path = map.get("path");
            if ("file".equals(type) && path instanceof String p && !p.isBlank()) {
                // Apply the SAME scope the copy applies. Counting every non-namespaced path
                // promised work the real run then refuses, which is the phantom the dry run
                // exists to avoid - and a publisher-authored foreign path would have been
                // reported forever.
                return !p.startsWith("_publications/") && scope.allows(p) ? 1 : 0;
            }
            int total = 0;
            // A JSON-encoded string field is counted at the TOP level of a data map only,
            // because that is the only place copyFileRefsInJsonStrings looks: it iterates
            // entrySet() and does not recurse. Counting one nested deeper would report work
            // the real run never does, and a dry run that never converges is worse than one
            // that under-reports.
            for (Map.Entry<String, Object> e : asMap(map).entrySet()) {
                total += countFileRefs(e.getValue(), scope, countUrlStrings);
                total += countJsonEncodedFileRefs(e.getValue(), scope, countUrlStrings);
            }
            return total;
        }
        if (node instanceof List<?> list) {
            int total = 0;
            for (Object v : list) total += countFileRefs(v, scope, countUrlStrings);
            return total;
        }
        return 0;
    }

    /**
     * What {@code copyFileRefsInJsonStrings} would find: a JSON-encoded string sitting
     * DIRECTLY on a data map, never one nested deeper.
     */
    private int countJsonEncodedFileRefs(Object value, Scope scope, boolean countUrlStrings) {
        if (!countUrlStrings || !(value instanceof String str)) return 0;
        if (!(str.startsWith("[") || str.startsWith("{"))) return 0;
        if (!str.contains("\"_type\":\"file\"") && !str.contains(FileProxyUrls.PATH_MARKER)) return 0;
        try {
            // countUrlStrings stays TRUE inside the parsed JSON: normalizeProxyUrlValue parses
            // a top-level JSON string and normalizeProxyUrlsInParsedJson rewrites URLs at ANY
            // depth inside it, then copyFileRefsInJsonStrings copies what it produced. Passing
            // false made this whole branch incapable of counting anything - and the shape it
            // could not see (a public_link URL inside postsJson) is the one the repair exists
            // for.
            return countFileRefs(publicationService.fileWalkObjectMapper()
                    .readValue(str, Object.class), scope, true);
        } catch (Exception ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}
