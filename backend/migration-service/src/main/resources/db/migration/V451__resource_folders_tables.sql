-- V451: folders for the /app/tables list.
--
-- Same folder table as V448/V449/V450, but the MEMBERSHIP is a separate row rather than a
-- column on the resource. Two reasons, both specific to this service:
--   * data_sources is read through a Java record built at ~40 call sites, so adding a
--     component to it would touch every one of them for a purely organisational field;
--   * datasource-service is JDBC, not JPA, for its own domain - a join table it reads with
--     one small query fits it better than an entity column.
-- The trade-off is one extra (tiny, indexed) read per list request, and it is the same shape
-- the applications list needs anyway (a publication row is shared, so its filing cannot live
-- on it).
--
-- No FK, as everywhere else in this feature: the cascade and the "content goes back to the
-- top level" rule are enforced in the service, so deleting a folder never deletes a table.

CREATE TABLE IF NOT EXISTS datasource.datasource_folders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(120) NOT NULL,
    parent_folder_id UUID,
    owner_id         VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_datasource_folders_org
    ON datasource.datasource_folders (organization_id, parent_folder_id);

CREATE INDEX IF NOT EXISTS idx_datasource_folders_owner
    ON datasource.datasource_folders (owner_id);

-- One row per filed table. A table filed nowhere simply has no row here, which is what the
-- top level lists.
CREATE TABLE IF NOT EXISTS datasource.datasource_folder_items (
    data_source_id  BIGINT NOT NULL,
    folder_id       UUID NOT NULL,
    organization_id VARCHAR(255),
    owner_id        VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (data_source_id)
);

-- The list's own read: every filing of the active workspace, in one query.
CREATE INDEX IF NOT EXISTS idx_datasource_folder_items_scope
    ON datasource.datasource_folder_items (organization_id, folder_id);

CREATE INDEX IF NOT EXISTS idx_datasource_folder_items_owner
    ON datasource.datasource_folder_items (owner_id, folder_id);

COMMENT ON TABLE datasource.datasource_folders IS
    'User-created folders for the /app/tables list. Nestable via parent_folder_id, workspace-scoped.';
COMMENT ON TABLE datasource.datasource_folder_items IS
    'Which folder each table is filed under. Absent = top level. Kept out of data_sources so the DataSource record (built at ~40 sites) stays untouched.';
