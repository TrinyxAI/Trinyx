-- On a fresh database, V148 has just added pin_id and expires_at and the ledger is empty.
-- Build the two indexes transactionally in that safe case. V149 and V150 then remain the
-- online, concurrent upgrade path for installations that already contain ledger rows.
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM auth.credit_ledger LIMIT 1) THEN
        CREATE INDEX IF NOT EXISTS idx_cl_pin_id_recent
            ON auth.credit_ledger (pin_id)
            WHERE pin_id IS NOT NULL;

        CREATE INDEX IF NOT EXISTS idx_cl_expires_pending
            ON auth.credit_ledger (expires_at)
            WHERE source_type = 'PLATFORM_MARKUP_RESERVE'
              AND expires_at IS NOT NULL;
    END IF;
END
$migration$;
