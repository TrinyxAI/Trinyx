package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.domain.CeRelease;
import com.apimarketplace.auth.repository.CeReleaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CeReleaseStore}, the cache in front of the announced release.
 *
 * <p>What makes these worth writing: this class decides what the entire self-hosted fleet is told,
 * and its two failure behaviours are the opposite of intuitive. Serving a stale answer is correct;
 * serving "no release" is not, because every CE binary released before the null-guard fix stores
 * that null over a good status and blanks its own update banner.
 */
class CeReleaseStoreTest {

    /** announce() reads through the locked query; current() through the plain one. */
    private static void stubBoth(CeReleaseRepository repo, CeRelease value) {
        when(repo.findById(CeRelease.SINGLETON_ID)).thenReturn(Optional.ofNullable(value));
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenReturn(Optional.ofNullable(value));
    }

    private static CeRelease row(String version) {
        return new CeRelease(version, "https://example.test/notes", false, null);
    }

    @Test
    @DisplayName("the row is read once and then served from cache")
    void secondReadIsCached() {
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenReturn(Optional.of(row("0.2.7")));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.current().latestVersion()).isEqualTo("0.2.7");
        assertThat(store.current().latestVersion()).isEqualTo("0.2.7");

        // The feed is public and unauthenticated; a DB round trip per request is the thing the
        // cache exists to prevent.
        verify(repo, times(1)).findById(CeRelease.SINGLETON_ID);
    }

    @Test
    @DisplayName("an empty table is cached as 'nothing announced' without re-querying")
    void emptyTableIsCached() {
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenReturn(Optional.empty());
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.current()).isNull();
        assertThat(store.current()).isNull();

        verify(repo, times(1)).findById(CeRelease.SINGLETON_ID);
    }

    @Test
    @DisplayName("a read failure with NO cached answer throws instead of reporting 'no release'")
    void coldFailureThrows() {
        // The load-bearing case. Returning null here would make the public feed answer 200 with a
        // null version, and every already-shipped CE install would overwrite its good status with
        // it. Throwing makes those pollers see an error and keep what they had.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenThrow(new IllegalStateException("connection refused"));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
    }

    @Test
    @DisplayName("a read failure AFTER a good read serves the stale answer rather than failing")
    void warmFailureServesStale() {
        // Zero TTL so every read misses the cache; the snapshot is still kept, which is exactly
        // the fallback under test.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID))
                .thenReturn(Optional.of(row("0.2.7")))
                .thenThrow(new IllegalStateException("connection refused"));
        CeReleaseStore store = new CeReleaseStore(repo, Duration.ZERO);

        assertThat(store.current().latestVersion()).isEqualTo("0.2.7");
        // The repo now throws, but a previous snapshot exists: stale beats "no release".
        assertThat(store.current().latestVersion()).isEqualTo("0.2.7");
    }

    @Test
    @DisplayName("announcing with no existing row saves a fresh entity carrying the singleton id")
    void announceInsertsWhenAbsent() {
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenReturn(Optional.empty());
        CeReleaseStore store = new CeReleaseStore(repo);

        store.announce("0.2.8", "https://example.test/notes", true, "2026-07-30T10:00:00Z", true);

        ArgumentCaptor<CeRelease> saved = ArgumentCaptor.forClass(CeRelease.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getLatestVersion()).isEqualTo("0.2.8");
        assertThat(saved.getValue().isSecurityFix()).isTrue();
        assertThat(saved.getValue().getId()).isEqualTo(CeRelease.SINGLETON_ID);
    }

    @Test
    @DisplayName("announcing with an existing row updates it in place, keeping the singleton id")
    void announceUpdatesInPlace() {
        CeRelease existing = row("0.2.7");
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        stubBoth(repo, existing);
        CeReleaseStore store = new CeReleaseStore(repo);

        store.announce("0.2.8", "https://example.test/new", true, null, true);

        assertThat(existing.getLatestVersion()).isEqualTo("0.2.8");
        assertThat(existing.getReleaseUrl()).isEqualTo("https://example.test/new");
        assertThat(existing.isSecurityFix()).isTrue();
        assertThat(existing.getId()).isEqualTo(CeRelease.SINGLETON_ID);
        verify(repo).save(existing);
    }

    @Test
    @DisplayName("announcing refreshes the cache, so this pod serves the new release without re-reading")
    void announceRefreshesTheCache() {
        // Without this the pod that accepted the write keeps serving the OLD version for a full
        // TTL, and the release job's own verification reads it back and fails.
        // DISTINCT row instances per call: with one shared instance announce() mutates it in
        // place, so a re-read returns the new value even when the cache was merely cleared - and
        // this test passed with the pre-fix "clear to null" behaviour restored.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenAnswer(invocation -> Optional.of(row("0.2.7")));
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenAnswer(invocation -> Optional.of(row("0.2.7")));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.current().latestVersion()).isEqualTo("0.2.7");
        store.announce("0.2.8", "https://example.test/8", true, "2026-07-30T10:00:00Z", true);

        CeReleaseStore.Announced after = store.current();
        assertThat(after.latestVersion()).isEqualTo("0.2.8");
        assertThat(after.securityFix()).isTrue();
        assertThat(after.releaseUrl()).isEqualTo("https://example.test/8");
    }

    @Test
    @DisplayName("after announcing, a read failure still serves the announced value rather than throwing")
    void announceKeepsAFallbackForLaterFailures() {
        // Clearing the cache to null after a write would leave the very next read with nothing to
        // fall back on, so a database blip right after a release would answer "no release" to the
        // whole fleet. Caching the announced value instead keeps the fallback alive.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        // The read path is down from the start; only the (locked) write path works. Zero TTL so
        // the read below cannot be served from cache and must fall back.
        when(repo.findById(CeRelease.SINGLETON_ID))
                .thenThrow(new IllegalStateException("connection refused"));
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenReturn(Optional.empty());
        CeReleaseStore store = new CeReleaseStore(repo, Duration.ZERO);

        store.announce("0.2.8", null, false, null, true);

        assertThat(store.current().latestVersion()).isEqualTo("0.2.8");
    }

    @Test
    @DisplayName("a slow read cannot overwrite an announcement that landed while it was in flight")
    void inFlightReadCannotUndoANewerAnnouncement() {
        // The lost update the plain cache.set could not prevent: a read queries the DB, an
        // announcement commits, then the read installs its now-stale result and this pod serves
        // the OLD version for a full TTL - precisely the window the release job verifies in.
        // The stub announces from INSIDE the query, reproducing that interleaving deterministically.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        // A REAL TTL, so the assertion below reads the cache rather than the repository. With a
        // zero TTL the second read re-queries and returns the right answer either way, which made
        // the first version of this test pass with the CAS guard deleted.
        CeReleaseStore store = new CeReleaseStore(repo, Duration.ofMinutes(5));
        // Fires ONCE: announce() itself calls findById, so an unguarded re-entry recurses forever.
        java.util.concurrent.atomic.AtomicBoolean interleaved = new java.util.concurrent.atomic.AtomicBoolean();
        when(repo.findById(CeRelease.SINGLETON_ID)).thenAnswer(invocation -> {
            if (interleaved.compareAndSet(false, true)) {
                store.announce("0.2.8", null, false, null, true); // commits while the read is in flight
            }
            return Optional.of(row("0.2.7"));               // the read's own, older result
        });

        store.current();

        // Served from cache: 0.2.7 must not have been resurrected over the newer announcement.
        assertThat(store.current().latestVersion()).isEqualTo("0.2.8");
    }

    @Test
    @DisplayName("a failed lookup backs off instead of re-querying an unhealthy database every request")
    void failureBacksOff() {
        // The feed is public and unauthenticated. Retrying per request turns a database blip into
        // an amplification path against a database that is already struggling.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID))
                .thenReturn(Optional.of(row("0.2.7")))
                .thenThrow(new IllegalStateException("connection refused"));
        CeReleaseStore store = new CeReleaseStore(repo, Duration.ZERO);

        store.current();                 // warms the snapshot (TTL zero, so it is already expired)
        store.current();                 // fails, serves stale, and re-caches with the backoff
        store.current();                 // must come from that backoff window, NOT the repository
        store.current();

        // 2 = the warming read + the one failure. Without the backoff every later call re-queries.
        verify(repo, times(2)).findById(CeRelease.SINGLETON_ID);
    }

    @Test
    @DisplayName("a COLD failure arms the backoff: the next request refuses without touching the database")
    void coldFailureArmsTheBackoff() {
        // Both halves of the amplification guard, in one test. Deleting the cold-path arming, or
        // neutering the "still unavailable" branch so it answers "no release" instead of throwing,
        // each leave this red. Before this, both survived with the whole suite green.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenThrow(new IllegalStateException("connection refused"));
        CeReleaseStore store = new CeReleaseStore(repo, Duration.ofMinutes(5));

        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
        // Inside the backoff: must still REFUSE (not answer "no release", which blanks banners
        // fleet-wide) and must not re-query.
        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);

        verify(repo, times(1)).findById(CeRelease.SINGLETON_ID);
    }

    @Test
    @DisplayName("an outage LONGER than the backoff keeps refusing, it never starts answering 'no release'")
    void expiredBackoffStillRefuses() {
        // The defect this pins: the cold path installs a non-null sentinel, so a guard testing only
        // `snapshot == null` let the warm branch take over once it expired, cache its null value,
        // and answer 200-with-null for the rest of the outage - reached ten seconds into any
        // outage that catches a pod with a cold cache. Zero backoff = observe past the window.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenThrow(new IllegalStateException("connection refused"));
        CeReleaseStore store = new CeReleaseStore(repo, Duration.ofMinutes(5), Duration.ZERO);

        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
        // Backoff already expired: this is the call that used to return null.
        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
        assertThatThrownBy(store::current).isInstanceOf(CeReleaseStore.LookupUnavailableException.class);
    }

    @Test
    @DisplayName("the cache is refreshed only AFTER the transaction commits, never before")
    void cacheIsRefreshedAfterCommitOnly() {
        // The afterCommit branch had never executed in any test: every case ran outside a
        // transaction, so isSynchronizationActive() was false in 100% of the suite and deleting
        // the registration entirely left everything green.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenAnswer(invocation -> Optional.of(row("0.2.7")));
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenAnswer(invocation -> Optional.of(row("0.2.7")));
        CeReleaseStore store = new CeReleaseStore(repo);
        assertThat(store.current().latestVersion()).isEqualTo("0.2.7");

        TransactionSynchronizationManager.initSynchronization();
        try {
            store.announce("0.2.8", null, false, null, true);
            // Still inside the transaction: a reader must NOT yet see the uncommitted value.
            assertThat(store.current().latestVersion())
                    .as("publishing before commit exposes a value that may still roll back")
                    .isEqualTo("0.2.7");

            TransactionSynchronizationUtils.triggerAfterCommit();
            assertThat(store.current().latestVersion()).isEqualTo("0.2.8");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the row-level guard refuses an older or equal version when force is false")
    void rowGuardRefusesOlderWithoutForce() {
        // Never once executed before: every other test calls the 4-arg overload, which hard-codes
        // force=true, so the guard could be replaced with `if (false)` and the suite stayed green.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        stubBoth(repo, row("0.3.0"));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.announce("0.2.8", null, false, null, false)).isFalse();
        assertThat(store.announce("0.3.0", null, false, null, false)).isFalse();
        verify(repo, org.mockito.Mockito.never()).save(any(CeRelease.class));
    }

    @Test
    @DisplayName("the row-level guard lets force through, which is the retraction path")
    void rowGuardHonoursForce() {
        // Dropping the force check made every retraction answer 409 with nothing red.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        stubBoth(repo, row("0.3.0"));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.announce("0.2.8", null, false, null, true)).isTrue();
        verify(repo).save(any(CeRelease.class));
    }

    @Test
    @DisplayName("the row-level guard accepts a newer version without force")
    void rowGuardAcceptsNewer() {
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        stubBoth(repo, row("0.2.7"));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.announce("0.2.8", null, false, null, false)).isTrue();
        verify(repo).save(any(CeRelease.class));
    }

    @Test
    @DisplayName("an unreadable stored version is treated as nothing announced, not as a blocker")
    void unreadableStoredVersionAcceptsTheNextAnnouncement() {
        for (String stored : new String[] { "latest", "v", "  ", "2026.07.30.1" }) {
            CeReleaseRepository repo = mock(CeReleaseRepository.class);
            stubBoth(repo, row(stored));
            CeReleaseStore store = new CeReleaseStore(repo);

            assertThat(store.announce("0.2.8", null, false, null, false))
                    .as("stored %s", stored)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a row whose version is NULL accepts the next announcement")
    void nullStoredVersionAcceptsTheNextAnnouncement() {
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        stubBoth(repo, row(null));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.announce("0.2.8", null, false, null, false)).isTrue();
    }

    @Test
    @DisplayName("announceIfAbsent refuses when a release is already announced, deciding under the lock")
    void announceIfAbsentLeavesAnExistingReleaseAlone() {
        // The bootstrap used a CACHED read outside the transaction and then wrote with force, so a
        // release announced between the two was silently overwritten by the pin - the fleet walked
        // backwards by the component whose javadoc promised it could not.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        stubBoth(repo, row("0.3.0"));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.announceIfAbsent("0.2.7", null, false, null)).isFalse();
        verify(repo, org.mockito.Mockito.never()).save(any(CeRelease.class));
    }

    @Test
    @DisplayName("announceIfAbsent fills a row that holds no usable version")
    void announceIfAbsentFillsAnEmptyRow() {
        for (String stored : new String[] { null, "latest", "  " }) {
            CeReleaseRepository repo = mock(CeReleaseRepository.class);
            stubBoth(repo, stored == null ? null : row(stored));
            CeReleaseStore store = new CeReleaseStore(repo);

            assertThat(store.announceIfAbsent("0.2.7", null, false, null))
                    .as("stored %s", stored)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("announceIfAbsent writes from the LOCKED read and never re-reads the row")
    void announceIfAbsentWritesFromTheLockedRead() {
        // The residual defect this pins: the old version checked emptiness and then delegated to
        // announce(force=true), which RE-READ the row and overwrote whatever had appeared in
        // between. A lock does not help - SELECT FOR UPDATE takes none on a row that does not
        // exist, which is the only state this method is for. The two stubs disagree on purpose:
        // if the write path re-reads at all, it sees 0.3.0 and the assertion catches it.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.findById(CeRelease.SINGLETON_ID)).thenReturn(Optional.of(row("0.3.0")));
        CeReleaseStore store = new CeReleaseStore(repo);

        assertThat(store.announceIfAbsent("0.2.7", null, false, null)).isTrue();

        ArgumentCaptor<CeRelease> saved = ArgumentCaptor.forClass(CeRelease.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getLatestVersion()).isEqualTo("0.2.7");
        // The locked query is the only read: a plain findById here would be the unlocked path.
        verify(repo).findByIdForUpdate(CeRelease.SINGLETON_ID);
        verify(repo, org.mockito.Mockito.never()).findById(CeRelease.SINGLETON_ID);
    }

    @Test
    @DisplayName("announceIfAbsent runs in one transaction, so the lock spans the write")
    void announceIfAbsentIsTransactional() throws Exception {
        // The seed path takes announceIfAbsent, not announce, so reflecting only over announce
        // gave false assurance for the path the bootstrap actually uses.
        java.lang.reflect.Method m = CeReleaseStore.class.getMethod(
                "announceIfAbsent", String.class, String.class, boolean.class, String.class);
        assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .as("without this the read and the write are separate transactions")
                .isNotNull();
    }

    @Test
    @DisplayName("announceIfAbsent also publishes only AFTER the commit")
    void announceIfAbsentPublishesAfterCommitOnly() {
        // The seed path had its own copy of this and only announce() was covered, so publishing
        // before commit survived every test. A rolled-back seed would then be served from this
        // pod's cache for a full TTL.
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findByIdForUpdate(CeRelease.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.findById(CeRelease.SINGLETON_ID)).thenReturn(Optional.empty());
        CeReleaseStore store = new CeReleaseStore(repo);

        TransactionSynchronizationManager.initSynchronization();
        try {
            store.announceIfAbsent("0.2.7", null, false, null);
            assertThat(store.current())
                    .as("an uncommitted seed must not be served: it may still roll back")
                    .isNull();

            TransactionSynchronizationUtils.triggerAfterCommit();
            assertThat(store.current().latestVersion()).isEqualTo("0.2.7");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a stored row is mapped field for field onto what the reader sees")
    void announcedFieldsRoundTrip() {
        CeReleaseRepository repo = mock(CeReleaseRepository.class);
        when(repo.findById(CeRelease.SINGLETON_ID)).thenReturn(
                Optional.of(new CeRelease("0.3.0", "https://example.test/3", true, "2026-08-01T00:00:00Z")));
        CeReleaseStore store = new CeReleaseStore(repo);

        CeReleaseStore.Announced announced = store.current();

        assertThat(announced.latestVersion()).isEqualTo("0.3.0");
        assertThat(announced.releaseUrl()).isEqualTo("https://example.test/3");
        assertThat(announced.securityFix()).isTrue();
        assertThat(announced.publishedAt()).isEqualTo("2026-08-01T00:00:00Z");
        verify(repo, times(1)).findById(any());
    }

    @Test
    @DisplayName("REGRESSION: exactly one constructor is Spring-selectable, so the context can start")
    void exactlyOneConstructorIsSpringSelectable() {
        // This class has two constructors (the second is a TTL test seam). Spring only
        // auto-selects a constructor when there is exactly ONE; with several and none
        // annotated it falls back to the no-arg constructor, which does not exist. The
        // whole application then dies at startup with
        // "NoSuchMethodException: CeReleaseStore.<init>()" - observed on the CE monolith,
        // and it would hit standalone auth-service the same way.
        var constructors = CeReleaseStore.class.getDeclaredConstructors();
        assertThat(constructors)
            .as("guard is only needed while there is more than one constructor")
            .hasSizeGreaterThan(1);

        var autowired = java.util.Arrays.stream(constructors)
            .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
            .toList();
        assertThat(autowired)
            .as("with several constructors, exactly one must carry @Autowired or Spring "
                + "instantiates via a no-arg constructor that does not exist")
            .hasSize(1);
        assertThat(autowired.get(0).getParameterTypes())
            .containsExactly(CeReleaseRepository.class);
    }
}
