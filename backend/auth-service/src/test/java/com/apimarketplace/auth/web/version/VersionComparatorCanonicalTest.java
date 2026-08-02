package com.apimarketplace.auth.web.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct tests for {@link VersionComparator#canonicalOrNull}.
 *
 * <p>It is the single answer to "is this a version, and what is it?" for the public feed, the
 * announce endpoint's pin guard and the config bootstrap. It shipped as the fix for those three
 * disagreeing with each other, and had no test of its own: the callers' tests all used inputs that
 * every plausible implementation agrees on.
 */
class VersionComparatorCanonicalTest {

    @Test
    @DisplayName("a plain version is returned in major.minor.patch form")
    void plainVersion() {
        assertThat(VersionComparator.canonicalOrNull("0.2.7")).isEqualTo("0.2.7");
        assertThat(VersionComparator.canonicalOrNull(" 0.2.7 ")).isEqualTo("0.2.7");
        assertThat(VersionComparator.canonicalOrNull("1.0")).isEqualTo("1.0.0");
        assertThat(VersionComparator.canonicalOrNull("2")).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("exactly ONE leading v is accepted, so vv is not a version")
    void oneLeadingVOnly() {
        // The divergence this helper exists to remove: one caller stripped a v and then called a
        // parser that strips one too, so "vv0.2.7" was a version there and not elsewhere.
        assertThat(VersionComparator.canonicalOrNull("v0.2.7")).isEqualTo("0.2.7");
        assertThat(VersionComparator.canonicalOrNull("V0.2.7")).isEqualTo("0.2.7");
        assertThat(VersionComparator.canonicalOrNull("vv0.2.7")).isNull();
        assertThat(VersionComparator.canonicalOrNull("vV0.2.7")).isNull();
    }

    @Test
    @DisplayName("anything that is not a numeric core is refused")
    void refusesNonVersions() {
        for (String bad : new String[] { null, "", "   ", "v", "V", "latest", "next",
                "2026.07.30.1", "0.2.x", "-1.0.0", "0..7" }) {
            assertThat(VersionComparator.canonicalOrNull(bad)).as("input %s", bad).isNull();
        }
    }

    @Test
    @DisplayName("a pre-release suffix is dropped, which is what the feed then advertises")
    void prereleaseIsReducedToItsCore() {
        // Worth stating explicitly: the feed advertises the CORE, so a release cut as 0.2.8-rc1 is
        // announced as 0.2.8. The release job compares the feed against its own raw version, so a
        // suffixed release would fail its verification with a misleading diagnosis.
        assertThat(VersionComparator.canonicalOrNull("0.2.8-rc1")).isEqualTo("0.2.8");
        assertThat(VersionComparator.canonicalOrNull("v1.2.3+build9")).isEqualTo("1.2.3");
    }

    @Test
    @DisplayName("the canonical form is what isUpdateAvailable compares, so the two agree")
    void agreesWithTheComparison() {
        String canonical = VersionComparator.canonicalOrNull("v0.3.0");
        assertThat(canonical).isNotNull();
        assertThat(VersionComparator.isUpdateAvailable("0.2.7", canonical)).isTrue();
        assertThat(VersionComparator.isUpdateAvailable(canonical, "0.2.7")).isFalse();
    }
}
