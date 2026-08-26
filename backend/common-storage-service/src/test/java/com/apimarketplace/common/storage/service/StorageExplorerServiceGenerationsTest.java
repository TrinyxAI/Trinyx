package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.dto.GenerationHistoryDto;
import com.apimarketplace.common.storage.dto.GenerationHistoryProjection;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The layer between the query and the browser.
 *
 * <p>Its one real decision is what to do with a row whose recipe cannot be read. Showing it would
 * put a Regenerate button in front of a recipe nobody can replay - a button that quietly makes
 * something else - so the row is DROPPED. What must survive that drop is {@code hasNext}: it
 * describes whether the DATABASE has more matches, and recomputing it from the surviving rows
 * would strand a reader on a page whose successor exists.
 */
@DisplayName("StorageExplorerService.listGenerations")
class StorageExplorerServiceGenerationsTest {

    private static final Pageable PAGE = PageRequest.of(0, 12);

    private StorageExplorerRepository repository;
    private StorageExplorerService service;

    @BeforeEach
    void setUp() {
        repository = mock(StorageExplorerRepository.class);
        service = new StorageExplorerService(repository);
    }

    private static GenerationHistoryProjection projection(String recipeJson) {
        return new GenerationHistoryProjection(
                UUID.randomUUID(), "flux.png", "image/png", 2048,
                Instant.parse("2026-08-24T10:00:00Z"), "1/general/flux.png", recipeJson);
    }

    private void repositoryAnswers(List<GenerationHistoryProjection> rows, boolean hasNext) {
        when(repository.searchGenerations(any(), any(), any(), any()))
                .thenReturn(new SliceImpl<>(rows, PAGE, hasNext));
    }

    @Test
    @DisplayName("maps a row into the entry the browser reads")
    void mapsARow() {
        repositoryAnswers(List.of(projection("{\"model\":\"flux-1.1-pro\",\"prompt\":\"a lighthouse\"}")), false);

        Slice<GenerationHistoryDto> page = service.listGenerations("org-1", null, List.of(), PAGE);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).provenance())
                .containsEntry("model", "flux-1.1-pro")
                .containsEntry("prompt", "a lighthouse");
        assertThat(page.getContent().get(0).fileName()).isEqualTo("flux.png");
    }

    @Test
    @DisplayName("drops a row whose recipe cannot be read")
    void dropsUnreadableRows() {
        // Every shape a recipe can be broken in: malformed JSON, and a column that came back null
        // under a key the query proved exists. Neither may reach a screen that offers to replay it.
        repositoryAnswers(List.of(
                projection("{\"model\":\"flux-1.1-pro\"}"),
                projection("not json at all"),
                projection(null)), false);

        Slice<GenerationHistoryDto> page = service.listGenerations("org-1", null, List.of(), PAGE);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).provenance()).containsEntry("model", "flux-1.1-pro");
    }

    @Test
    @DisplayName("keeps hasNext from the query, even when a row was dropped from the page")
    void keepsHasNextAcrossADrop() {
        // The drop is a rendering decision; whether more matches exist is a fact about the
        // database. Recomputing the second from the first would hide every page after this one.
        repositoryAnswers(List.of(
                projection("{\"model\":\"flux-1.1-pro\"}"),
                projection("not json at all")), true);

        Slice<GenerationHistoryDto> page = service.listGenerations("org-1", null, List.of(), PAGE);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("says there is no next page when the query said so")
    void reportsTheEndOfTheHistory() {
        repositoryAnswers(List.of(projection("{\"model\":\"flux-1.1-pro\"}")), false);

        assertThat(service.listGenerations("org-1", null, List.of(), PAGE).hasNext()).isFalse();
    }

    @Test
    @DisplayName("passes the scope, the format and the deny-list straight through")
    void forwardsEveryFilter() {
        // The service adds no scoping of its own, so a filter it forgets to forward is a filter
        // that silently does not apply - including the member deny-list.
        UUID denied = UUID.randomUUID();
        repositoryAnswers(List.of(), false);

        service.listGenerations("org-1", "voice", List.of(denied), PAGE);

        verify(repository).searchGenerations(eq("org-1"), eq("voice"), eq(List.of(denied)), eq(PAGE));
    }

    @Test
    @DisplayName("answers an empty page rather than null when nothing has been generated")
    void emptyHistory() {
        repositoryAnswers(List.of(), false);

        Slice<GenerationHistoryDto> page = service.listGenerations("org-1", null, List.of(), PAGE);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }
}
