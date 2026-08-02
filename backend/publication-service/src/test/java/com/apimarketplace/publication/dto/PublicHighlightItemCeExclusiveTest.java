package com.apimarketplace.publication.dto;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The curated highlights row is the most-seen surface (anonymous Home and the
 * chat welcome screen) and it has its OWN slim projection, deliberately stripped
 * of the heavy JSONB columns. A field that is not copied there is simply absent
 * from the payload, and the badge cannot render.
 *
 * <p>That is a labelling gap, not an install hole (the guard still refuses), but
 * the stated intent is that such an app "must be labelled" - so the one surface
 * where it silently looks like any other installable app is worth pinning.
 */
@DisplayName("PublicHighlightItem - CE-exclusive label")
class PublicHighlightItemCeExclusiveTest {

    private static WorkflowPublicationEntity publication(boolean ceExclusive, List<String> features) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity(
                UUID.randomUUID(), "RAG App", Map.of(), "publisher-1");
        p.setId(UUID.randomUUID());
        p.setDisplayMode(DisplayMode.APPLICATION);
        p.setCeExclusive(ceExclusive);
        p.setCeExclusiveFeatures(features);
        return p;
    }

    @Test
    @DisplayName("carries the flag and its features into the slim projection")
    void carriesTheLabel() {
        PublicHighlightItem item = PublicHighlightItem.from(
                publication(true, List.of("CLI_AGENT", "VECTOR_SEARCH")));

        assertThat(item.ceExclusive()).isTrue();
        assertThat(item.ceExclusiveFeatures()).containsExactly("CLI_AGENT", "VECTOR_SEARCH");
    }

    @Test
    @DisplayName("a normal publication reports false with an empty feature list, never null")
    void normalPublicationReportsFalse() {
        PublicHighlightItem item = PublicHighlightItem.from(publication(false, List.of()));

        assertThat(item.ceExclusive()).isFalse();
        // The frontend iterates the list for the tooltip; null would throw there.
        assertThat(item.ceExclusiveFeatures()).isEmpty();
    }
}
