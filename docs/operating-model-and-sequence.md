# Operating Model and Delivery Sequence

**Canonical current status and roadmap.** Last reviewed October 17, 2026,
against `main` at `4d859482810a37ec53571d81e1b87a29ace256f2` (after PR #27
merged). Update this document when implementation or validation evidence
changes; README and runbooks provide entry points and execution details, not
competing status matrices.

## Operating model

This independent IDP is intended to be built and operated by a platform team
and consumed by developers through approved self-service workflows. The
approximately 3,000 developers are the target population, not measured capacity,
environment count, or concurrency. Dev, QA, and UAT are the lifecycle focus.
The accepted Phase B decision keeps `prod1` and `prod2` as independent
production-continuity sites/clusters; it does not define deployed production
clusters or settle control-plane ownership, management placement, active/active
versus active/passive operation, isolation, recovery objectives, or production
readiness.

## Status vocabulary

- **Implemented:** code or configuration exists. This alone is not validation.
- **Validation recorded:** a dated check passed for the stated target and scope.
- **Evidence pending:** the acceptance run or durable evidence is still needed.
- **Blocked:** an identified dependency currently prevents the acceptance run.
- **Planned/deferred:** not part of the implemented or validated scope.

A merge is not a live validation result, and a successful bounded check is not
production readiness. "Task-local" identifies evidence produced in an
ephemeral task environment; it does not mean the artifact is durably retained.

## Current delivery gates

This is the durable roadmap record after PR #27, not a claim of new validation.
The next priorities are **GITOPS-01 → PERSIST-01 → CAPACITY-01 → ARCH-01**;
the other open acceptance gates remain recorded here. “Evidence pending” means
validation or durable retention is still required; no recorded blocker does not
mean a target has been verified healthy.

| Gate | Status / open blockers | Capability and implementation | What it proves / validation recorded | Remaining acceptance | References |
|---|---|---|---|---|---|
| **AUTH-01 — Basic/team boundary** | Implemented; validation recorded for bounded scope. | Basic-authenticated `OPERATOR`, team-scoped `DEVELOPER`, and exclusive expiry-guarded `CLEANUP` roles are implemented. Trusted SQL administrators provision teams and memberships; legacy unowned environments remain operator-managed. | Live smoke checks recorded anonymous-read `401`, cross-team create `403` without writes/reservations, owned-team create/replay `202`, and membership-revocation denial. | This bounded gate is implemented and its smoke evidence is recorded. SSO/OIDC, account lifecycle, rate limiting, and stronger tenant isolation are deferred; this is not production identity assurance. | [API and auth contract](api.md); [backend auth setup](../backend/README.md#authentication-and-authorization) |
| **BUDGET-01 — Per-environment guardrails** | Implemented; validation recorded for bounded scope. | ResourceQuota and LimitRange budgets are enforced for provisioned environments. The fixed sample workload is a provisioning fixture, not a representative developer workload. | A targeted disposable kind check recorded LimitRange defaults and rejection of an over-budget Pod. | Budgets are guardrails, not capacity estimates; broader isolation and capacity evidence remain separate gates. | [Budget details and evidence](../infra/README.md#provisioned-environment-resource-budgets) |
| **K3S-01 — K3s platform lifecycle** | Implemented; automated evidence pending. No blocker recorded. | Platform Kustomize package, in-cluster worker, projected credentials, and dedicated RBAC are implemented for a disposable single-node K3s cluster. | Manual create → `READY` → authenticated delete → namespace absence passed October 16, 2026. This was not an automated test run. | Record a successful automated target-specific lifecycle run with retained evidence. Do not infer PVC restart persistence or production readiness from lifecycle success. | [K3s runbook and manual evidence](../infra/README.md#local-k3s-platform); [K3s package](../infra/kubernetes/) |
| **GITOPS-01 — GitOps live validation** | Implemented; evidence pending; blocked by `cdn01.quay.io` DNS/image-download failure. | Bounded Argo CD tooling for frontend-only reconciliation and rollback is implemented; target ownership excludes the namespace, PVC, worker RBAC, and external secrets. | Proves target-cluster Git synchronization and frontend upgrade/revert without disturbing backend or persisted state. October 17, 2026: bootstrap resources applied, but Argo controller startup was blocked by the image-download failure. No successful Git sync, frontend upgrade/revert, or persistence proof is recorded. | Resolve artifact/image availability without weakening TLS or permissions. Retain successful evidence for three distinct Git commits (baseline, frontend upgrade, revert), exact desired/runtime releases, and unchanged backend/database/PVC/secret and environment/operation state. | [GitOps runbook and validation status](../infra/README.md#disposable-k3s-gitops-deployment-and-frontend-rollback) |
| **RESTORE-01 — Logical database round trip** | Implemented; durable evidence pending. No blocker recorded. | Opt-in disposable K3s fixture-based logical backup/restore tooling is implemented; it uses isolated temporary databases, not the live application database or PVC. | October 17, 2026: task-local disposable K3s round trip passed fixture, Flyway, schema, and sequence checks; backup took 0.207 s and restore 0.216 s. The artifacts were only in that task environment. | Retain the dump, checksum, and evidence durably for the intended disposable target, or repeat the drill and archive them. This is not live PVC backup, restart persistence, node-loss recovery, PITR, HA, or production recovery. | [Logical backup/restore runbook and limits](../infra/README.md#disposable-k3s-logical-backuprestore) |
| **CAPACITY-01 — Lifecycle baseline** | Implemented; evidence pending. No blocker recorded. | PR #26 implemented `capacity_baseline.py`, an opt-in harness for 10 fixed `sample-service` lifecycle workflows at concurrency 2, including cleanup, plus offline tests and optional resource samples. | Proves bounded lifecycle timing, failure, and cleanup behavior on the selected target. Harness/offline tests are implementation evidence; no successful live capacity-baseline run is recorded. Per-environment quotas are guardrails, not capacity estimates. | Retain one successful full run with selected target/configuration, timings, failures, cleanup outcomes, and explicit resource-sampling availability. It is a small baseline, not support for 3,000 developers, saturation, or autoscaling evidence. | [Capacity harness and interpretation](../automation/README.md#opt-in-lifecycle-capacity-baseline); [resource budgets](../infra/README.md#provisioned-environment-resource-budgets) |
| **PERSIST-01 — Restart persistence on K3s** | Evidence pending; controlled check still needs definition. No live-run blocker recorded. | PostgreSQL uses a persistent K3s PVC; the documented restore fixture is separate and does not exercise it. | Proves environment/operation IDs, history, and idempotent replay survive restart without manual data repair. Persistence across a controlled K3s pod restart has not been demonstrated in the recorded evidence. | Define and run a controlled disposable-target check that retains known environment/operation/idempotency/history records across a PostgreSQL restart; retain before/after observations, replay, readiness, and subsequent safe deletion/namespace-absence evidence. Do not delete the PVC or platform namespace as a test. | [K3s persistence and teardown cautions](../infra/README.md#persistence-startup-and-remaining-work) |
| **CLEANUP-01 — Expiration cleanup** | Implemented; acceptance reconciliation pending. Historical limits remain in the linked evidence. | A manual API-driven cleanup CLI is implemented; it defaults to dry-run and never deletes namespaces directly. Scheduling and remote-TLS transport are deferred. | The cleanup README preserves the original change's hosted-CI, review/security, kind, and browser verification limits. Those dated results are historical and have not been reconciled here as new failures. | Reconcile the recorded gates against current hosted CI/review and produce a successful disposable kind/browser cleanup walkthrough before treating that acceptance as closed. Do not weaken safeguards to make a drill pass. | [Cleanup usage and original evidence](../automation/README.md#evidence-and-remaining-gates-for-the-original-cleanup-change); [demo](demo.md#5-manual-expiration-cleanup) |
| **ARCH-01 — Architecture and recovery decisions** | Planned/deferred; ownership, isolation, and recovery objectives remain unresolved. | Phase B records the intended topology, not deployed production sites. Hosting/control-plane ownership, management placement/isolation, recovery objectives, `prod1`/`prod2` implementation, identity/account-management hardening, and deeper security/recovery work remain open. | Establishes agreed design boundaries and measurable acceptance criteria, not production readiness. No broader recovery/security validation is claimed. | Record decisions/ADRs with acceptance criteria and implementation dependencies; explicitly resolve or defer SSO/OIDC, network policy, secret rotation, database role separation, retention, PITR, HA, and failover. | [Phase B decision](decisions/phase-b-target-architecture.md); [open decisions and deferred scope](#open-decisions-and-deferred-scope) |

## Proposed next sequence

This is a proposal for bounded disposable-target work, not authorization for
production changes:

1. Select a healthy disposable target, verify image/artifact availability, and
   choose an operator-managed durable evidence location. Do not weaken TLS,
   access controls, or ownership checks.
2. Resolve the recorded GitOps image-download blocker and run the Git-driven
   frontend upgrade/revert drill with retained evidence.
3. Define and validate controlled same-PVC restart persistence with retained
   before/after evidence.
4. Retain the initial 10-workflow/concurrency-2 capacity baseline, including cleanup.
5. Decide broader architecture, identity, security, and recovery objectives
   before production-facing expansion.

This is a priority order, not a hard dependency on GitOps. Automated K3s
lifecycle and retained logical-restore evidence can be completed independently,
as can persistence and capacity checks on a healthy target; inspect and resolve
leftovers first. Reconcile the historical cleanup/demo evidence against current
CI and review results separately. Documentation consolidation is complete;
maintaining this roadmap remains ongoing.

## Open decisions and deferred scope

- **Phase B:** target population and `prod1`/`prod2` continuity topology are
  recorded; hosting/control-plane ownership, management placement/isolation,
  service objectives, and production site design remain unresolved.
- **Phase C:** local choices exist (including Argo CD, K3s, and PostgreSQL), but
  overall tool selection against the unresolved requirements is incomplete.
  Accepted decisions and local tool choices do not imply that the topology is
  deployed in runtime.
- **Deferred:** SSO/OIDC and account lifecycle; rate limits; network isolation
  and worker-credential blast-radius reduction; database role separation,
  secret encryption/rotation, and certificate renewal; scheduled backups,
  retention, PITR and node-loss objectives; representative workload profiles
  and scaling; dev/QA/UAT promotion and independent `prod1`/`prod2` switchover.
  Optional service catalog, templates, and AI ideas are candidates, not
  acceptance gates.

## Maintainer status-update checklist

When a relevant PR merges or a live run completes:

1. Read this roadmap and its linked evidence/runbooks before asking for status
   already available in the repository.
2. Update implementation and validation separately; a merge does not close an
   evidence gate.
3. Record target, date, revision, command/check, outcome, sanitized durable
   artifact reference, blocker, and next action. Say explicitly when a check is
   unknown, skipped, task-local, or not run.
4. Preserve dated historical results; clarify their scope and link back here
   rather than silently rewriting old outcomes.
5. Keep this table as the status record; README is the entry point, infra and
   automation are runbooks/evidence, and the pre-Kubernetes baseline is archival.
   Update links rather than copying a second roadmap into those documents.
