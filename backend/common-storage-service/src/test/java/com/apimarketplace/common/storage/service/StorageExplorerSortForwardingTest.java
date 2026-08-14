package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.dto.ExplorerSort;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that the CALLER's sort reaches the repository on every listing path.
 *
 * <p>Without this, a hard-coded {@code ExplorerSort.DEFAULT} anywhere in the service would leave
 * the whole feature inert while every other test stayed green: the repository stubs match on
 * {@code any()}, and the SQL tests exercise the repository directly. The deprecated no-sort
 * overloads must equally keep behaving exactly as before, which is what makes them safe for the
 * side-panel explorer and the agent tools that still call them.</p>
 */
@DisplayName("StorageExplorerService - sort forwarding")
class StorageExplorerSortForwardingTest {

    private static final String ORG = "org-1";
    private static final ExplorerSort CHOSEN = new ExplorerSort(ExplorerSort.Key.SIZE, true);

    private StorageExplorerRepository repository;
    private StorageExplorerService service;

    @BeforeEach
    void setUp() {
        repository = mock(StorageExplorerRepository.class);
        service = new StorageExplorerService(repository);
        lenient().when(repository.search(anyString(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());
        lenient().when(repository.listFolderScope(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyBoolean(), anyBoolean(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new StorageExplorerRepository.SliceResult(List.of(), 0));
        lenient().when(repository.listVirtualLeafFiles(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new StorageExplorerRepository.SliceResult(List.of(), 0));
        lenient().when(repository.countChildrenByParent(any(), any(), any())).thenReturn(java.util.Map.of());
        lenient().when(repository.findPreviewFilesByParent(any(), any(), any())).thenReturn(java.util.Map.of());
        lenient().when(repository.latestChildCreatedAtByParent(any(), any(), any())).thenReturn(java.util.Map.of());
        lenient().when(repository.listRootManualFolders(any(), any(), any())).thenReturn(List.of());
        lenient().when(repository.listVirtualGroups(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(repository.previewFilesForVirtualGroups(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(java.util.Map.of());
    }

    private static Pageable page() {
        return PageRequest.of(0, 20);
    }

    @Nested
    @DisplayName("the chosen sort reaches the repository")
    class Forwards {

        @Test
        @DisplayName("on the flat listing")
        void flatListing() {
            service.search("1", ORG, null, null, null, null, null, null, null,
                    true, true, null, List.of(), CHOSEN, page());

            assertThat(capturedFlatSort()).isEqualTo(CHOSEN);
        }

        @Test
        @DisplayName("on the manual folder-scope listing")
        void folderScopeListing() {
            service.searchFolderScope("1", ORG, null, null, null, null, null, null, null, null,
                    true, true, null, List.of(), CHOSEN, page());

            assertThat(capturedFolderScopeSort()).isEqualTo(CHOSEN);
        }

        @Test
        @DisplayName("on a virtual leaf listing - the files inside a run folder")
        void virtualLeafListing() {
            service.searchVirtualScope("1", ORG, VirtualFolderAddress.parse("wf:wf-1/e0/s0/i0"),
                    null, null, null, true, true, null, null, null, List.of(), CHOSEN, page());

            assertThat(capturedLeafSort()).isEqualTo(CHOSEN);
        }

        @Test
        @DisplayName("on the root's loose files")
        void virtualRootLooseFiles() {
            service.searchVirtualScope("1", ORG, null, null, null, null, true, true,
                    null, null, null, List.of(), CHOSEN, page());

            assertThat(capturedLeafSort()).isEqualTo(CHOSEN);
        }
    }

    @Nested
    @DisplayName("the no-sort overloads keep the historical ordering")
    class Defaults {

        @Test
        @DisplayName("flat listing defaults to newest-first")
        void flatDefaults() {
            service.search("1", ORG, null, null, null, null, null, null, null,
                    true, true, null, List.of(), page());

            assertThat(capturedFlatSort()).isEqualTo(ExplorerSort.DEFAULT);
        }

        @Test
        @DisplayName("folder-scope listing defaults to newest-first (the side-panel explorer's order)")
        void folderScopeDefaults() {
            service.searchFolderScope("1", ORG, null, null, null, null, null, null, null, null,
                    true, true, null, List.of(), page());

            assertThat(capturedFolderScopeSort()).isEqualTo(ExplorerSort.DEFAULT);
        }

        @Test
        @DisplayName("virtual listing defaults to newest-first (the agent files tool's order)")
        void virtualDefaults() {
            service.searchVirtualScope("1", ORG, VirtualFolderAddress.parse("wf:wf-1/e0/s0/i0"),
                    null, null, null, true, true, null, List.of(), page());

            assertThat(capturedLeafSort()).isEqualTo(ExplorerSort.DEFAULT);
        }
    }

    private ExplorerSort capturedFlatSort() {
        ArgumentCaptor<ExplorerSort> sort = ArgumentCaptor.forClass(ExplorerSort.class);
        verify(repository).search(anyString(), any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), sort.capture(), any(Pageable.class));
        return sort.getValue();
    }

    private ExplorerSort capturedFolderScopeSort() {
        ArgumentCaptor<ExplorerSort> sort = ArgumentCaptor.forClass(ExplorerSort.class);
        verify(repository).listFolderScope(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyBoolean(), anyBoolean(), any(), any(), sort.capture(), anyInt(), anyInt());
        return sort.getValue();
    }

    private ExplorerSort capturedLeafSort() {
        ArgumentCaptor<ExplorerSort> sort = ArgumentCaptor.forClass(ExplorerSort.class);
        verify(repository).listVirtualLeafFiles(anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), anyBoolean(),
                sort.capture(), anyInt(), anyInt());
        return sort.getValue();
    }
}
