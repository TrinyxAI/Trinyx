package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.dto.ExplorerSort;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the ORDER BY the user-chosen sort produces, on the SQL string itself.
 *
 * <p>Two properties are load-bearing and invisible from the service layer. First, the fragment is
 * built from CONSTANTS chosen by a closed enum, so no request text can reach the query - the
 * parser test proves the parser, only this proves the SQL. Second, every branch ends in the
 * {@code s.id} tiebreak, without which offset pagination can show the same row on two pages and
 * skip another entirely.</p>
 *
 * <p>The EntityManager is mocked and the count query answers 0, so each call stops after building
 * its SQL - no database involved.</p>
 */
@DisplayName("StorageExplorerRepository - ORDER BY for the user-chosen sort")
class StorageExplorerRepositorySortSqlTest {

    private static StorageExplorerRepository repoWithStubbedEm(EntityManager em) {
        StorageExplorerRepository repo = new StorageExplorerRepository();
        try {
            Field f = StorageExplorerRepository.class.getDeclaredField("em");
            f.setAccessible(true);
            f.set(repo, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return repo;
    }

    /** Count query answers 1 so the data query (the one carrying ORDER BY) is actually built. */
    private static EntityManager emWithRows() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        when(q.getSingleResult()).thenReturn(1L);
        return em;
    }

    /** The last SQL built - the data query, since the count query is issued first. */
    private static String capturedSql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    private static String orderByOf(String sql) {
        int i = sql.indexOf(" ORDER BY ");
        assertThat(i).as("data query must carry an ORDER BY").isGreaterThan(-1);
        int end = sql.indexOf(" LIMIT ", i);
        return sql.substring(i, end > i ? end : sql.length());
    }

    private static String flatOrderBy(ExplorerSort sort) {
        EntityManager em = emWithRows();
        repoWithStubbedEm(em).search("org-1", null, null, null, null, null, null, null,
                true, true, null, List.of(), sort, PageRequest.of(0, 20));
        return orderByOf(capturedSql(em));
    }

    @Nested
    @DisplayName("per criterion")
    class PerCriterion {

        @Test
        @DisplayName("DATE keeps the historical newest-first ordering")
        void dateDescending() {
            assertThat(flatOrderBy(ExplorerSort.DEFAULT))
                    .isEqualTo(" ORDER BY s.created_at DESC NULLS LAST, s.id");
        }

        @Test
        @DisplayName("NAME orders case-insensitively, then breaks ties by recency")
        void nameAscending() {
            // LOWER(): otherwise Postgres' byte order puts every capitalised name before every
            // lowercase one, which reads as an unsorted list to a user.
            assertThat(flatOrderBy(new ExplorerSort(ExplorerSort.Key.NAME, true)))
                    .isEqualTo(" ORDER BY LOWER(s.file_name) ASC NULLS LAST, s.created_at DESC NULLS LAST, s.id");
        }

        @Test
        @DisplayName("SIZE orders on size_bytes")
        void sizeDescending() {
            assertThat(flatOrderBy(new ExplorerSort(ExplorerSort.Key.SIZE, false)))
                    .isEqualTo(" ORDER BY s.size_bytes DESC NULLS LAST, s.created_at DESC NULLS LAST, s.id");
        }

        @Test
        @DisplayName("TYPE falls back to content_type when mime_type is absent")
        void typeAscending() {
            assertThat(flatOrderBy(new ExplorerSort(ExplorerSort.Key.TYPE, true)))
                    .isEqualTo(" ORDER BY LOWER(COALESCE(s.mime_type, s.content_type)) ASC NULLS LAST, "
                            + "s.created_at DESC NULLS LAST, s.id");
        }
    }

    @Nested
    @DisplayName("invariants that hold for every criterion")
    class Invariants {

        @Test
        @DisplayName("NULLs sort last in BOTH directions, never first")
        void nullsAlwaysLast() {
            // Postgres defaults to NULLS FIRST on DESC: a nameless or sizeless row would otherwise
            // open the list. Folder rows have no size and no mime type at all.
            for (ExplorerSort.Key key : ExplorerSort.Key.values()) {
                for (boolean asc : new boolean[]{true, false}) {
                    assertThat(flatOrderBy(new ExplorerSort(key, asc)))
                            .as("%s %s", key, asc ? "asc" : "desc")
                            .doesNotContain("NULLS FIRST")
                            .contains("NULLS LAST");
                }
            }
        }

        @Test
        @DisplayName("every criterion ends on the s.id tiebreak, so offset paging is deterministic")
        void alwaysTieBreaksOnId() {
            for (ExplorerSort.Key key : ExplorerSort.Key.values()) {
                assertThat(flatOrderBy(new ExplorerSort(key, true)))
                        .as("%s", key)
                        .endsWith(", s.id");
            }
        }

        @Test
        @DisplayName("a null sort is treated as the default rather than producing broken SQL")
        void nullSortDefaults() {
            assertThat(flatOrderBy(null)).isEqualTo(" ORDER BY s.created_at DESC NULLS LAST, s.id");
        }
    }

    @Nested
    @DisplayName("folder-scoped listing")
    class FolderScope {

        private String folderScopeOrderBy(ExplorerSort sort) {
            EntityManager em = emWithRows();
            repoWithStubbedEm(em).listFolderScope("org-1", null, null, null, null, null, null,
                    null, null, true, true, null, List.of(), sort, 20, 0);
            return orderByOf(capturedSql(em));
        }

        @Test
        @DisplayName("folders stay ahead of files whatever the criterion")
        void foldersAlwaysFirst() {
            for (ExplorerSort.Key key : ExplorerSort.Key.values()) {
                assertThat(folderScopeOrderBy(new ExplorerSort(key, true)))
                        .as("%s", key)
                        .startsWith(" ORDER BY s.is_folder DESC, ");
            }
        }

        @Test
        @DisplayName("a DATE sort still orders folders by their last activity, not their own date")
        void dateUsesFolderActivity() {
            // The CASE expression is what makes a folder sort by the date its last child landed.
            assertThat(folderScopeOrderBy(ExplorerSort.DEFAULT))
                    .contains("CASE WHEN s.is_folder THEN COALESCE(")
                    .contains("MAX(c.created_at)");
        }

        @Test
        @DisplayName("a NAME sort orders on the name and keeps last-activity as the tiebreak")
        void nameSortKeepsActivityTieBreak() {
            String orderBy = folderScopeOrderBy(new ExplorerSort(ExplorerSort.Key.NAME, true));
            assertThat(orderBy).contains("LOWER(s.file_name) ASC NULLS LAST");
            assertThat(orderBy).contains("CASE WHEN s.is_folder THEN COALESCE(");
            assertThat(orderBy).endsWith(", s.id");
        }
    }

    @Nested
    @DisplayName("virtual leaf files")
    class VirtualLeafFiles {

        @Test
        @DisplayName("honour the chosen criterion too - a run folder's files sort like any other")
        void leafFilesHonourSort() {
            EntityManager em = emWithRows();
            repoWithStubbedEm(em).listVirtualLeafFiles("org-1", "wf-1", null, 0, 0, null,
                    null, null, null, true, true, null, null, null, List.of(), false,
                    new ExplorerSort(ExplorerSort.Key.SIZE, true), 20, 0);

            assertThat(orderByOf(capturedSql(em)))
                    .isEqualTo(" ORDER BY s.size_bytes ASC NULLS LAST, s.created_at DESC NULLS LAST, s.id");
        }
    }

    @Nested
    @DisplayName("manual folder ancestry (the breadcrumb rebuild)")
    class ManualAncestry {

        private String ancestrySql(java.util.Collection<UUID> excluded) {
            EntityManager em = emWithRows();
            repoWithStubbedEm(em).manualFolderAncestry("org-1", UUID.randomUUID(), excluded);
            return capturedSql(em);
        }

        @Test
        @DisplayName("walks parents root-first and stays inside the org, active folders only")
        void walksParentsRootFirstWithinOrg() {
            String sql = ancestrySql(List.of());
            assertThat(sql).contains("WITH RECURSIVE ancestry");
            assertThat(sql).contains("JOIN ancestry a ON p.id = a.parent_folder_id");
            // Both hops are org-scoped, active and folder-only - a chain must not walk out of the
            // workspace, through a deleted row, or through a file.
            assertThat(sql).contains("s.organization_id = :orgId").contains("p.organization_id = :orgId");
            assertThat(sql).contains("s.status = 'ACTIVE'").contains("p.status = 'ACTIVE'");
            assertThat(sql).contains("s.is_folder = true").contains("p.is_folder = true");
            // depth DESC = the top-level folder first, ending with the folder itself.
            assertThat(sql).endsWith("ORDER BY depth DESC");
        }

        @Test
        @DisplayName("carries a depth backstop so a cycle cannot spin forever")
        void hasDepthBackstop() {
            assertThat(ancestrySql(List.of())).contains("a.depth < 64");
        }

        @Test
        @DisplayName("a restricted folder ends the walk, at BOTH the seed and the parent hop")
        void deniedFolderEndsTheWalk() {
            // Otherwise the breadcrumb would name a folder the listing hides from this member -
            // the name is itself the disclosure.
            String sql = ancestrySql(List.of(UUID.randomUUID()));
            assertThat(sql).contains("s.id NOT IN (:excludedIds)");
            assertThat(sql).contains("p.id NOT IN (:excludedIds)");
        }

        @Test
        @DisplayName("no deny-list means no exclusion clause at all")
        void noDenyListNoClause() {
            assertThat(ancestrySql(List.of())).doesNotContain("excludedIds");
        }
    }
}
