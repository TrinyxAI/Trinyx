-- V419: make the announced-release row always exist, with no version in it.
--
-- Why a second migration rather than editing V415: V415 is already applied in production (the
-- announce endpoint answers there), and Flyway checksums an applied migration - editing it would
-- fail every subsequent boot.
--
-- The defect this closes. auth.ce_release starts empty, and both write paths read it with
-- SELECT ... FOR UPDATE before deciding whether to write. That takes NO lock on a row which does
-- not exist, which is precisely the state they are for. Worse, CeRelease carries an assigned
-- non-null @Id, so Spring Data's save() takes the merge() branch: merge issues its own snapshot
-- SELECT, sees a row committed after the lock-free read, and emits an UPDATE. The result was a
-- release announced between the two statements being silently replaced - the fleet walked
-- backwards by the very code whose javadoc promised it could not, and no primary-key violation
-- ever occurred to stop it.
--
-- With the row always present, FOR UPDATE takes a real lock, the guard and the write become one
-- atomic step, and a concurrent writer waits instead of racing.
--
-- latest_version stays NULL on purpose. The store treats a null or unparseable version as "nothing
-- announced", and the feed answers "no release" for it - byte for byte what CE answers today. This
-- migration also runs on every self-hosted install, so it must not invent a release for them.
INSERT INTO auth.ce_release (id, latest_version, release_url, security_fix)
VALUES (1, NULL, NULL, false)
ON CONFLICT (id) DO NOTHING;
