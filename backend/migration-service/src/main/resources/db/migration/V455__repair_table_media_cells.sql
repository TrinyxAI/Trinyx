-- Repair table media cells that can no longer be displayed.
--
-- Two populations, both created by shipped code and never migrated:
--
--   1. Cells holding "/api/proxy/files/proxy?key=<urlencoded s3 key>". That endpoint was removed
--      with the opaque-URL cutover ("no back-compat - a legacy id-less ref renders broken by
--      design"), and no migration rewrote the rows, so these cells have rendered as a silently
--      missing file ever since. The s3 key inside the URL is still a valid handle on the row.
--
--   2. Cells holding a raw file reference with a "path" but neither "id" nor "url" - what the CRUD
--      coercer stored when it could not build a URL. It kept the value and warned, so the write
--      succeeded and the cell was simply unrenderable.
--
-- Both are repaired by finding the storage row again by its s3 key and writing the id-based URL.
--
-- SAFETY: the repair is driven by an exact join on storage.storage.s3_key. No match means no
-- change, so a key we decode wrongly, a file that was deleted, and a cell we do not understand are
-- all left exactly as they are. Re-running the migration is a no-op: the shapes it looks for no
-- longer match once repaired.
--
-- Deliberately NOT done here: reshaping healthy cells. A cell holding a working URL string keeps
-- it. Readers accept every vintage (ColumnValueCoercer server-side, parseAsset client-side), and
-- rewriting shapes that already display correctly would risk published interfaces for no gain.

DO $$
DECLARE
    media_columns  CONSTANT text[] := ARRAY['file', 'image'];
    item           RECORD;
    col_key        text;
    col_value      jsonb;
    inner_text     text;
    inner_json     jsonb;
    legacy_key     text;
    resolved_id    uuid;
    resolved_count int;
    new_url        text;
    repaired       int := 0;
    unresolved     int := 0;
BEGIN
    -- Which tables carry a media column is decided ONCE, in a CTE joined to the item rows, rather
    -- than by a correlated jsonb_each over the mapping spec evaluated for every item in the
    -- database. Deliberately NOT a temp table: a fixed global name collides if this ever runs twice
    -- in one transaction, which is exactly how the idempotency check caught it.
    FOR item IN
        WITH media_tables AS (
            SELECT ds.id, ds.mapping_spec
              FROM datasource.data_sources ds
             WHERE jsonb_typeof(ds.mapping_spec) = 'object'
               AND EXISTS (
                   SELECT 1
                   FROM jsonb_each(ds.mapping_spec) spec
                   WHERE jsonb_typeof(spec.value) = 'object'
                     AND lower(coalesce(spec.value ->> 'type', spec.value ->> 'visual_type', '')) = ANY (media_columns)
               )
        )
        SELECT i.id AS item_id,
               i.data,
               i.tenant_id,
               mt.mapping_spec
        FROM datasource.data_source_items i
        JOIN media_tables mt ON mt.id = i.data_source_id
        WHERE jsonb_typeof(i.data) = 'object'
    LOOP
        FOR col_key IN
            SELECT spec.key
            FROM jsonb_each(item.mapping_spec) spec
            WHERE jsonb_typeof(spec.value) = 'object'
              AND lower(coalesce(spec.value ->> 'type', spec.value ->> 'visual_type', '')) = ANY (media_columns)
        LOOP
            -- mapping_spec keys are bare column names; the stored path may carry a "data." prefix.
            col_value := item.data -> regexp_replace(col_key, '^data\.', '');
            CONTINUE WHEN col_value IS NULL OR jsonb_typeof(col_value) = 'null';

            legacy_key := NULL;
            inner_json := NULL;

            IF jsonb_typeof(col_value) = 'string' THEN
                inner_text := col_value #>> '{}';
                IF inner_text IS NULL THEN
                    CONTINUE;
                END IF;
                -- The CRUD write path persists a media map as a JSON *string*, so look inside.
                IF left(btrim(inner_text), 1) = '{' THEN
                    BEGIN
                        inner_json := inner_text::jsonb;
                    EXCEPTION WHEN others THEN
                        inner_json := NULL;
                    END;
                    IF inner_json IS NOT NULL THEN
                        legacy_key := substring(coalesce(inner_json ->> 'url', '') FROM '/files/proxy\?key=([^&"]+)');
                        IF legacy_key IS NULL
                           AND inner_json ->> 'id' IS NULL
                           AND coalesce(inner_json ->> 'url', '') = ''
                        THEN
                            legacy_key := inner_json ->> 'path';
                        END IF;
                    END IF;
                ELSE
                    legacy_key := substring(inner_text FROM '/files/proxy\?key=([^&]+)');
                END IF;

            ELSIF jsonb_typeof(col_value) = 'object' THEN
                legacy_key := substring(coalesce(col_value ->> 'url', '') FROM '/files/proxy\?key=([^&]+)');
                IF legacy_key IS NULL
                   AND col_value ->> 'id' IS NULL
                   AND coalesce(col_value ->> 'url', '') = ''
                THEN
                    legacy_key := col_value ->> 'path';
                END IF;
            END IF;

            CONTINUE WHEN legacy_key IS NULL OR legacy_key = '';

            -- Percent-decoding, limited to what an s3 key of the form
            -- "<tenant>/general/<category>/<8hex>_<sanitized name>" can actually contain. A key we
            -- decode wrongly simply fails the join below and is left untouched.
            legacy_key := replace(replace(replace(replace(legacy_key,
                              '%2F', '/'), '%2f', '/'), '%20', ' '), '%2B', '+');

            -- min() over the id as text: uuid has no aggregate, and the value is only used when
            -- the count is exactly 1 anyway.
            SELECT count(*), min(s.id::text)::uuid
              INTO resolved_count, resolved_id
              FROM storage.storage s
             WHERE s.s3_key = legacy_key
               AND s.status = 'ACTIVE'
               -- Defence in depth. S3 keys are tenant-prefixed, so a cross-tenant collision should
               -- be impossible; a repair is the wrong place to depend on "should".
               AND (item.tenant_id IS NULL OR s.tenant_id = item.tenant_id);

            IF resolved_count <> 1 THEN
                -- 0 = the file is gone (or the key never decoded cleanly); >1 = ambiguous.
                -- Either way the cell keeps its current value rather than gaining a wrong one.
                unresolved := unresolved + 1;
                CONTINUE;
            END IF;

            new_url := '/api/proxy/files/by-id/' || resolved_id::text || '/raw?disposition=inline';

            IF jsonb_typeof(col_value) = 'object' THEN
                UPDATE datasource.data_source_items
                   SET data = jsonb_set(data,
                                        ARRAY[regexp_replace(col_key, '^data\.', '')],
                                        col_value
                                          || jsonb_build_object('id', resolved_id::text)
                                          || jsonb_build_object('url', new_url),
                                        true)
                 WHERE id = item.item_id;

            ELSIF inner_json IS NOT NULL THEN
                UPDATE datasource.data_source_items
                   SET data = jsonb_set(data,
                                        ARRAY[regexp_replace(col_key, '^data\.', '')],
                                        to_jsonb((inner_json
                                                    || jsonb_build_object('id', resolved_id::text)
                                                    || jsonb_build_object('url', new_url))::text),
                                        true)
                 WHERE id = item.item_id;

            ELSE
                UPDATE datasource.data_source_items
                   SET data = jsonb_set(data,
                                        ARRAY[regexp_replace(col_key, '^data\.', '')],
                                        to_jsonb(new_url),
                                        true)
                 WHERE id = item.item_id;
            END IF;

            repaired := repaired + 1;
        END LOOP;
    END LOOP;

    RAISE NOTICE 'Table media repair: % cell(s) re-pointed at a live file, % left untouched (file gone or key ambiguous)',
                 repaired, unresolved;
END $$;
