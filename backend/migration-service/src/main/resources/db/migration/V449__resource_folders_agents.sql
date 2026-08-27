-- V449: folders for the /app/agent list. Second service to get them; the shape is the one
-- V448 established for workflows, and the reason it is per-service is spelled out there:
-- the list endpoint that filters on the folder is served by THIS service, and a service
-- only ever queries its own schema.
--
-- As in V448 there is no FK, on either column: the folder cascade and the "content goes
-- back to the top level" rule are enforced in ResourceFolderCoreService, so that deleting
-- a way of filing agents can never delete an agent.

CREATE TABLE IF NOT EXISTS agent.agent_folders (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(120) NOT NULL,
    parent_folder_id UUID,
    owner_id         VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_folders_org
    ON agent.agent_folders (organization_id, parent_folder_id);

CREATE INDEX IF NOT EXISTS idx_agent_folders_owner
    ON agent.agent_folders (owner_id);

ALTER TABLE agent.agents
    ADD COLUMN IF NOT EXISTS folder_id UUID;

CREATE INDEX IF NOT EXISTS idx_agents_folder
    ON agent.agents (organization_id, folder_id);

COMMENT ON TABLE agent.agent_folders IS
    'User-created folders for the /app/agent list. Nestable via parent_folder_id, workspace-scoped. Mapped by AgentFolderEntity (extends common-lib AbstractResourceFolderEntity).';
COMMENT ON COLUMN agent.agents.folder_id IS
    'Folder this agent is filed under (agent.agent_folders.id). NULL = top level. Independent of project_id.';
