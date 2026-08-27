-- V452: folders for the /app/applications list, the fifth and last of the series
-- (V448 workflows, V449 agents, V450 interfaces, V451 tables).
--
-- The membership is a row of its own, as for tables (V451), but here for a stronger reason:
-- an application IS a publication, and a publication row is SHARED - the publisher's row is
-- the same row every acquirer sees, and a cloud-acquired app has no local row at all. Its
-- filing therefore belongs to the workspace that filed it, never to the publication.
--
-- No FK on publication_id, deliberately: a cloud-acquired application's id names a row that
-- lives on another install.

CREATE TABLE IF NOT EXISTS publication.application_folders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(120) NOT NULL,
    parent_folder_id UUID,
    owner_id         VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_application_folders_org
    ON publication.application_folders (organization_id, parent_folder_id);

CREATE INDEX IF NOT EXISTS idx_application_folders_owner
    ON publication.application_folders (owner_id);

-- One row per filed application, per workspace. An application filed nowhere simply has no
-- row here, which is what the top level lists.
CREATE TABLE IF NOT EXISTS publication.application_folder_items (
    publication_id  UUID NOT NULL,
    organization_id VARCHAR(255) NOT NULL DEFAULT '',
    folder_id       UUID NOT NULL,
    owner_id        VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (publication_id, organization_id)
);

-- The list's own read: every filing of the active workspace, in one query.
CREATE INDEX IF NOT EXISTS idx_application_folder_items_scope
    ON publication.application_folder_items (organization_id, folder_id);

COMMENT ON TABLE publication.application_folders IS
    'User-created folders for the /app/applications list. Nestable via parent_folder_id, workspace-scoped.';
COMMENT ON TABLE publication.application_folder_items IS
    'Which folder each application is filed under, PER WORKSPACE - a publication row is shared (and may live on another install), so its filing cannot be a column on it. organization_id is '''' for a personal workspace so the key stays well-defined.';
