package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.domain.CeRelease;
import com.apimarketplace.auth.repository.CeReleaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read/write access to the single announced CE release, with a short read-through cache.
 *
 * <p>The feed it backs is public and unauthenticated, so a DB round trip per request is not
 * acceptable; the announcement changes a handful of times a year, so a short TTL costs nothing in
 * freshness. Each pod caches independently: they converge within the TTL, and briefly disagreeing
 * about which release is newest is harmless for a daily poller.
 *
 * <p>On a read failure it serves the last known ANNOUNCEMENT, however stale, and throws whenever
 * there is none - including when the only thing cached is a previous failure. That order is deliberate: a CE install cannot distinguish "no release" from "you are up
 * to date", and every binary released before the null-guard fix stores a null version over a good
 * one. A stale answer is harmless; an empty one blanks the update banner across the fleet, and a
 * thrown error at least makes those pollers keep what they already had. A cold failure also arms a
 * short backoff, so an outage cannot turn this public unauthenticated endpoint into one database
 * connection per request.
 */
@Service
public class CeReleaseStore {

    private static final Logger log = LoggerFactory.getLogger(CeReleaseStore.class);
    static final Duration TTL = Duration.ofSeconds(60);
    /** How long a failed lookup keeps serving the stale value before trying the database again. */
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(10);

    private final CeReleaseRepository repository;
    private final Duration ttl;
    private final Duration failureBackoff;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>(null);
    private final AtomicLong sequence = new AtomicLong(0);

    // Explicit: the test-seam overload below means there is no longer a single constructor for
    // Spring to pick implicitly, and without this the context fails with "No default constructor
    // found" - i.e. auth-service does not start.
    @Autowired
    public CeReleaseStore(CeReleaseRepository repository) {
        this(repository, TTL, FAILURE_BACKOFF);
    }

    /** Test seam: a zero TTL makes every read miss the cache while keeping the stale fallback. */
    CeReleaseStore(CeReleaseRepository repository, Duration ttl) {
        this(repository, ttl, FAILURE_BACKOFF);
    }

    /**
     * Test seam: a zero backoff lets a test observe what happens AFTER the backoff expires, which
     * is where the cold path used to start answering "no release" instead of refusing.
     */
    CeReleaseStore(CeReleaseRepository repository, Duration ttl, Duration failureBackoff) {
        this.repository = repository;
        this.ttl = ttl;
        this.failureBackoff = failureBackoff;
    }

    /**
     * The announced release, or {@code null} when nothing has been announced.
     *
     * @throws LookupUnavailableException when the row cannot be read and no previous snapshot
     *                                    exists to fall back on. Deliberately NOT swallowed into a
     *                                    null: "no release" and "I cannot tell you" are the same
     *                                    answer on the wire, and every CE binary released before
     *                                    the null-guard fix stores a null version over a good one,
     *                                    blanking its update banner. Failing the request instead
     *                                    makes those pollers throw and keep what they had.
     */
    public Announced current() {
        Snapshot snapshot = cache.get();
        if (snapshot != null && Instant.now().isBefore(snapshot.expiresAt())) {
            if (snapshot.unavailable()) {
                // Still inside the backoff after a cold failure. Keep refusing rather than
                // answering "no release", which is the payload that blanks banners fleet-wide.
                throw new LookupUnavailableException(null);
            }
            return snapshot.value();
        }
        // Stamped BEFORE the query: anything published while this read is in flight is newer than
        // what the read is about to return, and must not be overwritten by it.
        long startedAt = sequence.get();
        try {
            Announced fresh = repository.findById(CeRelease.SINGLETON_ID)
                    .map(row -> new Announced(
                            row.getLatestVersion(), row.getReleaseUrl(), row.isSecurityFix(), row.getPublishedAt()))
                    .orElse(null);
            publish(new Snapshot(fresh, Instant.now().plus(ttl), startedAt, false));
            return fresh;
        } catch (RuntimeException failure) {
            // `snapshot.unavailable()` counts as NO fallback, not as one holding null. The cold
            // path installs a non-null sentinel, so testing `snapshot == null` alone meant that
            // once the sentinel expired mid-outage the warm branch below took over, cached its
            // null value and answered 200-with-null from then on - the banner-blanking payload
            // this class exists to never emit, reached ten seconds into any outage that catches a
            // pod with a cold cache.
            if (snapshot == null || snapshot.unavailable()) {
                publish(new Snapshot(null, Instant.now().plus(failureBackoff), startedAt, true));
                log.warn("CE release lookup failed with no cached answer to fall back on", failure);
                throw new LookupUnavailableException(failure);
            }
            // Re-cache the stale value for a short while instead of retrying on every request: the
            // feed is public and unauthenticated, so hammering an already-unhealthy database once
            // per request is an amplification path, not resilience.
            publish(new Snapshot(snapshot.value(), Instant.now().plus(failureBackoff), snapshot.sequence(), false));
            log.debug("CE release lookup failed - serving the last known announcement: {}", failure.getMessage());
            return snapshot.value();
        }
    }

    /** Raised when the announced release cannot be determined at all. */
    public static class LookupUnavailableException extends RuntimeException {
        LookupUnavailableException(Throwable cause) {
            super("the announced CE release could not be read", cause);
        }
    }

    /**
     * Replaces the announced release and refreshes this pod's cache with it, so this pod answers
     * with the new value immediately. Other pods pick it up within {@link #TTL}, which is why the
     * release job polls its verification instead of checking once.
     */
    @Transactional
    public boolean announce(String version, String url, boolean securityFix, String publishedAt, boolean force) {
        // Locked read: the guard below and the write must be one atomic step, or two concurrent
        // announces both pass it and the older one can commit last.
        CeRelease row = repository.findByIdForUpdate(CeRelease.SINGLETON_ID).orElse(null);
        // Compared against the ROW inside the transaction, not the per-replica cache: with more
        // than one replica a 60s-stale cache could let an older version overwrite a newer one,
        // which is exactly what this guard exists to prevent.
        // A stored version that cannot be parsed counts as nothing advertised, exactly like an
        // unreadable pin. Without this, isUpdateAvailable() is conservatively false for a
        // garbage left side, so EVERY non-forced announce is refused for good while the feed
        // simultaneously advertises no release - wedged in both directions at once.
        String stored = VersionComparator.canonicalOrNull(row == null ? null : row.getLatestVersion());
        if (!force && stored != null && !VersionComparator.isUpdateAvailable(stored, version)) {
            return false;
        }
        if (row == null) {
            repository.save(new CeRelease(version, url, securityFix, publishedAt));
        } else {
            row.apply(version, url, securityFix, publishedAt);
            repository.save(row);
        }
        // Cache the value we just wrote rather than clearing to null, and do it AFTER the commit.
        //   - after commit, because clearing inside the transaction lets a concurrent read
        //     repopulate from the pre-commit state and serve the OLD version for another full TTL,
        //     which is exactly the window the release job's verification lands in;
        //   - the value rather than null, because a null snapshot destroys the fallback that lets
        //     current() serve a stale answer during a database outage. Clearing it would mean the
        //     first read after an announcement has nothing to fall back on.
        publishAfterCommit(new Announced(version, url, securityFix, publishedAt));
        return true;
    }

    /** Publishes to this pod's cache once the write is durable, never before. */
    private void publishAfterCommit(Announced written) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNewest(written);
                }
            });
        } else {
            publishNewest(written);
        }
    }

    /**
     * Writes the announced release ONLY if there is none yet.
     *
     * <p>Writes from the row this method already read, and never re-reads. The previous version
     * checked emptiness and then delegated to {@code announce(..., force=true)}, which re-read the
     * row and, because force skips the monotonic guard, overwrote whatever it found by then. That
     * is not fixed by a lock: {@code SELECT ... FOR UPDATE} takes no lock on a row that does not
     * exist, which is the only state this method is for. So a release announced between the two
     * statements was silently replaced by the pin - the fleet walked backwards by the component
     * whose javadoc promised it could not.
     *
     * <p>The row always exists (V419 seeds it with a null version), so the locked read above takes
     * a real lock and a concurrent writer waits rather than racing. The previous wording claimed a
     * primary-key violation would protect an absent row; it would not. {@code CeRelease} carries an
     * assigned non-null id, so {@code save()} takes Hibernate's merge branch, merge issues its own
     * snapshot read, and the write comes out as an UPDATE - overwriting whatever committed in
     * between, with no constraint violation anywhere.
     *
     * @return true when this call is what filled the row
     */
    @Transactional
    public boolean announceIfAbsent(String version, String url, boolean securityFix, String publishedAt) {
        CeRelease row = repository.findByIdForUpdate(CeRelease.SINGLETON_ID).orElse(null);
        if (row != null && VersionComparator.canonicalOrNull(row.getLatestVersion()) != null) {
            return false;
        }
        if (row == null) {
            repository.save(new CeRelease(version, url, securityFix, publishedAt));
        } else {
            row.apply(version, url, securityFix, publishedAt);
            repository.save(row);
        }
        publishAfterCommit(new Announced(version, url, securityFix, publishedAt));
        return true;
    }

    /** Publishes a write: takes the next sequence number, so no in-flight read can undo it. */
    private void publishNewest(Announced value) {
        publish(new Snapshot(value, Instant.now().plus(ttl), sequence.incrementAndGet(), false));
    }

    /**
     * Installs {@code candidate} unless the cache already holds something strictly newer.
     *
     * <p>Without this, a read that queried the database BEFORE an announcement committed could
     * still write its stale result afterwards, and the pod that accepted the write would serve the
     * old version for a full TTL. That is exactly the window the release job's verification lands
     * in, and it is why the plain {@code cache.set} this replaces did not provide the guarantee its
     * comment claimed.
     */
    private void publish(Snapshot candidate) {
        while (true) {
            Snapshot current = cache.get();
            if (current != null && current.sequence() > candidate.sequence()) {
                return;
            }
            if (cache.compareAndSet(current, candidate)) {
                return;
            }
        }
    }

    /** Immutable view of the announced release. */
    public record Announced(String latestVersion, String releaseUrl, boolean securityFix, String publishedAt) {
    }

    private record Snapshot(Announced value, Instant expiresAt, long sequence, boolean unavailable) {
    }
}
