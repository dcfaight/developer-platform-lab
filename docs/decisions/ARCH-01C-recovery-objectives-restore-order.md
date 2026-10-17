# ARCH-01C: Recovery Objectives and Restore Order

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; reviewers: platform operations, service owners, and security. Confirm named owners during review.
**Roadmap reference:** [ARCH-01C — Recovery objectives and restore order](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01A — Hosting, control-plane ownership, and isolation](ARCH-01A-hosting-control-plane-ownership.md).

## Context

The accepted [Phase B target architecture](phase-b-target-architecture.md)
intends `prod1` and `prod2` as independent production-continuity sites, but
does not define recovery objectives, data replication, failover, or production
readiness. Current disposable-target checks have narrow scopes: fixture-based
logical database restore, graceful same-PVC pod restart, GitOps frontend
rollback, and a bounded lifecycle baseline. None establishes recovery
objectives for node, cluster, or site loss.

This decision must define which platform and workload state must be recovered,
the loss scenarios that matter, acceptable recovery point objectives (RPO) and
recovery time objectives (RTO), who declares recovery, and the dependency-aware
order for restoration. RPO/RTO values must be reviewed and assigned to owners;
they must not be inferred from disposable drill timings.

## Options considered

- **Uniform objectives:** apply one RPO/RTO pair to all in-scope platform and
  workload state. This simplifies communication but may overprotect low-impact
  state or understate critical service needs.
- **Tiered objectives:** assign approved RPO/RTO values by service, data class,
  and failure scope. This reflects differing consequences but requires service
  ownership, classification, and more evidence.
- **Defer objective values:** first inventory state, dependencies, and business
  impact, then assign targets before choosing recovery mechanisms. This avoids
  unsupported numbers but leaves recovery design blocked in the interim.

For restore order, candidate approaches are a fixed serial sequence, a
dependency-based sequence with safe parallel restoration, or service-specific
runbooks without a shared dependency model. The proposal favors evaluating a
shared dependency model, while leaving its adoption and detailed ordering open
for review.

## Decision

**No RPO/RTO values, recovery strategy, or final restore order are selected by
this proposal.** The proposed review outcome is a reviewed recovery matrix and
dependency-aware restore sequence, including an explicit decision authority and
measurable evidence plan for each in-scope service and failure scope.

As a starting point for that review—not an accepted runbook—consider this
dependency order: establish the hosting/network/cluster foundation and recovery
access; restore identity and privileged access; restore required secrets,
certificates, and keys; restore persistent storage and databases; restore
management services; then restore and verify workloads. The final order must
record dependencies, safe parallel work, and any break-glass path that avoids
circular dependence on a service being recovered.

## Rationale

Recovery mechanisms cannot be selected responsibly without knowing acceptable
data loss, recovery time, service priority, and failure scope. A dependency
model is more actionable than assuming one fixed sequence fits all components,
and makes hidden prerequisites—such as credentials needed to restore a
database—reviewable. Explicitly separating proposed objectives from measured
results prevents the current bounded drills from being misrepresented as
production recovery evidence.

## Dependencies

- Preserve the accepted Phase B continuity-pair intent without inferring site
  independence or recovery capability.
- Review jointly with ARCH-01A: hosting, ownership, isolation, and failure
  domains constrain achievable objectives and recovery access.
- Use accepted recovery objectives and restore dependencies to frame ARCH-01B,
  ARCH-01D, and ARCH-01E through ARCH-01G.
- RESTORE-01, PERSIST-01, GITOPS-01, and CAPACITY-01 remain separate gates.
  Their evidence may inform later planning within its stated scope but neither
  supplies recovery objectives nor closes this decision.

## Consequences

- Until objectives and ordering are reviewed, backup, replication, failover,
  and recovery implementation choices that depend on them remain blocked.
- An accepted decision will identify what later drills must measure and retain;
  acceptance itself will not prove that any objective is achieved.
- If objectives differ by service or failure scope, owners must maintain
  corresponding recovery procedures and evidence rather than claiming one
  platform-wide result.

## Acceptance criteria and evidence

Before this decision is accepted, its reviewed disposition must include:

1. An inventory of in-scope platform services and state, with an accountable
   owner and the impact of loss or unavailability.
2. A matrix covering at least pod, node, cluster, and site loss, assigning
   approved RPO and RTO values (with units) or explicitly recording an owned,
   time-bounded deferral for each applicable service/state and failure scope.
3. A recovery authority and an ordered dependency map identifying prerequisites,
   safe parallel steps, required identity/secrets/storage/database/management/
   workload recovery, and a break-glass path where needed.
4. For each objective, defined start/end events, measurement source, pass/fail
   rule, required data-integrity checks, and sanitized durable evidence to retain.
5. A recorded deferral owner, revisit condition, and blocked follow-up work for
   any unassigned objective or unresolved recovery dependency.

These criteria assess whether objectives and a testable recovery plan are
recorded. They do not constitute a passing recovery drill, commit numeric
targets before review, or claim production readiness.

## Explicit deferrals / out of scope

- Selecting numerical RPO/RTO values before review by accountable service and
  platform owners; values are not inferred from the logical restore duration or
  other bounded gate results.
- Choosing backup schedules, retention, off-site copies, PITR, replication,
  HA, automated failover/failback, or a `prod1` / `prod2` mechanism (ARCH-01D
  and ARCH-01G).
- Implementing, executing, or authorizing destructive recovery, site-loss, or
  production drills.
- Detailed hosting/control-plane ownership and management/workload isolation
  (ARCH-01A), environment boundaries (ARCH-01B), and identity/account lifecycle
  design (ARCH-01E).
- Modifying existing validation-gate definitions, evidence history, or status.

## Follow-up work

- [ ] Platform team and service owners: inventory critical state and propose owned RPO/RTO values by service and failure scope; document deferrals and revisit conditions.
- [ ] Platform operations and security reviewers: review the dependency-based restore sequence, recovery authority, and break-glass requirements alongside ARCH-01A.
- [ ] After acceptance, create separately scoped recovery implementation and drills with target-specific safety boundaries and durable evidence; do not treat this decision as validation.
