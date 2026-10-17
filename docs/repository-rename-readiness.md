# Repository Rename Readiness

This checklist prepares for the owner-requested rename from
`dcfaight/developer-platform-lab` to `dcfaight/internal-developer-platform`.
It does not perform or claim the rename. Repository ID `1421665582` identifies
the existing repository and must remain unchanged.

## Before the rename

- Coordinate merging this readiness change with the owner's rename. References
  to `internal-developer-platform` below will not resolve until the owner renames
  the repository.
- The owner must rename the existing repository in GitHub: **Settings > General >
  Repository name**, enter `internal-developer-platform`, then select **Rename**.
- A read-only GitHub search for `dcfaight/internal-developer-platform` returned
  no matching repository, and a direct lookup returned 404. This did not
  establish reservation/availability conclusively; confirm in GitHub while
  performing the rename. Do not create a replacement repository.
- The repository's README already uses the product name “Internal Developer
  Platform”; its repository-structure example now uses the desired repository
  name. No README clone command or workflow badge was found to update.

## Reference audit and coordinated follow-up

| Reference | Readiness treatment |
|---|---|
| Historical PR links in the roadmap, infrastructure evidence, and FreeLens recovery notes | Left unchanged as historical evidence. GitHub normally redirects renamed-repository URLs. |
| kind cluster/context names, application name, Kubernetes resources, package names, and built image names | Left unchanged; these are runtime/resource or artifact identifiers, not repository URLs. |
| `infra/gitops/bootstrap/application.yaml` Argo CD `sourceRepos` and `repoURL` | Still target the old URL. Coordinate updating the live GitOps consumer and this committed configuration to the new URL after rename, then validate Argo CD access/sync. Its test expectations in `automation/tests/test_gitops.py` must be updated in the same change. This runtime-impacting change is deliberately not made here. |
| `frontend/Dockerfile` `org.opencontainers.image.source` label | Updated to the intended repository URL; it is source metadata, not a runtime setting or image coordinate. Existing images retain their old label, so verify any published images separately and rebuild after the rename. |
| GitHub-hosted actions and reusable workflows | No repository-hosted action metadata or `workflow_call` workflow was found. GitHub does not redirect calls to actions hosted by a renamed repository. External workflows that may consume this repository are not discoverable from this checkout; the owner must search accessible repositories for `dcfaight/developer-platform-lab` action references and coordinate updates. |
| Packages and container publication | No repository-name-dependent package/image coordinates or publishing workflow were found. The OCI source label above is the only repository URL found in image metadata. |
| GitHub Pages | Repository metadata reported `has_pages=false`. Recheck after rename if Pages has since been enabled; Pages site URLs are an exception to GitHub's general repository-URL redirects. |

Other external consumers—including external Argo CD installations, workflow
callers, and registry metadata already published elsewhere—cannot be inspected
from this repository. Do not assume they do not exist; the owner should check
each system they operate.

## After the owner renames the repository

1. Confirm the canonical repository name is
   `dcfaight/internal-developer-platform` and its repository ID is still
   `1421665582`.
2. Check that the old repository URL redirects to the new URL. Also follow
   representative links to the [README](https://github.com/dcfaight/internal-developer-platform/blob/main/README.md),
   [ARCH-01 roadmap](https://github.com/dcfaight/internal-developer-platform/blob/main/docs/operating-model-and-sequence.md),
   and [merged PR #40](https://github.com/dcfaight/internal-developer-platform/pull/40).
   These destination URLs are expected not to resolve before the rename.
3. From a machine with repository access, verify the new HTTPS clone/fetch
   endpoint with
   `git ls-remote https://github.com/dcfaight/internal-developer-platform.git HEAD`.
   Verify the old URL redirects as documented, but update local remotes to avoid
   relying on redirects.
4. In the owner's own clone, inspect and update the existing `origin`, preserving
   its current HTTPS or SSH protocol, then verify the remote and fetch:

   ```sh
   git remote -v
   git remote get-url origin
   git remote set-url origin https://github.com/dcfaight/internal-developer-platform.git
   # For an existing SSH remote instead:
   # git remote set-url origin git@github.com:dcfaight/internal-developer-platform.git
   git remote -v
   git ls-remote origin HEAD
   git fetch --dry-run origin
   ```

   An agent cannot change or verify the owner's personal local clone; these
   commands have not been run on the owner's machine.
5. Verify any workflow badge URLs, external hosted-action/reusable-workflow
   callers, frontend OCI source metadata on the next built image, and the
   coordinated Argo CD repository URL/configuration and sync. Search accessible
   repositories and deployed systems; consumers outside this checkout remain
   unverified.
6. If Pages is enabled by then, check its configured custom domain and site URL
   separately because Pages URLs are not covered by the general redirect
   behavior.
7. Do not recreate a repository using the old `developer-platform-lab` name;
   doing so can break GitHub's redirects to this repository.

## Verification status

This checklist and the safe README example update are pre-rename preparation
only. The repository has not been renamed, and post-rename redirect, URL,
workflow, GitOps, and owner-local-remote checks remain pending.
