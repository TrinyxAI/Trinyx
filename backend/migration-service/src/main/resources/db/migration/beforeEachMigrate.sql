-- Flyway SQL callback: reset schema resolution before every migration.
--
-- Historical migrations can leave session-scoped search_path and timeout GUCs behind.
-- Flyway reuses connections, so the next migration can otherwise resolve unqualified tables
-- against the wrong schema or inherit a lock/statement timeout that is incompatible with its
-- own operation (notably CREATE INDEX CONCURRENTLY).
RESET lock_timeout;
RESET statement_timeout;
SET search_path TO orchestrator, public;
