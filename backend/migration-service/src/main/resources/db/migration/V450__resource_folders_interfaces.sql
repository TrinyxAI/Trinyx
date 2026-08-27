-- V450: folders for the /app/interface list. Same shape as V448 (workflows) and V449
-- (agents); the reason folders live per service is spelled out in V448.
--
-- No FK on either column: the folder cascade and the "content goes back to the top level"
-- rule are enforced in ResourceFolderCoreService, so deleting a way of filing pages can
-- never delete a page.

CREATE TABLE IF NOT EXISTS interface.interface_folders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(120) NOT NULL,
    parent_folder_id UUID,
    owner_id         VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_interface_folders_org
    ON interface.interface_folders (organization_id, parent_folder_id);

CREATE INDEX IF NOT EXISTS idx_interface_folders_owner
    ON interface.interface_folders (owner_id);

ALTER TABLE interface.interfaces
    ADD COLUMN IF NOT EXISTS folder_id UUID;

CREATE INDEX IF NOT EXISTS idx_interfaces_folder
    ON interface.interfaces (organization_id, folder_id);

COMMENT ON TABLE interface.interface_folders IS
    'User-created folders for the /app/interface list. Nestable via parent_folder_id, workspace-scoped. Mapped by InterfaceFolderEntity (extends common-lib AbstractResourceFolderEntity).';
COMMENT ON COLUMN interface.interfaces.folder_id IS
    'Folder this page is filed under (interface.interface_folders.id). NULL = top level. Independent of project_id.';
