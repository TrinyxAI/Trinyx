package com.apimarketplace.common.storage.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.apimarketplace.common.storage.dto.GenerationHistoryProjection;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The native SQL behind the generation history.
 *
 * <p>Three things in that statement are load-bearing and invisible at runtime if they break.
 *
 * <p><b>The key test must stay written as {@code jsonb_exists(...)}.</b> The equivalent
 * {@code metadata ? 'generation'} operator cannot be written from JPA without a {@code ??} escape
 * that is a JDBC-driver convention rather than a guarantee, and a single {@code ?} is read as a
 * positional parameter - which fails at runtime, on a query no unit test executes against a real
 * database.
 *
 * <p><b>The org scope and the member deny-list must both be there.</b> This is a second way of
 * listing files; either one missing makes it a second way of READING files a member cannot open.
 *
 * <p><b>The format filter reads the RECIPE's kind</b>, not the mime type, which cannot tell a voice
 * from a music track (both are audio/mpeg).
 */
@DisplayName("StorageExplorerRepository.searchGenerations - native SQL")
class StorageExplorerRepositoryGenerationsSqlTest {

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

    private static EntityManager emReturningEmpty() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getResultList()).thenReturn(List.of());
        return em;
    }

    /** An EntityManager whose data query answers with {@code rows}. */
    private static EntityManager emReturningRows(List<Object[]> rows) {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.getResultList()).thenReturn(rows);
        return em;
    }

    private static final UUID ROW_ID = UUID.randomUUID();

    /** One row exactly as the native query hands it back: raw JDBC types, in SELECT order. */
    private static Object[] row() {
        return new Object[] {
            ROW_ID,
            "flux.png",
            "image/png",
            Integer.valueOf(2048),
            Timestamp.from(Instant.parse("2026-08-24T10:00:00Z")),
            "tenant/general/flux.png",
            // jsonb comes back as the driver's own object; only its text matters here.
            new Object() {
                @Override public String toString() { return "{\"model\":\"flux-1.1-pro\"}"; }
            },
        };
    }

    /**
     * The DATA statement the call issued.
     *
     * <p>Picked by what it IS (the paged select) rather than by its position among the captured
     * calls: a test that also asks the stub for its Query mock would otherwise shift the index and
     * silently start asserting against the COUNT statement, which passes for the wrong reason.
     */
    /** Every SQL statement the call issued. */
    private static List<String> allSql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getAllValues();
    }

    private static String dataSql(EntityManager em) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(em, atLeastOnce()).createNativeQuery(sql.capture());
        return sql.getAllValues().stream()
                .filter(statement -> statement.contains("LIMIT :limit"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no paged data query was issued"));
    }

    @Test
    @DisplayName("selects only the files that carry a recipe, by the indexed expression")
    void selectsGeneratedFilesByTheIndexedPredicate() {
        EntityManager em = emReturningEmpty();

        repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 12));

        String sql = dataSql(em);
        assertThat(sql).contains("jsonb_exists(s.metadata, 'generation')");
        // The operator form would be read by JPA as a positional parameter. It must not appear.
        assertThat(sql).doesNotContain("s.metadata ?");
    }

    @Test
    @DisplayName("scopes to the workspace and skips deleted rows and folders")
    void scopesToTheWorkspace() {
        EntityManager em = emReturningEmpty();

        repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 12));

        assertThat(dataSql(em))
                .contains("s.organization_id = :orgId")
                .contains("s.status = 'ACTIVE'")
                .contains("s.is_folder = false");
    }

    @Test
    @DisplayName("applies the member deny-list, like every other way of listing files")
    void appliesTheDenyList() {
        EntityManager em = emReturningEmpty();

        repoWithStubbedEm(em).searchGenerations(
                "org-1", null, List.of(UUID.randomUUID()), PageRequest.of(0, 12));

        assertThat(dataSql(em)).contains("s.id NOT IN (:excludedIds)");
    }

    @Test
    @DisplayName("filters a format on the recipe's own kind, never on the mime type")
    void filtersOnTheRecipeKind() {
        // audio/mpeg is a voice AND a music track. Only the recipe knows which was asked for.
        EntityManager em = emReturningEmpty();

        repoWithStubbedEm(em).searchGenerations("org-1", "voice", List.of(), PageRequest.of(0, 12));

        String sql = dataSql(em);
        assertThat(sql).contains("s.metadata -> 'generation' ->> 'kind' = :kind");
        // The mime type appears in the SELECT list, where it belongs; what must never happen is it
        // appearing in the WHERE, because audio/mpeg is a voice AND a music track.
        assertThat(sql.substring(sql.indexOf("WHERE"))).doesNotContain("mime_type");
    }

    @Test
    @DisplayName("does not filter by format when none is asked for")
    void noFormatFilterByDefault() {
        EntityManager em = emReturningEmpty();

        repoWithStubbedEm(em).searchGenerations("org-1", "  ", List.of(), PageRequest.of(0, 12));

        assertThat(dataSql(em)).doesNotContain(":kind");
    }

    @Test
    @DisplayName("orders by date then id, so a page is deterministic")
    void ordersDeterministically() {
        // Four assets generated in the same call share a created_at to the microsecond. Without the
        // id tie-break the same row can appear on two pages while another appears on none.
        EntityManager em = emReturningRows(List.<Object[]>of(row()));

        repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 12));

        assertThat(dataSql(em)).contains("ORDER BY s.created_at DESC, s.id");
    }

    @Test
    @DisplayName("selects the recipe alongside the file, and pages with LIMIT/OFFSET")
    void selectsTheRecipeAndPages() {
        EntityManager em = emReturningRows(List.<Object[]>of(row()));
        Query q = em.createNativeQuery("probe");

        repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(2, 12));

        assertThat(dataSql(em))
                .contains("s.metadata -> 'generation'")
                .contains("LIMIT :limit OFFSET :offset");
        // 13, not 12: the extra row is the next-page probe (see asksForOnePastThePage). The OFFSET
        // still steps by the PAGE size, or page 2 would start one row late and skip an entry.
        verify(q).setParameter("limit", 13);
        verify(q).setParameter("offset", 24);
    }

    @Test
    @DisplayName("maps a raw driver row onto the projection, in the order it was selected")
    void mapsRowsOntoTheProjection() {
        // The SELECT list and this mapping are two halves of one contract, held together by
        // position alone: a column inserted on one side and not the other is a ClassCastException
        // at runtime, on a query no other test executes.
        EntityManager em = emReturningRows(List.<Object[]>of(row()));

        Slice<GenerationHistoryProjection> page =
                repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 12));

        assertThat(page.getContent()).hasSize(1);
        GenerationHistoryProjection projection = page.getContent().get(0);
        assertThat(projection.id()).isEqualTo(ROW_ID);
        assertThat(projection.fileName()).isEqualTo("flux.png");
        assertThat(projection.mimeType()).isEqualTo("image/png");
        assertThat(projection.sizeBytes()).isEqualTo(2048);
        assertThat(projection.createdAt()).isEqualTo(Instant.parse("2026-08-24T10:00:00Z"));
        assertThat(projection.s3Key()).isEqualTo("tenant/general/flux.png");
        assertThat(projection.provenanceJson()).contains("flux-1.1-pro");
    }

    @Test
    @DisplayName("asks for one row past the page, and answers the pager with it")
    void asksForOnePastThePage() {
        // The extra row IS the "is there a next page" answer. A COUNT would cost a second pass that
        // cannot stop early - every ACTIVE row of the workspace, heap-fetched for the jsonb test,
        // on every page view - to produce one number.
        EntityManager em = emReturningRows(List.<Object[]>of(row(), row(), row()));
        Query q = em.createNativeQuery("probe");

        Slice<GenerationHistoryProjection> page =
                repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 2));

        verify(q).setParameter("limit", 3);
        // The extra row is the SIGNAL, never content: showing it would put a thirteenth entry on a
        // twelve-entry page and skip it on the next one.
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(allSql(em)).noneMatch(statement -> statement.contains("COUNT(*)"));
    }

    @Test
    @DisplayName("says there is no next page when the extra row did not come back")
    void reportsTheEndOfTheHistory() {
        EntityManager em = emReturningRows(List.<Object[]>of(row(), row()));

        Slice<GenerationHistoryProjection> page =
                repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("carries a row whose recipe column came back null, and lets the layer above drop it")
    void toleratesANullRecipeColumn() {
        // jsonb can hold a JSON null under the key. The mapper does not second-guess the database;
        // the service drops such a row, which is where the decision belongs.
        Object[] nullRecipe = row();
        nullRecipe[6] = null;
        EntityManager em = emReturningRows(List.<Object[]>of(nullRecipe));

        Slice<GenerationHistoryProjection> page =
                repoWithStubbedEm(em).searchGenerations("org-1", null, List.of(), PageRequest.of(0, 12));

        assertThat(page.getContent().get(0).provenanceJson()).isNull();
    }

    @Test
    @DisplayName("refuses an unscoped read rather than listing every workspace")
    void refusesWithoutAnOrganization() {
        EntityManager em = emReturningEmpty();

        assertThatThrownBy(() -> repoWithStubbedEm(em)
                .searchGenerations(null, null, List.of(), PageRequest.of(0, 12)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
