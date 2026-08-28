#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');
const gateway = read('backend/gateway-service/src/main/resources/application.yml');

function fail(message) {
  throw new Error(message);
}

function routeBlock(id) {
  const marker = '            - id: ' + id;
  const start = gateway.indexOf(marker);
  if (start < 0) fail('Missing Gateway route: ' + id);
  const end = gateway.indexOf('\n            - id:', start + marker.length);
  return gateway.slice(start, end < 0 ? gateway.length : end);
}

function requireText(text, expected, context) {
  if (!text.includes(expected)) fail(context + ' is missing ' + JSON.stringify(expected));
}

const features = [
  ['workflow execution', 'orchestrator-workflows-v2', 'ORCHESTRATOR_SERVICE_URL', '/api/v2/workflows/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/workflow/WorkflowExecutionController.java',
    ['@RestController', '@RequestMapping("/api/v2/workflows/dag")', '@PostMapping("/execute")']],
  ['subworkflow wait and signals', 'orchestrator-workflows-v2', 'ORCHESTRATOR_SERVICE_URL', '/api/v2/workflows/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/workflow/WorkflowSignalController.java',
    ['@RestController', '@RequestMapping("/api/v2/workflows/dag/runs/{runId}/signals")']],
  ['agents', 'agent-application', 'AGENT_SERVICE_URL', '/api/agents/**',
    'backend/agent-service/src/main/java/com/apimarketplace/agent/controller/AgentController.java',
    ['@RestController', '@RequestMapping("/api/agents")']],
  ['interfaces CRUD', 'interface-application', 'INTERFACE_SERVICE_URL', '/api/interfaces/**',
    'backend/interface-service/src/main/java/com/apimarketplace/interfaces/controller/InterfaceController.java',
    ['@RestController', '@RequestMapping("/api/interfaces")']],
  ['interfaces runtime facade', 'orchestrator-interface-runtime', 'ORCHESTRATOR_SERVICE_URL', '/api/interfaces/*/render',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/interfaces/InterfaceController.java',
    ['@RestController', '@RequestMapping("/api/interfaces")', '@GetMapping("/{id}/render")']],
  ['datasources', 'datasource-application', 'DATASOURCE_SERVICE_URL', '/api/data-sources/**',
    'backend/datasource-service/src/main/java/com/apimarketplace/datasource/controllers/datasource/DataSourceCrudController.java',
    ['@RestController', '@RequestMapping("/api/data-sources")']],
  ['applications', 'publication-application', 'PUBLICATION_SERVICE_URL', '/api/publications/**',
    'backend/publication-service/src/main/java/com/apimarketplace/publication/controller/WorkflowPublicationController.java',
    ['@RestController', '@RequestMapping("/api/publications")']],
  ['application folders', 'publication-application', 'PUBLICATION_SERVICE_URL', '/api/application-folders/**',
    'backend/publication-service/src/main/java/com/apimarketplace/publication/controller/ApplicationFolderController.java',
    ['@RestController', '@RequestMapping("/api/application-folders")']],
  ['agent folders', 'agent-application', 'AGENT_SERVICE_URL', '/api/agent-folders/**',
    'backend/agent-service/src/main/java/com/apimarketplace/agent/controller/AgentFolderController.java',
    ['@RestController', '@RequestMapping("/api/agent-folders")', 'extends AbstractResourceFolderController']],
  ['table folders', 'datasource-application', 'DATASOURCE_SERVICE_URL', '/api/table-folders/**',
    'backend/datasource-service/src/main/java/com/apimarketplace/datasource/controllers/datasource/DataSourceFolderController.java',
    ['@RestController', '@RequestMapping("/api/table-folders")', 'extends AbstractResourceFolderController']],
  ['interface folders', 'interface-application', 'INTERFACE_SERVICE_URL', '/api/interface-folders/**',
    'backend/interface-service/src/main/java/com/apimarketplace/interfaces/controller/InterfaceFolderController.java',
    ['@RestController', '@RequestMapping("/api/interface-folders")', 'extends AbstractResourceFolderController']],
  ['workflow folders', 'orchestrator-application', 'ORCHESTRATOR_SERVICE_URL', '/api/workflow-folders/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/folder/WorkflowFolderController.java',
    ['@RestController', '@RequestMapping("/api/workflow-folders")', 'extends AbstractResourceFolderController']],
  ['free Marketplace snapshot and acquire', 'publication-application', 'PUBLICATION_SERVICE_URL', '/api/ce-marketplace/**',
    'backend/publication-service/src/main/java/com/apimarketplace/publication/controller/CeDownloadController.java',
    ['@RestController', '@RequestMapping("/api/ce-marketplace")', '@PostMapping("/{publicationId}/acquire-with-auth")']],
  ['paid Marketplace acquire, reinstall and editable twin', 'publication-application', 'PUBLICATION_SERVICE_URL', '/api/publications/**',
    'backend/publication-service/src/main/java/com/apimarketplace/publication/controller/RemoteMarketplaceController.java',
    ['@RestController', '@RequestMapping("/api/publications/remote")', '@PostMapping("/{publicationId}/acquire")',
      '@PostMapping("/{publicationId}/editable-workflow")', 'marketplace.mode']],
  ['credentials and selectors', 'auth-application', 'AUTH_SERVICE_URL', '/api/credentials/**',
    'backend/auth-service/src/main/java/com/apimarketplace/auth/credential/web/CredentialController.java',
    ['@RestController', '@RequestMapping("/api/credentials")']],
  ['conversations and tool approvals', 'conversation-application', 'CONVERSATION_SERVICE_URL', '/api/conversations/**',
    'backend/conversation-service/src/main/java/com/apimarketplace/conversation/controller/ConversationController.java',
    ['@RestController', '@RequestMapping("/api/conversations")', '@PostMapping("/{conversationId}/tool-authorization/approve")']],
  ['generation modalities', 'catalog-application', 'CATALOG_SERVICE_URL', '/api/generation/**',
    'backend/catalog-service/src/main/java/com/apimarketplace/catalog/web/GenerationController.java',
    ['@RestController', '@PostMapping("/api/generation/execute")', 'generation.enabled']],
  ['storage', 'storage-application', 'STORAGE_SERVICE_URL', '/api/storage/**',
    'backend/storage-service/src/main/java/com/apimarketplace/storage/web/StorageController.java',
    ['@RestController', '@RequestMapping("/api/storage")']],
  ['storage explorer and provenance history', 'orchestrator-storage-facade', 'ORCHESTRATOR_SERVICE_URL', '/api/storage/explorer/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/storage/StorageExplorerController.java',
    ['@RestController', '@RequestMapping("/api/storage/explorer")', '@GetMapping("/generations")']],
  ['model catalog and native discovery', 'agent-application', 'AGENT_SERVICE_URL', '/api/model-config/**',
    'backend/agent-service/src/main/java/com/apimarketplace/agent/catalog/sync/ModelCatalogSyncController.java',
    ['@RestController', '@RequestMapping("/api/model-config")', '@PostMapping("/catalog-sync")']],
  ['MCP', 'orchestrator-application', 'ORCHESTRATOR_SERVICE_URL', '/api/mcp/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/mcp/McpServerController.java',
    ['@RestController', '@RequestMapping("/api/mcp")', '@PostMapping("/tools/call")']],
  ['websearch', 'orchestrator-application', 'ORCHESTRATOR_SERVICE_URL', '/api/ce-websearch/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/cloud/CloudWebSearchRelayController.java',
    ['@RestController', '@RequestMapping("/api/ce-websearch")', '@PostMapping("/search")']],
  ['browser agent', 'orchestrator-application', 'ORCHESTRATOR_SERVICE_URL', '/api/browser-agent/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/controllers/cloud/BrowserAgentLlmShimController.java',
    ['@RestController', '@RequestMapping("/api/browser-agent/llm")', '@PostMapping("/v1/chat/completions")']],
  ['public and shared applications', 'orchestrator-app-public', 'ORCHESTRATOR_SERVICE_URL', '/app/public/**',
    'backend/orchestrator-service/src/main/java/com/apimarketplace/orchestrator/trigger/PublicApplicationController.java',
    ['@RestController', '@RequestMapping("/api/internal/app/public")', '@GetMapping("/{token}/config")']],
  ['standalone webhooks', 'trigger-application', 'TRIGGER_SERVICE_URL', '/api/webhooks/**',
    'backend/trigger-service/src/main/java/com/apimarketplace/trigger/controller/StandaloneWebhookController.java',
    ['@RestController', '@RequestMapping("/api/webhooks")']],
  ['workspace deletion', 'auth-application', 'AUTH_SERVICE_URL', '/api/organizations/**',
    'backend/auth-service/src/main/java/com/apimarketplace/auth/web/OrganizationController.java',
    ['@RestController', '@RequestMapping("/api/organizations")', '@DeleteMapping("/{orgId}")']],
];

for (const [name, routeId, target, pattern, controllerPath, controllerMarkers] of features) {
  const block = routeBlock(routeId);
  requireText(block, target, name + ' route target');
  requireText(block, pattern, name + ' route predicate');
  const controller = read(controllerPath);
  for (const marker of controllerMarkers) requireText(controller, marker, name + ' controller');
}

const folderBase = read('backend/common-lib/src/main/java/com/apimarketplace/common/folder/AbstractResourceFolderController.java');
for (const marker of ['@GetMapping', '@PostMapping', '@PutMapping("/{folderId}")',
  '@DeleteMapping("/{folderId}")', '@PostMapping("/items")']) {
  requireText(folderBase, marker, 'shared folder lifecycle');
}

const monolithPom = read('backend/monolith-service/pom.xml');
for (const service of ['auth-service', 'agent-service', 'conversation-service', 'catalog-service',
  'datasource-service', 'interface-service', 'trigger-service', 'publication-service',
  'storage-service', 'orchestrator-service']) {
  requireText(monolithPom, '<artifactId>' + service + '</artifactId>', 'monolith surface');
}
const ceConfig = read('backend/monolith-service/src/main/resources/application-ce.yml');
for (const marker of ['edition: ${APP_EDITION:ce}', 'mode: ${DEPLOYMENT_MODE:monolith}',
  'mode: ${MARKETPLACE_MODE:remote}']) {
  requireText(ceConfig, marker, 'CE/paid monolith profile');
}
const cloudCompose = read('docker/docker-compose.cloud.yml');
for (const marker of ['APP_EDITION: cloud', 'DEPLOYMENT_MODE: microservice',
  'MARKETPLACE_MODE: local', 'gateway-service:', 'publication-service:',
  'orchestrator-service:', 'storage-service:']) {
  requireText(cloudCompose, marker, 'distributed Cloud profile');
}
const backendWorkflow = read('.github/workflows/build-trinyx-backend.yml');
for (const marker of ['APP_EDITION=paid-monolith', 'MARKETPLACE_MODE=local',
  'CLOUD_LINK_ENABLED=true', '/actuator/health/liveness', '"edition":"paid-monolith"']) {
  requireText(backendWorkflow, marker, 'paid-monolith runtime smoke');
}

console.log('Validated ' + features.length
  + ' Phase 2 route -> service -> controller contracts across CE, paid-monolith and distributed Cloud.');
