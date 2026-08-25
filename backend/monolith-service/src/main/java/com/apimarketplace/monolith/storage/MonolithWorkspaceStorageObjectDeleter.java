package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.service.WorkspaceStorageObjectDeleter;
import com.apimarketplace.storage.service.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-process workspace object deletion for monolith mode.
 *
 * <p>The storage internal HTTP endpoint is intentionally absent in this mode. Keep the same
 * tenant/key ownership check as that endpoint before delegating to the shared file service.
 */
@Component
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public final class MonolithWorkspaceStorageObjectDeleter
        implements WorkspaceStorageObjectDeleter {

    private static final Logger logger =
            LoggerFactory.getLogger(MonolithWorkspaceStorageObjectDeleter.class);

    private final FileStorageService fileStorageService;

    public MonolithWorkspaceStorageObjectDeleter(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @Override
    public boolean delete(String tenantId, String key) {
        if (tenantId == null || tenantId.isBlank()
                || key == null || !key.startsWith(tenantId + "/")) {
            logger.warn("Workspace purge refused storage key outside tenant ownership: tenant={} key={}",
                    tenantId, key);
            return false;
        }
        return fileStorageService.delete(key);
    }
}
