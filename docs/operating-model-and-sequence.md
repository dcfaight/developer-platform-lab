# Operating Model and Delivery Sequence

## Operating model

This independent IDP is built and operated by a platform team and consumed by
developers through approved self-service workflows. The platform team owns
platform services, provisioning automation, and guardrails; developers request
and manage supported development and staging environments within those
guardrails. This describes intended responsibilities, not eBay's actual team
boundaries or Kubernetes hosting arrangement.

The approximately 3,000 figure is the target developer population, not measured
capacity, environment count, or concurrency. Dev, QA, and UAT are the lifecycle
focus. The accepted Phase B decision keeps `prod1` and `prod2` as independent
production continuity sites/clusters. It does not settle control-plane
ownership, active/active versus active/passive operation, recovery objectives,
or production readiness.

## Phase status and next sequence

- **Phase B — target architecture:** The audience and production continuity
  topology are recorded. Hosting/control-plane ownership, management placement,
  detailed isolation, and recovery objectives remain open; Phase B is not fully
  resolved.
- **Phase C — exact tool choices:** Select and justify tools against those
  requirements. The shortlist is not an approved stack, and Phase C is not
  complete.
- **Phase D — implement and validate:** Build a deployable baseline and record
  evidence and limitations. This bounded increment adds Basic-authenticated,
  team-scoped self-service authorization, preserving existing resource budgets,
  lifecycle and namespace-ownership safety. Trusted SQL administrators provision
  teams and memberships; legacy unowned environments stay operator-managed.
  Live smoke checks verified anonymous reads return `401`, cross-team developer
  creation returns `403` without writes or idempotency reservations, owned-team
  creation/replay returns `202`, and revocation blocks replay and list access.
  This evidence does not establish production readiness.

The next delivery sequence is:

1. Build on the implemented Basic/team authorization boundary with future
   identity-provider integration (SSO/OIDC) and account-management hardening.
   Operators have platform-wide access, developers require team ownership, and
   exclusive cleanup accounts have list/detail discovery plus expiry-guarded deletion only;
   health remains anonymous. Identity-provider integration is not part of this
   increment.
2. Establish reproducible GitOps deployment and rollback.
   An opt-in, bounded Argo CD/K3s target and frontend rollback drill are
   implemented; target-cluster synchronization and rollback evidence remain
   required. See the [GitOps runbook](../infra/README.md#disposable-k3s-gitops-deployment-and-frontend-rollback).
3. Validate backup and restore, including recovery evidence.
   An opt-in [disposable K3s logical round-trip drill](../infra/README.md#disposable-k3s-logical-backuprestore)
   is implemented; a successful run and retained evidence remain required.
   This is not PITR, HA, or production recovery validation.
4. Measure capacity with explicit workload and concurrency targets.

Nightly CI is supporting backlog automation, not a substitute for these
milestones. None of these steps establishes production readiness until its
acceptance evidence exists.
