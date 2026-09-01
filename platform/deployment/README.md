# Trinyx deployment state contract

A deployment binds an immutable release to one environment. It must not redefine the release or rebuild any image.

Conceptually a deployment records at least:

- `deploymentId`
- `environment`
- `releaseId`
- `environmentConfigRevision`
- deployment state/timestamps and previous deployment references

`environmentConfigRevision` identifies the declarative environment inventory used for that deployment. It is intentionally outside `releaseId` because staging and production have different URLs, private addresses, SSM namespaces, IAM/KMS/PKI resources and secret material.

Promotion means: take the staging-approved `releaseId`, prove its source/platform provenance is integrated into the authorized production branch, then deploy the **same release manifest and exact same image digests** using the production environment configuration.

Environment configuration and secret namespaces must remain isolated. Production bootstrap must never import staging secrets, private keys, passwords, KMS identifiers, SSM namespaces or PKI material.
