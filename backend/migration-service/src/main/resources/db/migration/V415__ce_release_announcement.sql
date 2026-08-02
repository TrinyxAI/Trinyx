-- V415: durable home for the CE release announcement.
--
-- The public feed /api/ce/releases/latest used to answer from ce.release.* config injected at
-- startup, which lives in the CLOUD deploy values. That coupled two independent cycles: announcing
-- a CE release required a cloud deploy, and every cloud deploy re-asserted whatever CE version was
-- pinned in that file. It drifted silently for months (0.1.22 advertised while v0.2.7 shipped), so
-- no self-hosted install was ever told an update existed.
--
-- Moving the value into a row lets the CE release run push it after its smoke-test passes, with no
-- cloud deploy, and lets cloud deploys carry no CE version at all. The ce.release.* config stays as
-- a manual override that still wins, for pinning, rolling back or hiding a bad release.
--
-- Single row by construction: the CHECK on a fixed id makes a second row impossible, so readers
-- never have to pick a winner.
CREATE TABLE IF NOT EXISTS auth.ce_release (
    id             smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    latest_version text,
    release_url    text,
    security_fix   boolean     NOT NULL DEFAULT false,
    published_at   text,
    updated_at     timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE auth.ce_release IS
    'Single-row CE release announcement served by /api/ce/releases/latest. Written by the CE release '
    'workflow via POST /api/ce/releases/announce; ce.release.* config overrides it when set.';

-- Deliberately NOT seeded. This migration also runs on every self-hosted install, so a seeded row
-- would make each CE box advertise a release on its own local feed. An empty table resolves to the
-- same nulls CE answers today.
--
-- The cloud has no gap either: ce.release.latest-version is currently pinned to the shipped release
-- and overrides the row, so the feed keeps answering through the cutover. Order of operations is
-- what makes that safe: deploy this + the read path (config still answers), let the next CE release
-- push the row, and only then blank the config so the announced value takes over.
