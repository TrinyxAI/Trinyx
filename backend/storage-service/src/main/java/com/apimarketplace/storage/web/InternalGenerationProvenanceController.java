package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.GenerationProvenanceFields;
import com.apimarketplace.common.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Record on a generated asset the recipe it was made from.
 *
 * <p>catalog-service dispatches the generation and gets a stored file back; only it knows the
 * model, the prompt and the parameters, and only this service may write to the {@code storage}
 * schema. So the recipe crosses as one POST, once the asset exists. See
 * {@link GenerationProvenanceFields} for the shape and
 * {@link StorageService#stampGenerationProvenance} for the write-once rule.
 *
 * <p><b>Why this is its own class and not a method on {@code InternalFileController}.</b> That
 * controller is {@code @ConditionalOnProperty(deployment.mode=microservice)} and is therefore NOT
 * mounted in the CE monolith, where catalog-service still reaches storage over loopback HTTP
 * ({@code services.storage-url} points the monolith at itself). A method added there would compile,
 * pass its tests, and 404 on every self-hosted install - the exact shape of the CE trap the project
 * guidelines call out for {@code MonolithFileStorageServiceAdapter}. This one has no mode condition,
 * so one mount serves both editions. It depends on nothing but the shared index service, which both
 * editions already wire.
 *
 * <p>Answers {@code {"stamped": n}} and never 4xx: a file that cannot be stamped is skipped. The
 * generation has already run and been charged by the time this is called, so whether its recipe was
 * recorded must not decide whether the caller keeps the asset.
 */
@RestController
@RequestMapping("/api/internal/storage")
public class InternalGenerationProvenanceController {

    private static final Logger logger =
            LoggerFactory.getLogger(InternalGenerationProvenanceController.class);

    /** The {@code storage.storage} indexer. Optional for the same reason as in
     *  {@code InternalFileController}: some test profiles do not wire common-storage-service. */
    @Autowired(required = false)
    private StorageService storageIndexService;

    @PostMapping("/generation-provenance")
    public ResponseEntity<Map<String, Object>> stamp(
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-ID") String tenantId) {

        if (storageIndexService == null) {
            return ResponseEntity.ok(Map.of(GenerationProvenanceFields.STAMPED, 0));
        }
        List<UUID> ids = parseIds(body.get(GenerationProvenanceFields.IDS));
        Map<String, Object> provenance = asMap(body.get(GenerationProvenanceFields.PROVENANCE));
        if (ids.isEmpty() || provenance.isEmpty()) {
            return ResponseEntity.ok(Map.of(GenerationProvenanceFields.STAMPED, 0));
        }
        try {
            int stamped = storageIndexService.stampGenerationProvenance(tenantId, ids, provenance);
            return ResponseEntity.ok(Map.of(GenerationProvenanceFields.STAMPED, stamped));
        } catch (Exception e) {
            logger.warn("generation-provenance failed for tenant {}: {}", tenantId, e.getMessage());
            return ResponseEntity.ok(Map.of(GenerationProvenanceFields.STAMPED, 0));
        }
    }

    /** Ids arrive as JSON strings; anything unparseable is dropped rather than failing the batch. */
    private static List<UUID> parseIds(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            try {
                ids.add(UUID.fromString(item.toString()));
            } catch (IllegalArgumentException ignored) {
                // not a UUID - skip
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }
}
