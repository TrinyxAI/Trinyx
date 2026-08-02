-- CE build: this migration retired a cloud-only launch promo that the community
-- edition never seeds, so there is nothing here to remove.
-- Kept as a no-op instead of dropped: images already shipped this version, and
-- deleting the file would strand it in their Flyway history with nothing on disk
-- to resolve against.
SELECT 1;
