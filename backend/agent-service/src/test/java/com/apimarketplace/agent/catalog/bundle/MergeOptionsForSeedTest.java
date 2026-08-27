package com.apimarketplace.agent.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MergeOptions#forSeed()} semantics: the model-catalog seed reuses an
 * allowed source (no migration), must not mass-deprecate, and keeps enabled on
 * insert. Also pins that {@code honorEnabledOnInsert} is OFF for the feed-sync
 * path only (V381: the BUNDLE path honors it - the payload's effective enabled
 * is an explicit cloud-admin decision) and survives the {@code with*} helpers.
 */
@DisplayName("MergeOptions.forSeed")
class MergeOptionsForSeedTest {

    @Test
    @DisplayName("forSeed uses source=curated (allowed by the CHECK → no migration), no deprecate, honor enabled")
    void forSeedSemantics() {
        MergeOptions o = MergeOptions.forSeed();
        assertThat(o.source()).isEqualTo("curated");
        assertThat(o.bundleVersion()).isNull();
        assertThat(o.deprecateMissing()).isFalse();
        assertThat(o.honorEnabledOnInsert()).isTrue();
        assertThat(o.partialUpdate())
                .as("seed is a PATCH: it must never null enrichment fields it omits")
                .isTrue();
    }

    @Test
    @DisplayName("Only the seed is a partial/patch merge; bundle and feed sync overwrite authoritatively")
    void onlySeedIsPartial() {
        assertThat(MergeOptions.forSeed().partialUpdate()).isTrue();
        assertThat(MergeOptions.forBundle(7L).partialUpdate()).isFalse();
        assertThat(MergeOptions.forSync().partialUpdate()).isFalse();
    }

    @Test
    @DisplayName("V381: sync keeps the review-gate; the bundle path honors the cloud's per-model decision")
    void syncGatedBundleHonors() {
        assertThat(MergeOptions.forSync().honorEnabledOnInsert())
                .as("untrusted feeds stay review-gated")
                .isFalse();
        assertThat(MergeOptions.forBundle(7L).honorEnabledOnInsert())
                .as("signed bundles carry an explicit cloud-admin enabled per model")
                .isTrue();
    }

    @Test
    @DisplayName("with* copy helpers preserve honorEnabledOnInsert")
    void withHelpersPreserveHonorEnabled() {
        MergeOptions seed = MergeOptions.forSeed();
        assertThat(seed.withDeprecateMissing(true).honorEnabledOnInsert()).isTrue();
        assertThat(seed.withLabel("relabelled").honorEnabledOnInsert()).isTrue();
        // and the sync path stays false through a copy
        assertThat(MergeOptions.forSync().withLabel("x").honorEnabledOnInsert()).isFalse();
    }

    @Test
    @DisplayName("Seed and sync synthesise default categories on insert; only the bundle carries its own sidecar")
    void seedAndSyncAssignDefaultCategoriesOnInsert() {
        assertThat(MergeOptions.forSeed().assignDefaultCategoriesOnInsert())
                .as("seed doc omits the categories sidecar → merge must backfill mode defaults")
                .isTrue();
        assertThat(MergeOptions.forBundle(7L).assignDefaultCategoriesOnInsert())
                .as("a bundle payload always carries its own categories sidecar")
                .isFalse();
        // Regression: sync used to insert WITHOUT categories. The row landed
        // disabled AND invisible to every category-scoped picker, so an admin
        // enabling it changed nothing - V388 had to repair that class by hand
        // once already, for the rows V384/V386 orphaned.
        assertThat(MergeOptions.forSync().assignDefaultCategoriesOnInsert())
                .as("a feed insert must still be selectable once an admin enables it")
                .isTrue();
    }

    @Test
    @DisplayName("Sync keeps its review gate: default categories do NOT imply an enabled row")
    void syncStaysReviewGatedDespiteDefaultCategories() {
        // The two flags are independent and must stay so: categories make an
        // enabled row reachable, honorEnabledOnInsert decides whether it is
        // enabled at all. Assigning categories must not have quietly opened
        // the feed straight into the picker.
        MergeOptions sync = MergeOptions.forSync();
        assertThat(sync.assignDefaultCategoriesOnInsert()).isTrue();
        assertThat(sync.honorEnabledOnInsert()).isFalse();
        assertThat(sync.deprecateMissing()).isFalse();
    }

    @Test
    @DisplayName("with* copy helpers preserve assignDefaultCategoriesOnInsert (guards a 6→7 arg pass-through slip)")
    void withHelpersPreserveAssignDefaultCategories() {
        MergeOptions seed = MergeOptions.forSeed();
        assertThat(seed.withDeprecateMissing(true).assignDefaultCategoriesOnInsert()).isTrue();
        assertThat(seed.withLabel("relabelled").assignDefaultCategoriesOnInsert()).isTrue();
        assertThat(MergeOptions.forBundle(7L).withLabel("x").assignDefaultCategoriesOnInsert()).isFalse();
        assertThat(MergeOptions.forSync().withDeprecateMissing(true).assignDefaultCategoriesOnInsert()).isTrue();
    }
}
