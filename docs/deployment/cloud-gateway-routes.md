# Cloud gateway route inventory

The Cloud edge sends application traffic to `gateway-service`. The gateway validates
Keycloak identity, resolves the signed Trinyx binding/entitlement, strips spoofable headers,
and signs the final downstream target. It deliberately has no catch-all `/api/**` route.

| Public route family | Target | Notes |
|---|---|---|
| `/api/auth/**`, `/api/users/**`, `/api/billing/**`, `/api/organizations/**` and the other explicit auth families | auth-service:8083 | Browser/control-plane APIs |
| `/api/apis/**`, `/api/catalog/**`, `/api/tools/**`, `/catalog/v1/**` | catalog-service:8081 | Catalog and tool CRUD |
| `/api/agent/**`, `/api/agents/**`, `/api/agent-folders/**`, `/api/skills/**`, `/api/tasks/**` | agent-service:8090 | Agent runtime/configuration |
| `/api/conversations/**`, `/api/dm/**`, `/api/v3/chat/**`, `/ws/**` | conversation-service:8087 | Chat plus authenticated WebSocket |
| `/api/crud/**`, `/api/data-sources/**`, `/api/table-folders/**` | datasource-service:8088 | Datasource APIs |
| `/api/interfaces/**`, `/api/interface-folders/**` | interface-service:8089 | Interface CRUD; runtime render/action exceptions route to orchestrator |
| `/api/chat-endpoints/**`, `/api/form-endpoints/**`, `/api/webhooks/**` | trigger-service:8091 | Authenticated trigger management; this is not the edge webhook namespace |
| `/api/application-folders/**`, `/api/ce-marketplace/**`, `/api/cloud-link/**`, `/api/public/**`, `/api/publications/**` | publication-service:8092 | Publication and Cloud-link runtime |
| `/api/v2/workflows/**`, `/api/workflows/**`, `/api/workflow-folders/**`, `/api/projects/**`, `/api/browser-agent/**` and explicit orchestrator families | orchestrator-service:8099 | Workflow facade, including `/api/v2/workflows/dag` |
| `/api/files/**`, `/api/storage/**`, `/storage/v1/**` | storage-service:8082 | Storage; explorer/quota facade exceptions route to orchestrator |
| `/cdp/**` | websearch-service:8085 | CDP upgrade; protected by its session token |
| `/webhooks/stripe` | auth-service:8083 | Only explicitly public webhook; Stripe signature is verified downstream |
| `/widget.js`, `/widget/**` | agent-service:8090 | Historical widget loader/runtime; self-authenticated capability |
| `/share/**` | publication-service:8092 | Historical public share capability |
| `/c/**` | conversation-service:8087 | Historical shared conversation capability |
| `/webhook/agent/**` | agent-service:8090 | Agent webhook token; ordered before generic webhook |
| `/webhook/**` | orchestrator-service:8099 | Workflow webhook token |
| `/approval-callback/**`, `/chat/**`, `/form/**`, `/app/public/**` | orchestrator-service:8099 | Historical self-authenticated public entrypoints |

The explicit route contract is enforced by `GatewayRouteInventoryTest`, including
the five LiveContext v0.2.13 resource-folder families and their owning services.
A separate edge-compatibility inventory covers the root paths historically implemented
by `ServicePrefixRewriteFilter`; controller enumeration alone is not sufficient to
preserve those public URLs. Known path collisions are resolved before broad
families: conversation/admin, orchestrator/admin, publication CE TLS, orchestrator
interface runtime, orchestrator storage facade, and all `/api/v2/workflows/**`
routes. The orchestrator owns the public aggregate `/api/agent-tools/**` facade.
Likewise, public workflow schedule calls stay on the orchestrator facade: it delegates
CRUD to trigger-service and retains execute-now. The duplicate service-local
`/api/agent-tools/**` and trigger schedule controllers are interservice surfaces,
not additional public edge targets.

Service-selector paths such as `/api/auth-service/api/**` are intentionally not exposed
on the public edge. Interservice calls use Docker DNS directly; this prevents a rewritten
selector from bypassing route-specific entitlement policy. The gateway and security layer
always deny `/api/internal/**` and `/internal/**`; private workload-JWT listeners are
separate network surfaces and are never routed here.
