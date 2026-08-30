package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeInstallPing;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The cloud-side fleet ledger: a refresh, a guarded insert, a handful of aggregates, and a purge.
 *
 * <p>Extends the bare {@link Repository} marker rather than {@code JpaRepository}, so the methods
 * below are ALL that exist. There is deliberately no read of a single install: nothing in the
 * product needs to look one up, and a {@code findById} the type system hands out for free is what
 * turns an anonymous counter into a per-install tracker later, whatever the javadoc says.
 *
 * <p>The write is split in two on purpose. An upsert cannot tell the caller whether it created a
 * row, and that distinction is the whole difference between a bound that works and one that does
 * not: a sighting of an install already in the ledger cannot consume disk, while creating a row is the
 * only operation an anonymous caller can use to. Splitting them lets the ROW CEILING sit on the
 * second alone, so no flood can stop the real fleet being counted. Both carry a rate budget, sized
 * very differently: see {@code CeInstallPingRecorder}.
 */
@org.springframework.stereotype.Repository
public interface CeInstallPingRepository extends Repository<CeInstallPing, UUID> {

    /**
     * Refreshes an install already in the ledger, and reports whether it was there.
     *
     * <p>Not subject to the ledger's row ceiling: it cannot add an install, so that ceiling sits on
     * the insert below alone. Note the precise claim: an id the ledger does not hold matches zero
     * rows and writes nothing at all, which is the forged case the bound is about. A real install's
     * daily refresh does write, a new heap tuple plus index entries under MVCC, but that volume is
     * set by the size of the fleet rather than by anyone's choice. It carries a rate budget of its
     * own in {@code CeInstallPingRecorder}, far above what the fleet needs. {@code first_seen_at} is not in the SET list, which is what makes the
     * new-installs figure mean anything; a null version keeps the previously known one rather than
     * blanking it, so a request that omits it cannot erase what we already knew.
     *
     * @return 1 when the install existed, 0 when it did not
     */
    @Modifying
    @Transactional
    // Timeboxed: this runs inline on the request thread of the PUBLIC release feed, which the whole
    // fleet polls for security releases. Every other guard on this path defends against exceptions,
    // and a statement that is merely slow is not one: a contended row would otherwise hold that
    // thread until the database gave up. Pool acquisition is a separate wait that happens before a
    // statement exists, so this bounds the statement only; the recorder's failure backoff covers a
    // pool that is exhausted.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    @Query(value = """
            UPDATE auth.ce_install_ping
               SET last_seen_at = now(),
                   last_version = COALESCE(:version, last_version)
             WHERE install_id = :installId
            """, nativeQuery = true)
    int refreshSighting(@Param("installId") UUID installId, @Param("version") String version);

    /**
     * Adds an install the ledger has never seen.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than a plain insert because several cloud pods answer
     * the same public feed, so the first sighting of an install is genuinely concurrent and anything
     * else turns that into a primary-key violation on the endpoint the fleet polls for security
     * releases. It reports 0 in that case, which is correct: the row exists either way.
     *
     * <p>This is the only statement in the feature that consumes disk, which is why it is the only
     * one the recorder guards.
     *
     * @return 1 when a row was created, 0 when a concurrent caller got there first
     */
    @Modifying
    @Transactional
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    @Query(value = """
            INSERT INTO auth.ce_install_ping (install_id, first_seen_at, last_seen_at, last_version)
            VALUES (:installId, now(), now(), :version)
            ON CONFLICT (install_id) DO NOTHING
            """, nativeQuery = true)
    int insertSighting(@Param("installId") UUID installId, @Param("version") String version);

    /**
     * Installs the ledger has ever seen and not yet purged.
     *
     * <p>Declared rather than inherited so it can carry the same statement timeout as the writes.
     * {@code SELECT count(*)} is a heap scan in Postgres and grows with the very quantity the
     * ceiling exists to limit, and the recorder calls it on the request thread of the public feed;
     * leaving the slowest statement on that path as the only unguarded one would have been the
     * exception that matters.
     */
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    @Query(value = "SELECT count(*) FROM auth.ce_install_ping", nativeQuery = true)
    long count();

    /**
     * Every fleet figure in ONE statement: {@code [total, active_short, active_long, new_short,
     * new_long]}.
     *
     * <p>One statement, not five, because one statement is one snapshot. Five separate counts under
     * the default READ COMMITTED each take their own, so an install arriving between the first and
     * the last makes {@code active_long} exceed {@code total} and the dashboard's churn panel
     * ({@code total - active_long}) renders a negative number.
     *
     * <p>The obvious alternative, wrapping five counts in a REPEATABLE_READ transaction, was
     * written and then removed: every service reaches Postgres through PgBouncer in
     * {@code pool_mode = transaction}, whose {@code server_reset_query} is {@code DEALLOCATE ALL}
     * and therefore does NOT reset session isolation. pgjdbc sets the level with
     * {@code SET SESSION CHARACTERISTICS}, which under autocommit is its own implicit transaction,
     * so PgBouncer returns that server connection to the shared pool still at REPEATABLE READ and
     * unrelated auth writes start raising {@code 40001} serialization failures they do not retry.
     * A single statement needs no session state at all, and is one round trip instead of five.
     *
     * <p>Windows are half-open on the same side as the purge's, so a row is never both "seen" and
     * eligible for deletion.
     */
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    @Query(value = """
            SELECT count(*)                                                    AS total,
                   count(*) FILTER (WHERE last_seen_at  > :shortAgo)           AS active_short,
                   count(*) FILTER (WHERE last_seen_at  > :longAgo)            AS active_long,
                   count(*) FILTER (WHERE first_seen_at > :shortAgo)           AS new_short,
                   count(*) FILTER (WHERE first_seen_at > :longAgo)            AS new_long
              FROM auth.ce_install_ping
            """, nativeQuery = true)
    List<Object[]> fleetCounts(@Param("shortAgo") Instant shortAgo, @Param("longAgo") Instant longAgo);

    /**
     * Version distribution across installs seen since {@code since}, most common first.
     *
     * <p>Each row is {@code [version, count]}. This is what says whether a release actually reached
     * the fleet, as opposed to merely having been published.
     */
    // The most expensive read on the fleet path: a GROUP BY over every row inside the window.
    // Bounded like the rest, so no ordering of the handler's arguments can leave it unguarded.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    @Query(value = """
            SELECT COALESCE(last_version, 'unknown') AS version, COUNT(*) AS installs
              FROM auth.ce_install_ping
             WHERE last_seen_at > :since
             GROUP BY 1
             ORDER BY installs DESC, version ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> versionBreakdown(@Param("since") Instant since, @Param("limit") int limit);

    /**
     * Drops at most {@code batchSize} installs not seen since {@code cutoff}, and returns how many.
     *
     * <p>Storage limitation. Without it the table only ever grows: an install that was
     * decommissioned, or that opted out after one upgrade, keeps a row forever.
     *
     * <p>Keyed on {@code last_seen_at}, never {@code first_seen_at}: an install running happily for
     * two years would otherwise be deleted for being old, and the fleet count would shrink as it
     * aged.
     *
     * <p>Batched via {@code ctid} rather than one unbounded DELETE, because the run that most needs
     * to work is the run with the most rows to remove, and that is exactly where a single statement
     * means a long transaction, a large WAL burst and locks held throughout. The caller loops.
     */
    @Modifying
    @Transactional
    // The only statement here that deletes, and it was the only one with no bound. A batch blocked
    // on a row lock held by a concurrent refresh of the same install would otherwise wait
    // indefinitely, on Boot's single scheduler thread, past the ShedLock expiry that then lets a
    // second replica start the same deletes.
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "30000"))
    @Query(value = """
            DELETE FROM auth.ce_install_ping
             WHERE ctid IN (SELECT ctid FROM auth.ce_install_ping
                             WHERE last_seen_at < :cutoff
                             LIMIT :batchSize)
            """, nativeQuery = true)
    int purgeUnseenSince(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
