package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PublicationSlugAssigner} - the single point of truth for
 * giving a publication its public URL slug.
 *
 * <p>The invariants that matter here are the ones with SEO consequences: a slug
 * is assigned exactly once and never follows a renamed title, and two
 * publications can never end up sharing one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationSlugAssigner: assign-once, collision-free public slugs")
class PublicationSlugAssignerTest {

    @Mock private WorkflowPublicationRepository repository;

    private static WorkflowPublicationEntity publicationWith(String title, String existingSlug) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.fromString("0189d3c2-7f4a-4c11-9b3e-2a5d6e7f8a9b"));
        pub.setTitle(title);
        pub.setPublicSlug(existingSlug);
        return pub;
    }

    @Test
    @DisplayName("A publication without a slug gets one derived from its title")
    void assignsSlugFromTitle() {
        WorkflowPublicationEntity pub = publicationWith("Invoice Reminder Bot", null);
        when(repository.existsByPublicSlug("invoice-reminder-bot")).thenReturn(false);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).isEqualTo("invoice-reminder-bot");
    }

    @Test
    @DisplayName("An existing slug is never recomputed, even when the title changed")
    void keepsExistingSlugOnRename() {
        WorkflowPublicationEntity pub = publicationWith("A Completely New Title", "the-original-title");

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        // This is the whole point of "assign once": the published page is
        // already indexed under the original URL, and moving it would drop its
        // ranking and break every shared link.
        assertThat(pub.getPublicSlug()).isEqualTo("the-original-title");
        verify(repository, never()).existsByPublicSlug(anyString());
    }

    @Test
    @DisplayName("A blank slug counts as missing and is regenerated")
    void blankSlugIsTreatedAsMissing() {
        WorkflowPublicationEntity pub = publicationWith("Invoice Bot", "   ");
        when(repository.existsByPublicSlug("invoice-bot")).thenReturn(false);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).isEqualTo("invoice-bot");
    }

    @Test
    @DisplayName("A taken slug gets a numeric suffix")
    void deduplicatesWithNumericSuffix() {
        WorkflowPublicationEntity pub = publicationWith("Invoice Bot", null);
        when(repository.existsByPublicSlug("invoice-bot")).thenReturn(true);
        when(repository.existsByPublicSlug("invoice-bot-2")).thenReturn(false);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).isEqualTo("invoice-bot-2");
    }

    @Test
    @DisplayName("De-duplication keeps counting past the first free-looking suffix")
    void deduplicationWalksUntilFree() {
        WorkflowPublicationEntity pub = publicationWith("Invoice Bot", null);
        when(repository.existsByPublicSlug("invoice-bot")).thenReturn(true);
        when(repository.existsByPublicSlug("invoice-bot-2")).thenReturn(true);
        when(repository.existsByPublicSlug("invoice-bot-3")).thenReturn(true);
        when(repository.existsByPublicSlug("invoice-bot-4")).thenReturn(false);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).isEqualTo("invoice-bot-4");
    }

    @Test
    @DisplayName("Exhausting every suffix falls back to the id-derived slug, never to a duplicate")
    void fallsBackToIdWhenSuffixesExhausted() {
        WorkflowPublicationEntity pub = publicationWith("Invoice Bot", null);
        when(repository.existsByPublicSlug(anyString())).thenReturn(true);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        // The id-derived form is unique by construction, so the loop can give up
        // without ever emitting a slug that would violate the unique index.
        assertThat(pub.getPublicSlug()).isEqualTo("app-0189d3c2");
    }

    @Test
    @DisplayName("An untransliterable title falls back to the id-derived slug")
    void untransliterableTitleUsesIdFallback() {
        WorkflowPublicationEntity pub = publicationWith("发票提醒机器人", null);
        when(repository.existsByPublicSlug("app-0189d3c2")).thenReturn(false);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).isEqualTo("app-0189d3c2");
    }

    @Test
    @DisplayName("A first publish (id still null) still produces a usable slug")
    void worksBeforeIdIsAssigned() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setTitle("Invoice Bot");
        when(repository.existsByPublicSlug("invoice-bot")).thenReturn(false);

        // WorkflowPublicationEntity assigns its id in @PrePersist, which has not
        // run yet at publish time. A NullPointerException here would break every
        // first publish, not just the fallback branch.
        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).isEqualTo("invoice-bot");
    }

    @Test
    @DisplayName("A first publish with an untransliterable title still gets a unique slug")
    void untransliterableTitleBeforeIdAssigned() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setTitle("🚀🚀🚀");
        when(repository.existsByPublicSlug(anyString())).thenReturn(false);

        PublicationSlugAssigner.assignIfMissing(pub, repository);

        assertThat(pub.getPublicSlug()).matches("^app-[0-9a-f]{8}$");
    }

    @Test
    @DisplayName("All publish paths route through this helper (source-level invariant)")
    void allPublishPathsAssignASlug() throws Exception {
        // Mirrors PublisherProfileSnapshotterTest's structural guard. A publish
        // path that forgets the slug produces rows that are only reachable by
        // UUID, which the backfill then silently papers over at the next boot -
        // a bug that would never surface as a failure, only as missing SEO.
        java.nio.file.Path serviceDir = java.nio.file.Path.of(System.getProperty("user.dir"),
                "src/main/java/com/apimarketplace/publication/service");
        assertThat(serviceDir).as("publication-service source tree must be on disk at the module basedir").exists();

        for (String publishService : new String[]{
                "WorkflowPublicationService.java",
                "AgentPublicationService.java",
                "ResourcePublicationService.java"}) {
            String src = java.nio.file.Files.readString(serviceDir.resolve(publishService));
            assertThat(src)
                    .as("%s must assign a public slug at publish time", publishService)
                    .contains("PublicationSlugAssigner.assignIfMissing(");
        }
    }
}
