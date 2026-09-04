# Internal API security inventory

This inventory records the Phase 1 trust boundary for the paid Cloud deployment.
A route classified as secret-returning, destructive, financial, or
authority-changing must use a distinct service HMAC key and an explicit
method/path permission. Generic gateway HMAC remains only for legacy,
identity-sensitive reads that are outside this Phase 1 boundary.

| Route family | Classification | Authorized identity |
| --- | --- | --- |
| `/api/internal/credentials/**` | secret-returning and destructive | catalog, orchestrator, agent; explicit routes only |
| `/api/internal/variables/**` | secret-returning | catalog, orchestrator, agent; bundle read only |
| `/api/internal/cloud-credit-proxy/**` | financial | catalog, orchestrator, agent; explicit lifecycle routes only |
| `/api/internal/cloud-identity/**` | authority-changing | gateway-service only |
| `/api/internal/v1/entitlement-projections/**` | authority-changing | gateway-service only |
| `/api/internal/auth/workspace/**` | destructive | auth-service only |
| `/api/internal/storage/**` | secret-bearing object access and destructive | catalog, orchestrator, publication, auth; explicit routes only |
| `/internal/v1/credit-reservations/**` | paid-monolith financial authority | workload Ed25519 authentication, not container HMAC |
| `/internal/v1/entitlement-projections/**` | paid-monolith authority feed | workload Ed25519 authentication, not container HMAC |
| `/internal/v1/identity-bindings/revocations/**` | paid-monolith authority feed | workload Ed25519 authentication, not container HMAC |

## Tenant trust model

Service HMAC binds the exact method, target, body hash, tenant and organization
headers and prevents post-signature tampering or cross-service impersonation.
It does not claim that a fully compromised, already-authorized caller cannot
choose a different tenant before signing. Financial reserve requests therefore
also validate the actor membership, organization owner, billing subject,
installation and entitlement at the paid authority. A future independent
delegation capability would be an additional containment layer, not a property
of the Phase 1 service-HMAC protocol.
