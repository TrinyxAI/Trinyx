package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.config.StorageClientConfig;
import com.apimarketplace.auth.service.WorkspaceStorageObjectDeleter;
import com.apimarketplace.storage.client.StorageClient;
import com.apimarketplace.storage.service.file.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceStorageObjectDeleterWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void microserviceContextProvidesExactlyOneHttpDeletionPort() {
        contextRunner
                .withPropertyValues(
                        "deployment.mode=microservice",
                        "services.storage-url=http://storage.test")
                .withUserConfiguration(StorageClientConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(StorageClient.class);
                    assertThat(context).hasSingleBean(WorkspaceStorageObjectDeleter.class);
                    assertThat(context.getBean(WorkspaceStorageObjectDeleter.class))
                            .isNotInstanceOf(MonolithWorkspaceStorageObjectDeleter.class);
                });
    }

    @Test
    void monolithContextProvidesOnlyTheInProcessDeletionPort() {
        FileStorageService fileStorageService = mock(FileStorageService.class);
        when(fileStorageService.delete("tenant-1/report.pdf")).thenReturn(true);

        contextRunner
                .withPropertyValues("deployment.mode=monolith")
                .withBean(FileStorageService.class, () -> fileStorageService)
                .withUserConfiguration(
                        StorageClientConfig.class,
                        MonolithWorkspaceStorageObjectDeleter.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(StorageClient.class);
                    assertThat(context).hasSingleBean(WorkspaceStorageObjectDeleter.class);
                    WorkspaceStorageObjectDeleter deleter =
                            context.getBean(WorkspaceStorageObjectDeleter.class);
                    assertThat(deleter)
                            .isInstanceOf(MonolithWorkspaceStorageObjectDeleter.class);
                    assertThat(deleter.delete(java.util.UUID.randomUUID(), "org-1", "tenant-1", "tenant-1/report.pdf")).isTrue();
                    verify(fileStorageService).delete("tenant-1/report.pdf");

                    assertThat(deleter.delete(java.util.UUID.randomUUID(), "org-1", "tenant-2", "tenant-1/report.pdf")).isFalse();
                    verify(fileStorageService, times(1)).delete("tenant-1/report.pdf");
                });
    }
}
