package com.apimarketplace.common.folder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a folder lands in a list sorted by something only its content has. The rule that
 * matters most here is nulls-LAST: an empty folder must sink, and the obvious
 * {@code reversed()} would instead float it to the very top of the page.
 */
@DisplayName("ResourceFolderOrdering - a folder borrows the freshest thing inside it")
class ResourceFolderOrderingTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-01T00:00:00Z");

    private static ResourceFolderDto folder(String name, Instant modified, Instant activity, Long runs) {
        return new ResourceFolderDto(UUID.randomUUID(), name, null, 1, 0,
                modified, activity, runs, List.of(), OLD, OLD);
    }

    @Test
    @DisplayName("maps each list's sort parameter onto a folder ordering")
    void mapsSortParameters() {
        assertThat(ResourceFolderOrdering.keyOf("name")).isEqualTo(ResourceFolderOrdering.Key.NAME);
        assertThat(ResourceFolderOrdering.keyOf("lastExecuted")).isEqualTo(ResourceFolderOrdering.Key.LAST_ACTIVITY);
        assertThat(ResourceFolderOrdering.keyOf("execution")).isEqualTo(ResourceFolderOrdering.Key.LAST_ACTIVITY);
        assertThat(ResourceFolderOrdering.keyOf("runCount")).isEqualTo(ResourceFolderOrdering.Key.ACTIVITY_COUNT);
        assertThat(ResourceFolderOrdering.keyOf("lastModified")).isEqualTo(ResourceFolderOrdering.Key.LAST_MODIFIED);
    }

    @Test
    @DisplayName("an unknown or absent sort falls back to last-modified, like the lists themselves")
    void unknownSortFallsBackToLastModified() {
        assertThat(ResourceFolderOrdering.keyOf(null)).isEqualTo(ResourceFolderOrdering.Key.LAST_MODIFIED);
        assertThat(ResourceFolderOrdering.keyOf("nonsense")).isEqualTo(ResourceFolderOrdering.Key.LAST_MODIFIED);
    }

    @Test
    @DisplayName("last-modified puts the folder holding the freshest change first")
    void ordersByNewestChange() {
        List<ResourceFolderDto> sorted = ResourceFolderOrdering.sort(
                List.of(folder("stale", OLD, null, null), folder("fresh", RECENT, null, null)),
                ResourceFolderOrdering.Key.LAST_MODIFIED);

        assertThat(sorted).extracting(ResourceFolderDto::name).containsExactly("fresh", "stale");
    }

    @Test
    @DisplayName("an empty folder sorts LAST on a time key, not first")
    void emptyFolderSinks() {
        List<ResourceFolderDto> sorted = ResourceFolderOrdering.sort(
                List.of(folder("empty", null, null, null), folder("used", OLD, null, null)),
                ResourceFolderOrdering.Key.LAST_MODIFIED);

        assertThat(sorted).extracting(ResourceFolderDto::name).containsExactly("used", "empty");
    }

    @Test
    @DisplayName("a folder that has never run sorts LAST on the last-executed key")
    void neverRunSinks() {
        List<ResourceFolderDto> sorted = ResourceFolderOrdering.sort(
                List.of(folder("never", OLD, null, null), folder("ran", OLD, RECENT, null)),
                ResourceFolderOrdering.Key.LAST_ACTIVITY);

        assertThat(sorted).extracting(ResourceFolderDto::name).containsExactly("ran", "never");
    }

    @Test
    @DisplayName("run count orders busiest first and counts an unknown count as zero")
    void ordersByActivityCount() {
        List<ResourceFolderDto> sorted = ResourceFolderOrdering.sort(
                List.of(folder("quiet", OLD, OLD, 2L), folder("unknown", OLD, OLD, null),
                        folder("busy", OLD, OLD, 40L)),
                ResourceFolderOrdering.Key.ACTIVITY_COUNT);

        assertThat(sorted).extracting(ResourceFolderDto::name).containsExactly("busy", "quiet", "unknown");
    }

    @Test
    @DisplayName("name ordering ignores case")
    void ordersByNameCaseInsensitively() {
        List<ResourceFolderDto> sorted = ResourceFolderOrdering.sort(
                List.of(folder("beta", OLD, OLD, null), folder("Alpha", OLD, OLD, null)),
                ResourceFolderOrdering.Key.NAME);

        assertThat(sorted).extracting(ResourceFolderDto::name).containsExactly("Alpha", "beta");
    }

    @Test
    @DisplayName("does not mutate the list it was given")
    void doesNotMutateInput() {
        List<ResourceFolderDto> input = List.of(folder("b", OLD, OLD, null), folder("a", RECENT, OLD, null));

        ResourceFolderOrdering.sort(input, ResourceFolderOrdering.Key.NAME);

        assertThat(input).extracting(ResourceFolderDto::name).containsExactly("b", "a");
    }
}
