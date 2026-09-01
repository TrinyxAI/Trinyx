# AWS platform bootstrap

Trinyx uses GitHub Actions OIDC rather than long-lived AWS access keys.

## Staging OIDC bootstrap

`bootstrap/github-oidc-staging-bootstrap.json` creates or reuses the IAM OIDC provider for `https://token.actions.githubusercontent.com` and creates `TrinyxStagingGitHubOidcBootstrapRole`.

The bootstrap role deliberately has **no AWS permission policy**. Its only purpose is to prove that AWS accepts the exact immutable GitHub repository identity before any deployment capability is granted.

The trusted subject is pinned to the immutable owner/repository IDs and the platform automation branch:

`repo:TrinyxAI@319253481/Trinyx@1342032975:ref:refs/heads/codex/platform-release-automation`

The audience is exactly `sts.amazonaws.com`.

AWS CloudFormation is used here because this is the trust bootstrap that must exist before GitHub can authenticate to AWS. It can be applied from AWS CloudShell while signed into the staging AWS account; no AWS access key needs to be stored in GitHub or copied to a developer workstation.

If the AWS account already has the GitHub Actions OIDC provider, pass its ARN through `ExistingGitHubOidcProviderArn` instead of creating a duplicate provider.

Deployment permissions are intentionally added later, after OIDC identity is proven and after a dedicated SSM deployment document exists. GitHub must not receive Parameter Store secret decryption access; runtime secrets remain fetched by the EC2 instance roles.
