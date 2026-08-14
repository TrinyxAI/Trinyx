package com.apimarketplace.common.storage.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ExplorerSort#parse}.
 *
 * <p>This is the boundary that decides which CONSTANT ORDER BY fragment the repository picks, so
 * two properties matter beyond the happy path: unrecognised input must degrade to a listing rather
 * than fail the request (a bookmark from an older build must still show files), and a criterion
 * chosen without a direction must read the way a user expects it to.</p>
 */
@DisplayName("ExplorerSort.parse")
class ExplorerSortTest {

    @Nested
    @DisplayName("recognised input")
    class Recognised {

        @Test
        @DisplayName("keeps an explicit key and direction")
        void keepsExplicitKeyAndDirection() {
            assertThat(ExplorerSort.parse("size", "asc"))
                    .isEqualTo(new ExplorerSort(ExplorerSort.Key.SIZE, true));
            assertThat(ExplorerSort.parse("name", "desc"))
                    .isEqualTo(new ExplorerSort(ExplorerSort.Key.NAME, false));
        }

        @Test
        @DisplayName("is case- and whitespace-insensitive")
        void isCaseInsensitive() {
            assertThat(ExplorerSort.parse("  TYPE ", " DESC "))
                    .isEqualTo(new ExplorerSort(ExplorerSort.Key.TYPE, false));
        }
    }

    @Nested
    @DisplayName("direction left unspecified")
    class NaturalDirection {

        @Test
        @DisplayName("text criteria read A→Z")
        void textCriteriaAscend() {
            // "Sort by name" that came back Z→A would look broken, so the fallback is not a
            // blanket descending.
            assertThat(ExplorerSort.parse("name", null).ascending()).isTrue();
            assertThat(ExplorerSort.parse("type", "").ascending()).isTrue();
        }

        @Test
        @DisplayName("date and size read newest / biggest first")
        void quantitativeCriteriaDescend() {
            assertThat(ExplorerSort.parse("date", null).ascending()).isFalse();
            assertThat(ExplorerSort.parse("size", null).ascending()).isFalse();
        }
    }

    @Nested
    @DisplayName("unrecognised input degrades instead of failing")
    class Degrades {

        @Test
        @DisplayName("an unknown key falls back to the default sort")
        void unknownKeyFallsBack() {
            // A criterion that no longer exists must still list files.
            assertThat(ExplorerSort.parse("colour", null)).isEqualTo(ExplorerSort.DEFAULT);
        }

        @Test
        @DisplayName("an unknown direction falls back to the key's natural one")
        void unknownDirectionFallsBack() {
            assertThat(ExplorerSort.parse("name", "sideways").ascending()).isTrue();
            assertThat(ExplorerSort.parse("date", "sideways").ascending()).isFalse();
        }

        @Test
        @DisplayName("both params absent give the historical listing: newest first")
        void absentParamsGiveDefault() {
            assertThat(ExplorerSort.parse(null, null)).isEqualTo(ExplorerSort.DEFAULT);
            assertThat(ExplorerSort.DEFAULT.key()).isEqualTo(ExplorerSort.Key.DATE);
            assertThat(ExplorerSort.DEFAULT.ascending()).isFalse();
        }

        @Test
        @DisplayName("SQL-ish input is never carried through - it is simply not a key")
        void sqlInputIsNotAKey() {
            // The parse result is an enum, which is what keeps request text out of ORDER BY.
            assertThat(ExplorerSort.parse("created_at; DROP TABLE storage.storage", "asc").key())
                    .isEqualTo(ExplorerSort.Key.DATE);
        }
    }

    // Nested on purpose: with @Nested siblings present, a top-level @Test in this class is
    // skipped by a `-Dtest=ExplorerSortTest` filter and would silently never run.
    @Nested
    @DisplayName("constructor contract")
    class Contract {

        @Test
        @DisplayName("a null key is a programming error, not a request to default")
        void nullKeyRejected() {
            assertThatThrownBy(() -> new ExplorerSort(null, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
