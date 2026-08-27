-- V448: user-created FOLDERS for the resource list pages (phase 1: workflows).
--
-- Goal: /app/workflow (and, in the following phases, agents / tables / interfaces /
-- applications) can be organised into folders exactly like the Files browser, with a
-- tile that previews what the folder holds.
--
-- WHY A FOLDER TABLE PER SERVICE, not one central table:
--   Each list page is served by its OWN service (workflows -> orchestrator, agents ->
--   agent, interfaces -> interface, tables -> datasource, applications -> publication),
--   and each of those endpoints filters + paginates SERVER-SIDE. A folder filter has to
--   run inside that same query, so the folder rows must live in the schema the querying
--   service owns (cross-schema SQL is forbidden, and no orchestrator-client exists for
--   the other services to call back into). Every service therefore gets its own
--   `<resource>_folders` table with the SAME columns, all mapped by the shared
--   AbstractResourceFolderEntity / ResourceFolderCoreService in common-lib - one shape,
--   five owners, zero cross-service traffic on the list path.
--
-- This is deliberately NOT the existing `projects` grouping: a project is a
-- cross-resource collaboration space (members, roles, archive, its own detail page) and
-- a resource carries exactly one project_id. Folders are a per-list, nestable filing
-- system, so they get their own column and can be combined with a project.
--
-- No FK on parent_folder_id (same app-managed-lifecycle convention as storage V313):
-- the cascade on delete and the cycle guard are enforced in ResourceFolderCoreService.
-- No FK on workflows.folder_id either - deleting a folder re-files its workflows at the
-- top level (a folder NEVER deletes a user resource), which the service does explicitly.

CREATE TABLE IF NOT EXISTS orchestrator.workflow_folders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(120) NOT NULL,
    parent_folder_id UUID,
    owner_id         VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Primary access pattern: every folder of the active workspace (the whole tree is
-- loaded at once - it is small, and the breadcrumb + "move to" picker need all of it).
CREATE INDEX IF NOT EXISTS idx_workflow_folders_org
    ON orchestrator.workflow_folders (organization_id, parent_folder_id);

-- Personal-workspace fallback (organization_id NULL) + ownership checks.
CREATE INDEX IF NOT EXISTS idx_workflow_folders_owner
    ON orchestrator.workflow_folders (owner_id);

-- The membership itself: NULL = top level (the list page's root view).
ALTER TABLE orchestrator.workflows
    ADD COLUMN IF NOT EXISTS folder_id UUID;

CREATE INDEX IF NOT EXISTS idx_workflows_folder
    ON orchestrator.workflows (organization_id, folder_id);

COMMENT ON TABLE orchestrator.workflow_folders IS
    'User-created folders for the /app/workflow list. Nestable via parent_folder_id, workspace-scoped. Mapped by WorkflowFolderEntity (extends common-lib AbstractResourceFolderEntity).';
COMMENT ON COLUMN orchestrator.workflows.folder_id IS
    'Folder this workflow is filed under (orchestrator.workflow_folders.id). NULL = top level. Independent of project_id: a workflow can be in a project AND in a folder.';
