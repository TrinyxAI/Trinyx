package com.apimarketplace.publication.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PublicationSlugGenerator}, which turns a user-typed
 * publication title into the slug that ends up in an indexed public URL.
 *
 * <p>The behaviours pinned here are the ones that made a dedicated generator
 * necessary instead of reusing catalog-service's {@code SlugUtils}: accented
 * Latin must transliterate rather than lose characters, and a title with nothing
 * transliterable (CJK, emoji) must not collapse to an empty slug.
 */
@DisplayName("PublicationSlugGenerator: user titles to public URL slugs")
class PublicationSlugGeneratorTest {

    private static final UUID PUB_ID = UUID.fromString("0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b");

    @Nested
    @DisplayName("slugify()")
    class Slugify {

        @Test
        @DisplayName("Lowercases and hyphenates a plain title")
        void plainTitle() {
            assertThat(PublicationSlugGenerator.slugify("Invoice Reminder Bot"))
                    .isEqualTo("invoice-reminder-bot");
        }

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @DisplayName("Accents transliterate instead of being deleted")
        @CsvSource({
                "Résumé Créateur,  resume-createur",
                "Über Wetter,      uber-wetter",
                "Añadir Facturas,  anadir-facturas",
                "Ação Rápida,      acao-rapida",
        })
        void accentsTransliterate(String title, String expected) {
            // The reason this class exists: a generator that only strips
            // non-[a-z0-9] would turn "Générateur" into "gnrateur", which is
            // both unreadable and useless as a search-engine keyword.
            assertThat(PublicationSlugGenerator.slugify(title)).isEqualTo(expected);
        }

        @Test
        @DisplayName("An apostrophe becomes a separator, not a deleted character")
        void apostropheBecomesSeparator() {
            // Kept out of the @CsvSource above on purpose: JUnit's CSV parser
            // treats the single quote as its quote character.
            assertThat(PublicationSlugGenerator.slugify("Générateur d'emails"))
                    .isEqualTo("generateur-d-emails");
        }

        @Test
        @DisplayName("Punctuation and symbols collapse to single hyphens")
        void punctuationCollapses() {
            assertThat(PublicationSlugGenerator.slugify("Slack  ->  Notion   sync!!! (v2)"))
                    .isEqualTo("slack-notion-sync-v2");
        }

        @Test
        @DisplayName("Leading and trailing separators are trimmed")
        void edgesTrimmed() {
            assertThat(PublicationSlugGenerator.slugify("  ...Weekly Digest...  "))
                    .isEqualTo("weekly-digest");
        }

        @ParameterizedTest
        @DisplayName("Titles with nothing transliterable yield an empty slug, never a stray hyphen")
        @ValueSource(strings = {"发票提醒机器人", "🚀🚀🚀", "!!!", "---", "。、"})
        void untransliterableYieldsEmpty(String title) {
            // Empty is the contract that tells callers to use fallbackSlug().
            // A "-" or "" difference matters: "-" would be persisted as a real
            // slug and produce the URL /marketplace/-.
            assertThat(PublicationSlugGenerator.slugify(title)).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("Null and blank titles yield an empty slug")
        @ValueSource(strings = {"", "   ", "\t\n"})
        void blankYieldsEmpty(String title) {
            assertThat(PublicationSlugGenerator.slugify(title)).isEmpty();
        }

        @Test
        @DisplayName("Null title yields an empty slug rather than throwing")
        void nullYieldsEmpty() {
            assertThat(PublicationSlugGenerator.slugify(null)).isEmpty();
        }

        @Test
        @DisplayName("Long titles are truncated on a word boundary, never mid-word")
        void truncatesOnWordBoundary() {
            String title = "automate every single invoice reminder across all of your customer "
                    + "accounts without writing any code whatsoever today";

            String slug = PublicationSlugGenerator.slugify(title);

            assertThat(slug).hasSizeLessThanOrEqualTo(PublicationSlugGenerator.MAX_SLUG_LENGTH);
            assertThat(slug).doesNotEndWith("-");
            // The cut lands on a hyphen, so the final segment is a whole word
            // from the title rather than a truncated fragment.
            String lastWord = slug.substring(slug.lastIndexOf('-') + 1);
            assertThat(title).contains(lastWord);
        }

        @Test
        @DisplayName("A single word longer than the budget is cut hard (no boundary to fall back to)")
        void singleOverlongWordIsCutHard() {
            String slug = PublicationSlugGenerator.slugify("a".repeat(200));

            assertThat(slug).hasSize(PublicationSlugGenerator.MAX_SLUG_LENGTH);
        }

        @Test
        @DisplayName("Output only ever contains lowercase alphanumerics and inner hyphens")
        void outputAlphabetIsUrlSafe() {
            String slug = PublicationSlugGenerator.slugify("Ünïcôdé  Ñoise!! 42 %$# Test");

            assertThat(slug).matches("^[a-z0-9]+(-[a-z0-9]+)*$");
        }
    }

    @Nested
    @DisplayName("fallbackSlug() / slugifyOrFallback()")
    class Fallback {

        @Test
        @DisplayName("Fallback is derived from the id and is stable across calls")
        void fallbackIsDeterministic() {
            String first = PublicationSlugGenerator.fallbackSlug(PUB_ID);
            String second = PublicationSlugGenerator.fallbackSlug(PUB_ID);

            // Determinism is what makes the backfill safe to re-run: a random
            // fallback would mint a NEW url for an already-indexed page.
            assertThat(first).isEqualTo(second).isEqualTo("app-0189d3c2");
        }

        @Test
        @DisplayName("Fallback is URL-safe like any other slug")
        void fallbackIsUrlSafe() {
            assertThat(PublicationSlugGenerator.fallbackSlug(PUB_ID))
                    .matches("^[a-z0-9]+(-[a-z0-9]+)*$");
        }

        @Test
        @DisplayName("Different publications get different fallbacks")
        void fallbackDiscriminatesPublications() {
            assertThat(PublicationSlugGenerator.fallbackSlug(UUID.randomUUID()))
                    .isNotEqualTo(PublicationSlugGenerator.fallbackSlug(UUID.randomUUID()));
        }

        @Test
        @DisplayName("slugifyOrFallback prefers the title when it yields something")
        void prefersTitle() {
            assertThat(PublicationSlugGenerator.slugifyOrFallback("Invoice Bot", PUB_ID))
                    .isEqualTo("invoice-bot");
        }

        @Test
        @DisplayName("slugifyOrFallback falls back for an untransliterable title")
        void fallsBackForCjkTitle() {
            assertThat(PublicationSlugGenerator.slugifyOrFallback("发票提醒机器人", PUB_ID))
                    .isEqualTo("app-0189d3c2");
        }
    }

    @Nested
    @DisplayName("withSuffix()")
    class WithSuffix {

        @Test
        @DisplayName("Appends the de-duplication counter")
        void appendsCounter() {
            assertThat(PublicationSlugGenerator.withSuffix("invoice-bot", 2)).isEqualTo("invoice-bot-2");
            assertThat(PublicationSlugGenerator.withSuffix("invoice-bot", 37)).isEqualTo("invoice-bot-37");
        }

        @Test
        @DisplayName("A base already at the length ceiling still fits once suffixed")
        void reTruncatesLongBase() {
            String base = "a".repeat(PublicationSlugGenerator.MAX_SLUG_LENGTH);

            String suffixed = PublicationSlugGenerator.withSuffix(base, 12);

            // Without re-truncation this would exceed the VARCHAR(120) column
            // and fail on insert only for the unlucky duplicate title.
            assertThat(suffixed).hasSizeLessThanOrEqualTo(PublicationSlugGenerator.MAX_SLUG_LENGTH);
            assertThat(suffixed).endsWith("-12");
        }

        @Test
        @DisplayName("No double hyphen when truncation lands exactly on a separator")
        void noDoubleHyphen() {
            // Sized so the re-truncation cuts right after the hyphen: without
            // the trailing-hyphen strip the result would be "...a--5".
            String base = "a".repeat(PublicationSlugGenerator.MAX_SLUG_LENGTH - 3) + "-bb";

            String suffixed = PublicationSlugGenerator.withSuffix(base, 5);

            assertThat(suffixed).doesNotContain("--").endsWith("-5");
            assertThat(suffixed).matches("^[a-z0-9]+(-[a-z0-9]+)*$");
        }
    }
}
