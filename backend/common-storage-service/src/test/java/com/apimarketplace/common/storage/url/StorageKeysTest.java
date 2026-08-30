package com.apimarketplace.common.storage.url;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's authorization boundary for a file is a prefix test on the key's first
 * segment. These rules are what make that test a boundary rather than a suggestion.
 */
@DisplayName("StorageKeys")
class StorageKeysTest {

    @Test
    @DisplayName("accepts an ordinary tenant-namespaced key")
    void acceptsOrdinaryKeys() {
        assertThat(StorageKeys.isWellFormed("1/run_123/core:watermark/clip.mp4")).isTrue();
        assertThat(StorageKeys.isWellFormed("_publications/7941574e/snapshot/x/clip.mp4")).isTrue();
    }

    @Test
    @DisplayName("refuses a key that walks out of its own prefix - `8/../3/x.pdf` starts with `8/` and names tenant 3, and nothing downstream normalizes it")
    void refusesTraversal() {
        assertThat(StorageKeys.isWellFormed("8/../3/private.pdf")).isFalse();
        assertThat(StorageKeys.isWellFormed("_publications/abc/../../3/private.pdf")).isFalse();
        assertThat(StorageKeys.isWellFormed("8/a/../../3/private.pdf")).isFalse();
    }

    @Test
    @DisplayName("refuses a single-dot segment, an empty segment and a leading slash - each is a way to write a path that is not what it reads as")
    void refusesDegenerateSegments() {
        assertThat(StorageKeys.isWellFormed("8/./a.png")).isFalse();
        assertThat(StorageKeys.isWellFormed("8//a.png")).isFalse();
        assertThat(StorageKeys.isWellFormed("/8/a.png")).isFalse();
        assertThat(StorageKeys.isWellFormed("8/a.png/")).isFalse();
    }

    @Test
    @DisplayName("refuses null and blank")
    void refusesNullAndBlank() {
        assertThat(StorageKeys.isWellFormed(null)).isFalse();
        assertThat(StorageKeys.isWellFormed("")).isFalse();
        assertThat(StorageKeys.isWellFormed("   ")).isFalse();
    }

    @Test
    @DisplayName("namespaceOf reads the owning segment, and refuses to answer for a key it would not vouch for")
    void namespaceOf() {
        assertThat(StorageKeys.namespaceOf("12/run/a.png")).isEqualTo("12");
        assertThat(StorageKeys.namespaceOf("_publications/abc/a.png")).isEqualTo("_publications");
        // No slash at all: nothing owns it.
        assertThat(StorageKeys.namespaceOf("a.png")).isNull();
        // Malformed: answering would hand a caller a prefix the rest of the key escapes.
        assertThat(StorageKeys.namespaceOf("8/../3/private.pdf")).isNull();
    }
}
