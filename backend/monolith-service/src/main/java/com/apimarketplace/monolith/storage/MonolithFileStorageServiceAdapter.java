package com.apimarketplace.monolith.storage;

import com.apimarketplace.orchestrator.domain.file.FileRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * In-process bridge from orchestrator file operations to the storage-service
 * file implementation when CE runs as a monolith.
 *
 * <p>Microservice mode uses {@code StorageClientAdapter} over HTTP. In monolith
 * mode the storage internal HTTP controller is not mounted, so this adapter
 * keeps the same orchestrator contract without routing back through localhost.
 */
@Service
@Primary
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithFileStorageServiceAdapter implements com.apimarketplace.orchestrator.services.file.FileStorageService {

    private final com.apimarketplace.storage.service.file.FileStorageService storageFileStorageService;

    /** The {@code storage.storage} indexer, for run-context adoption. Optional: some test
     *  profiles wire the file service without common-storage-service. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.common.storage.service.StorageService storageIndexService;

    public MonolithFileStorageServiceAdapter(
            com.apimarketplace.storage.service.file.FileStorageService storageFileStorageService) {
        this.storageFileStorageService = storageFileStorageService;
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size) {
        return toOrchestratorRef(storageFileStorageService.upload(
                tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size));
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content) {
        return toOrchestratorRef(storageFileStorageService.upload(
                tenantId, workflowId, runId, stepAlias, fileName, mimeType, content));
    }

    /**
     * Context-carrying byte[] upload for WORKFLOW file producers (Download File, Convert To File,
     * Compression, SFTP, interface screenshots, ...). MUST be overridden: the orchestrator
     * {@code FileStorageService} interface ships a DEFAULT epoch-aware overload that silently DROPS
     * the run coordinates and falls back to the no-context {@code upload(...)} (epoch=0, spawn=0,
     * itemIndex=null, sourceType=S3_FILE). Microservice mode dodges this because
     * {@code StorageClientAdapter} overrides the same overload (it forwards the coordinates as
     * {@code ?epoch=&spawn=&itemIndex=&sourceType=} query params). Without this override every
     * monolith (CE) workflow file would land at epoch 0 - so the Files browser shows a single
     * "Epoch 0" folder no matter how many epochs actually ran. Delegate to the storage-service
     * epoch-aware overload (which {@code S3FileStorageService} implements) so the real coordinates
     * reach {@code storage.storage}.
     */
    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content,
                          int epoch, int spawn, Integer itemIndex, String sourceType) {
        return toOrchestratorRef(storageFileStorageService.upload(
                tenantId, workflowId, runId, stepAlias, fileName, mimeType, content,
                epoch, spawn, itemIndex, sourceType));
    }

    /** Context-carrying InputStream upload - mirror of the byte[] variant above (same epoch-loss fix). */
    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size,
                          int epoch, int spawn, Integer itemIndex, String sourceType) {
        return toOrchestratorRef(storageFileStorageService.upload(
                tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size,
                epoch, spawn, itemIndex, sourceType));
    }

    /**
     * File catalog-produced files under the run that produced them. MUST be overridden for the same
     * reason as the epoch-aware uploads above: the orchestrator interface ships a DEFAULT that does
     * nothing, and microservice mode dodges it because {@code StorageClientAdapter} overrides it
     * (posting to the internal HTTP endpoint, which is not mounted in monolith mode). Without this,
     * every CE install would keep showing text-to-speech / image-generation outputs at the ROOT of
     * the Files browser instead of inside their run folder - the exact bug this exists to fix,
     * present in CE only.
     */
    @Override
    public int adoptRunContext(String tenantId, java.util.Collection<String> fileIds,
                               String workflowId, String runId, String stepAlias,
                               int epoch, int spawn, Integer itemIndex) {
        if (storageIndexService == null || fileIds == null || fileIds.isEmpty()) {
            return 0;
        }
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>(fileIds.size());
        for (String id : fileIds) {
            try {
                ids.add(java.util.UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // not a storage row id - skip it rather than fail the batch
            }
        }
        return storageIndexService.adoptRunContext(tenantId, ids, workflowId, runId, stepAlias,
                epoch, spawn, itemIndex);
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        return storageFileStorageService.generateDownloadUrl(key, duration);
    }

    @Override
    public Optional<byte[]> download(String key) {
        return storageFileStorageService.download(key);
    }

    @Override
    public boolean delete(String key) {
        return storageFileStorageService.delete(key);
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        return storageFileStorageService.deleteRunFiles(tenantId, workflowId, runId);
    }

    @Override
    public boolean exists(String key) {
        return storageFileStorageService.exists(key);
    }

    /**
     * Carries the {@code storage.storage} row id across the CE boundary. Dropping it (the 4-arg
     * {@code of}) left every CE workflow FileRef id-less, so anything keyed on the id was inert
     * on self-hosted: {@code FileRefScanner} adopted nothing, and a table asset cell could not
     * build its {@code /api/proxy/files/by-id/{id}/raw} URL.
     */
    private static FileRef toOrchestratorRef(com.apimarketplace.storage.domain.FileRef ref) {
        return FileRef.of(ref.path(), ref.name(), ref.mimeType(), ref.size(), ref.id());
    }
}
