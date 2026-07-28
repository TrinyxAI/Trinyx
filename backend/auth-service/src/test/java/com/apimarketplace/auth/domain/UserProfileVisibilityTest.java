package com.apimarketplace.auth.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the three-state profile visibility.
 *
 * <p>The whole point of splitting the old {@code isPublic()} in two is that
 * "the page exists" and "search engines may index it" are different questions.
 * These tests pin that they can never collapse back into one, which is what
 * would silently opt users into being indexed.
 */
@DisplayName("UserProfileEntity: three-state visibility")
class UserProfileVisibilityTest {

    private static UserProfileEntity profileWith(String visibility) {
        UserProfileEntity profile = new UserProfileEntity();
        profile.setProfileVisibility(visibility);
        return profile;
    }

    @Test
    @DisplayName("A new profile defaults to UNLISTED, never to indexable")
    void defaultsToUnlisted() {
        UserProfileEntity profile = new UserProfileEntity();

        assertThat(profile.getProfileVisibility()).isEqualTo(UserProfileEntity.VISIBILITY_UNLISTED);
        assertThat(profile.isPageVisible()).isTrue();
        assertThat(profile.isSearchIndexable()).isFalse();
    }

    @Test
    @DisplayName("PUBLIC is the only state that allows search indexing")
    void publicIsIndexable() {
        UserProfileEntity profile = profileWith(UserProfileEntity.VISIBILITY_PUBLIC);

        assertThat(profile.isPageVisible()).isTrue();
        assertThat(profile.isSearchIndexable()).isTrue();
    }

    @Test
    @DisplayName("UNLISTED has a working page but is not indexable")
    void unlistedIsReachableButNotIndexable() {
        UserProfileEntity profile = profileWith(UserProfileEntity.VISIBILITY_UNLISTED);

        // This is the state every pre-existing user is migrated to: their page
        // keeps working exactly as before, it is just not advertised.
        assertThat(profile.isPageVisible()).isTrue();
        assertThat(profile.isSearchIndexable()).isFalse();
    }

    @Test
    @DisplayName("PRIVATE has neither a page nor indexing")
    void privateHasNeither() {
        UserProfileEntity profile = profileWith(UserProfileEntity.VISIBILITY_PRIVATE);

        assertThat(profile.isPageVisible()).isFalse();
        assertThat(profile.isSearchIndexable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "Public", "UNLISTED", "unlisted", "private", "Private"})
    @DisplayName("State comparison is case-insensitive in both directions")
    void comparisonIsCaseInsensitive(String stored) {
        UserProfileEntity profile = profileWith(stored);

        assertThat(profile.isPageVisible()).isEqualTo(!stored.equalsIgnoreCase("private"));
        assertThat(profile.isSearchIndexable()).isEqualTo(stored.equalsIgnoreCase("public"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "SOMETHING_ELSE", "PUBLIC_EXTENDED", "null"})
    @DisplayName("An unrecognised value is never indexable (the old isPublic() footgun)")
    void unrecognisedValueIsNeverIndexable(String stored) {
        UserProfileEntity profile = profileWith(stored);

        // The replaced isPublic() was `!"PRIVATE".equalsIgnoreCase(v)`, so ANY
        // unexpected value read as public. With a third state that shape would
        // have made a typo, a bad migration or a future enum value silently
        // search-indexable. isSearchIndexable() answers only to the exact
        // PUBLIC literal.
        assertThat(profile.isSearchIndexable()).isFalse();
    }

    @Test
    @DisplayName("A null visibility is not indexable and does not throw")
    void nullVisibilityIsNotIndexable() {
        UserProfileEntity profile = profileWith(null);

        assertThat(profile.isSearchIndexable()).isFalse();
        assertThat(profile.isPageVisible()).isTrue();
    }
}
