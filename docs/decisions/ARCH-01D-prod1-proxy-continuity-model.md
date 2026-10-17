# ARCH-01D: prod1/prod2 Continuity Model

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; reviewers: platform operations, service owners, and security. Confirm named owners during review.
**Roadmap reference:** [ARCH-01D — prod1/prod2 implementation](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01B — Environment boundaries](ARCH-01B-management-placement-environment-boundaries.md) and [ARCH-01C — Recovery objectives and restore order](ARCH-01C-recovery-objectives-restore-order.md).

## Context

The accepted [Phase B target architecture](phase-b-target-architecture.md)
intends `prod1` and `prod2` as independent production-continuity sites, not as
successive promotion environments. It does not select active/active or
active/passive operation, replication, traffic management, or recovery targets.
ARCH-01A/B/C remain Proposed; their existence does not establish hosting,
site independence, management placement, or achievable RPO/RTO.

Continuity must cover planned switchover, unplanned site loss, partial network
failure, and controlled return to the recovered site. Routing users elsewhere
is insufficient if data, identity, credentials, or management dependencies are
unavailable or both sites can still accept conflicting writes.

## Options considered

- **Active/active:** serve traffic at both sites. This can improve utilization
  and reduce some switching delays, but requires explicit data ownership,
  conflict handling, and partition-safe coordination.
- **Operator-controlled active/passive:** one site serves a given service and
  owns its writes; the other is a recovery target. This reduces concurrent-writer
  complexity but requires standby capacity, data recovery, and a deliberate
  interruption during promotion.
- **Automated active/passive failover:** automate failure detection and
  promotion. This may shorten recovery but adds false-positive and split-brain
  risks unless independent fencing and data-readiness checks are proven.
- **Backup/rebuild only; defer site switching:** retain a recovery target without
  a ready standby. This reduces standing cost but may not satisfy reviewed
  recovery objectives and cannot be presented as immediate continuity.

## Decision

**This proposal recommends operator-controlled active/passive continuity, with
one serving site and one authoritative writer per service/data set.** Either
site may hold the active role; the names do not imply a mandatory primary.
Standby readiness and capacity must be reviewed against ARCH-01C rather than
assumed. This is a proposed operational model, not a selected replication,
proxy, DNS, load-balancer, or database-HA technology.

For planned switchover, the proposed order is to approve the change, quiesce
affected mutations and background writers, verify the agreed recovery point,
fence the old writer, promote the target, verify service/data readiness, then
switch traffic and observe the outcome. The incident authority must approve
unplanned failover using evidence of fencing, target integrity, and expected
data loss against the reviewed objectives. Unreachable does not mean fenced:
if exclusive write authority cannot be established, promotion must stop.

Traffic switching is distinct from data promotion and does not revoke database
or worker write authority. Fencing must cover application writers, asynchronous
workers, and other mutation paths; a router/proxy change alone is insufficient.
Site-scoped credentials and control permissions should limit a compromised or
failed site from changing its peer. Shared routing, identity, management, and
storage dependencies must be listed with their failure impact and recovery path.

Failback is a separately approved planned switchover, not automatic return when
the old site responds. The returning site must remain fenced until divergent
state is reconciled, it is resynchronized from the authoritative state, and
readiness is verified. Before target writes begin, an aborted switch may restore
the old site only after confirming exclusive authority. After target writes
begin, routing back alone is not a safe rollback.

Operational continuity means restoring the agreed service scope with one
authoritative write path, verified data integrity and dependencies, and measured
interruption/data loss against ARCH-01C. It does not mean zero downtime, zero
data loss, automatic failover, or a substitute for backup/restore.

## Rationale

A deliberate single-writer transition makes authority, safety checks, and human
approval reviewable before introducing automation. It avoids selecting
active/active conflict semantics without reviewed data requirements. The
trade-off is recovery delay and operator burden; if these conflict with reviewed
objectives, reviewers must choose another option or record an owned deferral,
not silently assume faster or lossless recovery.

## Dependencies

- Review [ARCH-01A](ARCH-01A-hosting-control-plane-ownership.md), ARCH-01B, and
  ARCH-01C dispositions before acceptance: ownership, failure domains, trust
  boundaries, and recovery objectives remain unresolved.
- ARCH-01E must cover recovery authority and access during identity outages.
  Management recovery must not depend solely on the failed production site.
- ARCH-01F/G must select network/credential enforcement and data protection,
  replication, and database recovery mechanisms capable of enforcing this model.
  Operational implementation remains blocked until those prerequisites are met.
- GITOPS-01, PERSIST-01, CAPACITY-01, and RESTORE-01 remain separate bounded
  gates; none proves site switching, fencing, or production continuity.

## Consequences

- If accepted, each protected service needs an owner, active-role inventory,
  standby-readiness definition, transition authority, and a safe stop condition.
- Operator approval and verified fencing can lengthen outages; availability
  must not be prioritized by allowing unverified concurrent writers.
- Independent sites still have shared-dependency risks and standby costs.
  Acceptance does not prove independence, successful recovery, or readiness.
- Later automation must preserve the same promotion and fencing boundaries and
  requires separately reviewed implementation and evidence.

## Acceptance criteria and evidence

Before this decision is accepted, its reviewed disposition must include:

1. A matrix covering every protected service/data set, both sites, active/write
   authority, standby capacity/readiness, dependency owner, and failure impact.
   No shared dependency or mutation path may be left implicit.
2. A transition table for planned switchover, site loss, network partition,
   aborted promotion, and failback, naming the approver, fencing evidence,
   data-readiness checks, traffic-switch conditions, and stop/rollback boundary
   for every transition.
3. Approved RPO/RTO references from ARCH-01C for each protected scope, or an
   owned, time-bounded deferral with a revisit condition and blocked work.
4. A separately scoped validation plan covering both switch directions, old-site
   return, unavailable identity/management, and a partition with uncertain
   fencing. It must require promotion denial when fencing is unproven and zero
   concurrent authoritative writers during any successful transition.
5. For that future validation, defined outage start/end events, recovered-data
   comparison, routing-convergence measurement, objective-based pass/fail rules,
   and sanitized durable records of approvals, fencing, role changes, and results.

These are decision-completeness and future evidence requirements, not recorded
drill results or authorization for production changes.

## Explicit deferrals / out of scope

- Final hosting and environment placement (ARCH-01A/B), numeric recovery targets
  (ARCH-01C), and detailed identity/account controls (ARCH-01E).
- Network policies, worker/peer credential enforcement, secret rotation,
  certificate renewal, and database role separation (ARCH-01F).
- Replication technology, backup scope/schedules, retention, off-site protection,
  PITR, database HA, and automated data failover/failback (ARCH-01G). Replicas
  must not be treated as backups.
- Selecting routing/proxy technology, provisioning sites, executing destructive
  drills, changing validation-gate history, or authorizing production deployment.

The platform team owns the F/G handoff; revisit these mechanisms after reviewed
A–E dispositions and before continuity implementation. Until then, dependent
replication, fencing, routing, and failover rollout remains blocked.

## Follow-up work

- [ ] Platform team and service owners: reconcile the proposed model with A–C, inventory protected scopes, and record acceptance or an owned, time-bounded deferral.
- [ ] Platform operations and security: review transition authority, fencing/blast-radius assumptions, and identity/management outage paths with ARCH-01E.
- [ ] Platform team: carry mechanism requirements into ARCH-01F/G; after prerequisite acceptance, propose separate implementation and disposable-target drills with safety boundaries and retained evidence.
