-- V454: anonymous install identity + the cloud-side ping ledger behind it.
--
-- The gap this closes. Every self-hosted install already calls the cloud's public
-- /api/ce/releases/latest daily (CeVersionCheckScheduler) to learn whether it is behind, and that
-- call carries NO identifier. So the fleet is invisible: we cannot tell 5 live installs from 500,
-- cannot measure whether anyone stays past the first week, and cannot see which release a fleet
-- actually upgraded to. Every other number we have about adoption is either self-reported or
-- gameable; this one is not.
--
-- What is recorded, and what deliberately is NOT. auth.ce_install holds one random UUID per
-- install. It is derived from nothing: not the IP, not the hostname, not a licence, not a user.
-- The cloud stores that UUID, the running version, and two timestamps: first seen and last seen.
-- That is the complete list. There is deliberately no sighting counter, because nothing reads one
-- and a per-pod cache would make it unreliable anyway. It never stores the IP: an IP identifies a
-- subscriber, an opaque per-instance UUID identifies an instance, and we sell sovereignty, so that
-- distinction has to hold in the schema and not only in the docs. Note what this does NOT claim:
-- the request still reaches the edge with an IP the web server logs, like any HTTP call, so a small
-- fleet stays correlatable by joining that log against these timestamps. The guarantee is about
-- what this table holds.
-- The install id is also deliberately NOT the cloud-link install id
-- (publication.ce_cloud_link.install_id): that one exists only once an install links to the cloud
-- and is bound to a tenant, so reusing it would both miss every unlinked install (the population
-- we are blind to) and tie an anonymous counter to an identified account.
--
-- Sending it is opt-out (ce.version-check.send-install-id), and disabling the update check at all
-- (ce.version-check.enabled=false) already stops the request that would carry it.

-- ---------------------------------------------------------------------------
-- CE side: this install's own identity.
-- ---------------------------------------------------------------------------
-- Single row, pinned by a CHECK, exactly like auth.ce_release: readers never have to pick a winner.
CREATE TABLE IF NOT EXISTS auth.ce_install (
    id          SMALLINT    NOT NULL PRIMARY KEY,
    install_id  UUID        NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ce_install_singleton CHECK (id = 1)
);

-- Seeded HERE rather than generated on first read, so two pods booting together cannot mint two
-- identities for one install. The same reasoning as V419: a row that always exists removes the
-- race instead of asking every reader to handle it.
--
-- This also runs on the cloud deployment (the schema is shared), where the row is simply never
-- read: only the embedded edition polls the feed. One unused row is a smaller price than a
-- runtime create-if-absent path that has to be correct under concurrency.
--
-- An install upgrading from an earlier version gets its identity here, which is what makes the
-- EXISTING fleet visible on upgrade rather than only new installs.
INSERT INTO auth.ce_install (id, install_id)
VALUES (1, gen_random_uuid())
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Cloud side: one row per install that has ever pinged.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS auth.ce_install_ping (
    install_id    UUID        NOT NULL PRIMARY KEY,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_version  VARCHAR(64)
);

-- Every stats query is "how many installs were seen since <date>", so the ledger is read by
-- last_seen_at and never by primary key.
CREATE INDEX IF NOT EXISTS idx_ce_install_ping_last_seen
    ON auth.ce_install_ping (last_seen_at DESC);

-- "How many installs are NEW since <date>", read twice per stats call. Deliberately a second
-- index rather than a composite with last_seen_at: the two windows are queried independently.
--
-- There is no index on last_version. The only query touching it groups by it under a
-- last_seen_at predicate, which drives off the index above; a single-column index on the grouped
-- column would not be used and would still be maintained on every upsert, and last_seen_at
-- changes on every write so HOT updates are already impossible here.
CREATE INDEX IF NOT EXISTS idx_ce_install_ping_first_seen
    ON auth.ce_install_ping (first_seen_at DESC);

COMMENT ON TABLE auth.ce_install IS
    'This install''s anonymous identity (one random UUID, derived from nothing). Sent on the daily release-feed poll unless ce.version-check.send-install-id=false. Unused on the cloud deployment.';
COMMENT ON TABLE auth.ce_install_ping IS
    'Cloud-side ledger of self-hosted installs seen on the public release feed. Anonymous by construction: no IP, no hostname, no account is stored here. Rows unseen for ce.installs.telemetry.retention-days are purged.';
