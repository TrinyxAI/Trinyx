-- Flyway SQL callback: reset schema resolution before every migration.
--
-- Historical migrations can leave session-scoped search_path and timeout GUCs behind.
-- Flyway reuses connections, so the next migration can otherwise resolve unqualified tables
-- against the wrong schema or inherit a lock/statement timeout that is incompatible with its
-- own operation (notably CREATE INDEX CONCURRENTLY).
RESET lock_timeout;
RESET statement_timeout;
SET search_path TO orchestrator, public;

-- A clean database has no ledger rows when V148 adds pin_id/expires_at. Pre-create the two
-- indexes transactionally in that one safe case so Flyway's own bookkeeping transaction cannot
-- make the following CREATE INDEX CONCURRENTLY scripts wait on a fresh bootstrap. Existing
-- installations with ledger data skip this block and retain the online concurrent builds.
DO $
DECLARE
    ledger_is_empty BOOLEAN;
BEGIN
    IF EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'auth'
           AND table_name = 'credit_ledger'
           AND column_name = 'pin_id'
    ) AND EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'auth'
           AND table_name = 'credit_ledger'
           AND column_name = 'expires_at'
    ) THEN
        EXECUTE 'SELECT NOT EXISTS (SELECT 1 FROM auth.credit_ledger LIMIT 1)'
           INTO ledger_is_empty;
        IF ledger_is_empty THEN
            EXECUTE 'CREATE INDEX IF NOT EXISTS idx_cl_pin_id_recent '
                 || 'ON auth.credit_ledger (pin_id) WHERE pin_id IS NOT NULL';
            EXECUTE 'CREATE INDEX IF NOT EXISTS idx_cl_expires_pending '
                 || 'ON auth.credit_ledger (expires_at) '
                 || 'WHERE source_type = ''PLATFORM_MARKUP_RESERVE'' '
                 || 'AND expires_at IS NOT NULL';
        END IF;
    END IF;
END $;
