package com.apimarketplace.auth.web.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CeReleaseConfigBootstrap}.
 *
 * <p>This exists to delete a manual cutover step, so what matters is that it fires exactly once,
 * on an empty row, and never overwrites a real announcement. Getting either wrong is fleet-wide
 * and silent: seeding over a newer release would walk every install backwards, and not seeding at
 * all leaves an operator to remember an ordering whose failure mode is an empty feed.
 */
class CeReleaseConfigBootstrapTest {

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<CeReleaseStore> provider(CeReleaseStore store) {
        org.springframework.beans.factory.ObjectProvider<CeReleaseStore> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(store);
        return p;
    }

    private static CeReleaseStore storeHolding(CeReleaseStore.Announced announced) {
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.current()).thenReturn(announced);
        when(store.announceIfAbsent(any(), any(), anyBoolean(), any())).thenReturn(announced == null);
        return store;
    }

    @Test
    @DisplayName("an empty row is seeded from the configured release")
    void seedsWhenRowIsEmpty() {
        CeReleaseStore store = storeHolding(null);

        new CeReleaseConfigBootstrap(provider(store), "0.2.7", "https://example.test/v0.2.7", true, "2026-07-30T10:00:00Z")
                .seedFromConfigIfEmpty();

        verify(store).announceIfAbsent("0.2.7", "https://example.test/v0.2.7", true, "2026-07-30T10:00:00Z");
    }

    @Test
    @DisplayName("the not-overwriting decision is delegated to the store, not taken from a cached read")
    void delegatesTheEmptinessDecision() {
        // The config is the manual override for the FEED; it must not rewrite history in the row.
        // This class used to decide that itself from a CACHED read taken outside the transaction,
        // so a release announced between the check and the write was silently overwritten. The
        // decision now happens under the row lock inside announceIfAbsent, and this test pins the
        // delegation; CeReleaseStoreTest pins the refusal itself.
        CeReleaseStore store = storeHolding(new CeReleaseStore.Announced("0.2.8", null, false, null));

        new CeReleaseConfigBootstrap(provider(store), "9.9.9", null, false, null).seedFromConfigIfEmpty();

        verify(store).announceIfAbsent("9.9.9", null, false, null);
        verify(store, never()).announce(any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("nothing is seeded when no version is configured, which is every self-hosted install")
    void doesNothingWithoutConfiguration() {
        CeReleaseStore store = storeHolding(null);

        new CeReleaseConfigBootstrap(provider(store), "", null, false, null).seedFromConfigIfEmpty();
        new CeReleaseConfigBootstrap(provider(store), "   ", null, false, null).seedFromConfigIfEmpty();
        new CeReleaseConfigBootstrap(provider(store), null, null, false, null).seedFromConfigIfEmpty();

        verify(store, never()).announceIfAbsent(any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("an unparseable configured version is refused rather than seeded")
    void refusesAnUnparseableConfiguredVersion() {
        CeReleaseStore store = storeHolding(null);

        for (String bad : new String[] { "latest", "v", "2026.07.30.1" }) {
            new CeReleaseConfigBootstrap(provider(store), bad, null, false, null).seedFromConfigIfEmpty();
        }

        verify(store, never()).announceIfAbsent(any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a store failure is swallowed rather than propagated out of the seeding call")
    void survivesAStoreFailure() {
        CeReleaseStore store = mock(CeReleaseStore.class);
        when(store.announceIfAbsent(any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("connection refused"));

        // Must not propagate. It runs on ApplicationReadyEvent, so a throw here would surface at
        // startup; that the LISTENER is wired at all is pinned separately, in the wiring test -
        // this one only proves the call itself swallows.
        assertThatCode(() -> new CeReleaseConfigBootstrap(provider(store), "0.2.7", null, false, null)
                .seedFromConfigIfEmpty())
                .doesNotThrowAnyException();
        verify(store).announceIfAbsent("0.2.7", null, false, null);
    }

    @Test
    @DisplayName("a losing race on the row is swallowed rather than crashing the second replica")
    void survivesALostRace() {
        CeReleaseStore store = storeHolding(null);
        org.mockito.Mockito.doThrow(new IllegalStateException("duplicate key"))
                .when(store).announceIfAbsent(any(), any(), anyBoolean(), any());

        // The assertion is the absence of a propagated exception AND that the write was genuinely
        // attempted: verifying only "no throw" would also pass if the seeding never ran at all.
        assertThatCode(() -> new CeReleaseConfigBootstrap(provider(store), "0.2.7", null, false, null)
                .seedFromConfigIfEmpty())
                .doesNotThrowAnyException();
        verify(store).announceIfAbsent("0.2.7", null, false, null);
    }
}
