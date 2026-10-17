# ARCH-01B: Management Placement and Environment Boundaries

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; reviewers: platform operations, service owners, and security. Confirm named owners during review.
**Roadmap reference:** [ARCH-01B — Management placement and environment boundaries](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01A — Hosting and control-plane ownership](ARCH-01A-hosting-control-plane-ownership.md) and [ARCH-01C — Recovery objectives and restore order](ARCH-01C-recovery-objectives-restore-order.md).

## Context

The accepted [Phase B target architecture](phase-b-target-architecture.md)
defines dev, QA, and UAT as lifecycle environments and `prod1` / `prod2` as
separate intended production-continuity sites. It does not select where
management services or the platform database run, how those environments are
isolated, or how promotion and retirement work. ARCH-01A and ARCH-01C are
Proposed, not accepted; their hosting, ownership, isolation, and recovery
outcomes cannot be assumed here.

This decision frames placement and trust boundaries for the portal/API,
provisioning workers, GitOps services, identity dependencies, platform
database, and developer workload environments. It must distinguish disposable
targets from longer-lived services, identify dependencies and credentials that
cross boundaries, and define lifecycle transitions without authorizing
implementation or production deployment.

## Options considered

- **Co-locate management with each workload environment:** deploy management
  services and their database alongside dev/QA/UAT and production workloads.
  This can simplify local dependencies, but couples management availability and
  recovery to workload failures and duplicates privileged services.
- **Dedicated, longer-lived management environment:** place management
  services and the platform database in a separately operated environment,
  with scoped connections to workload environments. This makes lifecycle and
  failure boundaries explicit, but requires reliable management-to-target
  access and a recovery path consistent with ARCH-01C.
- **Split management placement:** separate components according to ownership
  or dependency—for example, managed identity outside the platform and
  platform-owned services in a management environment. This can fit provider
  boundaries, but increases cross-owner dependencies and must avoid circular
  recovery dependencies.
- **Retain disposable placement and defer a target model:** continue using
  disposable infrastructure only until A/C and hosting constraints are
  reviewed. This avoids unsupported assumptions but blocks decisions that
  depend on stable environment and trust boundaries.

## Decision

**This proposal recommends a dedicated, longer-lived management boundary for
the portal/API, workers, GitOps services, and platform database, separate from
disposable developer workload targets and from production workloads.** The
platform database should follow the management service lifecycle and recovery
boundary rather than be treated as disposable workload data. Dev, QA, and UAT
should remain identifiable lifecycle environments; production should remain a
separate domain consistent with the accepted Phase B continuity-pair intent.

Promotion should move reviewed application artifacts and environment-specific
configuration forward through lifecycle environments, not promote credentials
or copy environment data by default. Cross-boundary dependencies and access
must be explicitly inventoried, owned, and limited to the documented purpose.
Production credentials must not be reused in non-production environments.

This is a proposed boundary direction for review, not a final placement or
isolation design. Review must reconcile it with accepted or explicitly deferred
outcomes in ARCH-01A and ARCH-01C. If those outcomes make the recommendation
infeasible, the disposition must record the selected alternative or a
time-bounded deferral, its owner, revisit condition, and blocked work.

## Rationale

A separately managed lifecycle avoids making the tools needed to provision,
observe, or recover environments dependent on the disposable target they
manage. Keeping the platform database with the management lifecycle makes its
ownership, persistence, and recovery dependencies reviewable instead of
implicitly coupling it to a developer environment. Explicitly separating
non-production lifecycle environments from production preserves the Phase B
boundary without inventing a `prod1` / `prod2` implementation.

The proposed promotion and credential boundaries reduce accidental coupling
between environments while leaving detailed access control, identity
provisioning, network enforcement, database roles, and secret lifecycle to
their dependent decisions.

## Dependencies

- ARCH-01A and ARCH-01C remain Proposed. Review their hosting/ownership,
  isolation, failure-domain, and recovery dispositions before accepting this
  proposal; do not treat their existence as acceptance.
- Preserve the accepted Phase B dev/QA/UAT lifecycle and `prod1` / `prod2`
  continuity intent without inferring implementation, independence, or
  recovery capability.
- ARCH-01E must define identity and account lifecycle against these reviewed
  trust boundaries. ARCH-01F/G must define detailed network, credential, data,
  and recovery-hardening mechanisms.
- Existing GITOPS-01, PERSIST-01, CAPACITY-01, and RESTORE-01 gates remain
  separate. Their definitions or evidence do not accept this decision or
  validate these boundaries.

## Consequences

- If accepted, later design work must assign each management component and
  platform data store to a named environment, owner, lifecycle, and recovery
  boundary before implementation.
- Management-to-workload dependencies remain explicit design inputs; loss of a
  target must not silently imply loss of management, and management recovery
  must not depend on credentials or services that are only available through
  the unavailable target.
- A separate management environment adds operational and recovery obligations.
  Its availability, data protection, and cross-boundary access cannot be
  claimed until separately implemented and validated.
- Acceptance records an architectural disposition only. It is not evidence
  that environments are separated, credentials are isolated, recovery works,
  or production readiness has been achieved.

## Acceptance criteria and evidence

Before this decision is accepted, its reviewed disposition must include:

1. A placement and ownership matrix listing every in-scope management
   component, the platform database, dev, QA, UAT, and production sites; none
   may have an unspecified environment, owner, or lifecycle.
2. A boundary/dependency map covering each environment pair with an explicit
   allowed-flow decision (including “none”), dependency purpose, direction,
   data or control exchanged, credential class and owner, and failure impact.
   All proposed flows must be accounted for; no implicit cross-boundary
   dependency may remain.
3. Defined disposable, longer-lived, promotion, and retirement transitions,
   including what persists, what is recreated, what artifacts/configuration
   may advance, and confirmation that production credentials are not reused
   in non-production.
4. A recorded review of management and platform-database failure and recovery
   dependencies against ARCH-01C, including any dependency that could prevent
   management from recovering when a workload environment is unavailable.
5. An explicit disposition of each option above, with accountable owner and
   reviewers, trade-offs, and either an accepted boundary or a deferral naming
   its owner, revisit condition, and blocked follow-up work.

These criteria measure decision-record completeness; they do not authorize or
claim implementation, environment isolation, a successful recovery, or
production readiness.

## Explicit deferrals / out of scope

- Final hosting/provider, control-plane ownership, or isolation mechanism
  (ARCH-01A); detailed `prod1` / `prod2` topology, replication, traffic
  switching, fencing, failover, or failback (ARCH-01D).
- Detailed SSO/OIDC, account provisioning and revocation, role mapping,
  approval, service-account lifecycle, or break-glass identity design
  (ARCH-01E).
- Network-policy implementation, secret encryption/rotation, certificate
  renewal, database role separation, retention, PITR, HA, and failover
  mechanisms (ARCH-01F/G).
- Numeric recovery objectives, recovery runbooks, or proof of management or
  database recovery (ARCH-01C and separately scoped validation).
- Implementing, migrating, or validating any environment boundary; changing
  existing validation-gate definitions or evidence history; or authorizing
  production deployment.

## Follow-up work

- [ ] Platform team: reconcile the proposed placement direction with reviewed ARCH-01A/ARCH-01C dispositions; record an accepted boundary or an owned, time-bounded deferral.
- [ ] Platform operations and service owners: inventory components, platform data, dependencies, owners, and lifecycle transitions for the acceptance matrix.
- [ ] Security reviewers: review proposed trust-boundary and credential-flow statements; defer detailed identity and hardening mechanisms to ARCH-01E through ARCH-01G.
- [ ] After acceptance, propose separately scoped implementation and validation work with explicit targets, safety boundaries, and durable evidence; do not treat this record as validation.
