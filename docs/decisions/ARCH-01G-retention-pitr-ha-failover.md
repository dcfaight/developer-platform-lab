# ARCH-01G: Retention, PITR, HA, and Failover

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; required reviewers: platform/recovery operations, database and workload-data owners, identity/key administrators, security, and retention/compliance stakeholders. Named assignments and recovery/acceptance authority remain unresolved and must be confirmed during review.
**Roadmap reference:** [ARCH-01G — Retention, PITR, HA, and failover](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01C — Recovery objectives and restore order](ARCH-01C-recovery-objectives-restore-order.md), [ARCH-01D — prod1/prod2 continuity](ARCH-01D-prod1-proxy-continuity-model.md), and [ARCH-01F — Network/credential/data-access hardening](ARCH-01F-network-credential-data-access-hardening.md).

## Context

The accepted [Phase B intent](phase-b-target-architecture.md) describes a
production-continuity pair, not deployed sites or a selected recovery mechanism.
ARCH-01A–F remain Proposed, regardless of merged documentation PRs. C has no
accepted RPO/RTO or final restore order; D's operator-controlled active/passive
model is a recommendation, not an accepted replication or failover topology.

This decision must distinguish four outcomes:

- **Backup restore:** recover a verified independent copy after deletion,
  corruption, or loss, within the copy's recoverable scope.
- **Point-in-time recovery (PITR):** recover to a chosen consistent point inside
  an approved recovery window, requiring a usable base and continuous log chain.
- **Availability/HA:** sustain or resume service through specified component
  failures; replicas may reproduce corruption or deletion and are not backups.
- **Site continuity:** recover the agreed service scope after site loss,
  including identity, keys, data, management, fencing, and traffic dependencies.
  Local HA alone does not establish this outcome.

The bounded fixture-based logical round trip in RESTORE-01 does not establish
live platform/workload protection, PITR, node-loss recovery, HA, or site
continuity. Its existing evidence and durable-retention obligation remain
unchanged; this proposal claims no new recovery result.

## Options considered

- **Periodic logical exports and rebuild:** portable and inspectable, with
  relatively low standing cost; recovery granularity is the export and rebuild
  time may not meet C. It does not supply continuous PITR or HA.
- **Consistent base backups plus continuous transaction-log archiving:** supports
  candidate PITR windows and finer recovery points, but requires log continuity,
  coordinated retention, key recovery, monitoring, and engine-specific restore
  procedures. It does not by itself provide service availability.
- **Add replication/local HA and a separately designed site standby:** can reduce
  some interruptions but adds capacity, coordination, fencing, resynchronization,
  and split-brain risks. Independent backups remain necessary.
- **Managed recovery/availability services:** can reduce operating work, but
  provider guarantees, exportability, failure domains, key custody, retention,
  and tested restoration remain owner responsibilities.
- **Defer mechanism selection:** avoids unsupported objectives and topology
  assumptions, but dependent scheduled protection, PITR, HA, and failover
  implementation remain blocked.

## Decision

**Pending review: recommend an owned data-protection/recovery matrix with
independent verified backups, evaluating PITR, HA, and site continuity separately
against C's eventual objectives and D's eventual transition model.**
No provider, tool, topology, schedule, retention period, recovery-window value,
RPO/RTO, or automatic promotion policy is selected or accepted here.

### Backup scope, schedule, and retention

Inventory platform database state (including identity/team authorization,
environment/operation history, and schema version), required workload datasets,
and any external identity/configuration state needed to recover the agreed
service. Distinguish reproducible Git/configuration/artifacts from irreplaceable
state and record their availability dependencies. For every dataset record an
owner, consistency boundary, source of truth, restore destination, inclusion or
explicit exclusion, and handling of linked state across services. An exclusion
must not silently imply protection by another team.

For each protected scope, review backup type, numeric frequency/time window,
maximum backup age, log-archive lag where applicable, retention duration,
verification cadence, and missed-run alert/escalation threshold against C.
Distinguish successful job execution from a complete, usable, verified copy.
Values and responsible operators remain unresolved until that review; no
schedule is introduced by this record.

Recommend encrypted off-site copies in a reviewed independent failure and
administrative domain, accessible when the source site/management service is
lost. Review immutable or offline protection against compromised source
credentials, with separately controlled key custody and deletion authority.
Replication or another directory at the same site is not evidence of off-site
protection.

Data owners and retention/security stakeholders must approve retention by data
class, legal-hold handling where applicable, expiry/deletion of all copies, logs
and keys, deletion audit, and documented exceptions. Keep required keys and base
backups/log chains usable for the entire approved recovery window; premature key
destruction or log pruning can invalidate retained data. Resolve conflicts among
immutability, deletion duties, cost, and recovery requirements before acceptance.
No retention value or legal requirement is invented here.

### Restore verification and PITR

Recommend isolated restores without overwriting authoritative/live data, with
checksums plus actual readability, schema/version, referential and application
invariants, role/grant checks, and service-level validation. Include environment
ownership, operation history/replay, and workload-specific consistency checks;
an intact archive alone is insufficient. Keep writers and external side effects
disabled until identity, grants, data, and target ownership are verified.

Require a per-dataset disposition for PITR: needed, not needed with rationale,
or explicitly deferred. If needed, select the engine-supported base/log strategy,
earliest/latest recoverable points, log-gap detection, timestamp/transaction
semantics, recovery-window policy, and treatment of cross-service consistency.
Verify recovery to reviewed points before and after a known change, including
denial or explicit failure for a missing log/key or unavailable point. A logical
export alone must not be described as arbitrary PITR.

### HA, failover, and failback boundaries

Record availability needs separately for the database, identity/key services,
management components, storage, and workloads by pod/node/cluster/site failure.
Evaluate replica placement, synchronous/asynchronous durability trade-offs,
capacity, quorum/witness dependencies, failure detection, and operation during
partitions only after A/B/C constrain them. A database promotion does not recover
the complete platform or switch user traffic safely.

Reconcile with D without independently changing its pending continuity choice.
Any eventual promotion must prove exclusive write authority: quiesce/fence old
application, worker, database, and other write paths, verify target data and
approved loss, then authorize promotion/readiness and traffic change. An
unreachable site is not proven fenced; stop when authority or data safety is
uncertain. F supplies scoped credentials/network access, not permission to
bypass fencing.

Failback needs separate approval, authoritative-state reconciliation,
resynchronization, fencing and readiness checks. Do not automatically route
back on old-site return, and do not treat routing reversal as rollback after
new writes. Automated detection/promotion requires separate reviewed mechanisms
and failure tests; no HA or site failover is authorized here.

## Rationale

Separating data protection, recoverable time, availability, and continuity avoids
claiming one mechanism solves all failure modes. Policy-first comparison exposes
unowned workload data and gaps in key/identity recovery before selecting tools.
Independent copies resist correlated loss, but increase storage, custody,
deletion, and restore-testing costs. PITR and HA should only add complexity
where reviewed requirements justify it; no option is accepted by recommendation.

## Dependencies

- [ARCH-01A](ARCH-01A-hosting-control-plane-ownership.md) and
  [ARCH-01B](ARCH-01B-management-placement-environment-boundaries.md) must resolve
  hosting, management placement, storage/administrative failure domains, and
  ownership. C must resolve objectives and measurement events; D must resolve
  transition authority, fencing, and continuity boundaries before dependent
  mechanism selection/implementation.
- [ARCH-01E](ARCH-01E-identity-account-management.md) and F must resolve recovery
  identities, least-privilege backup/restore access, encryption/key custody,
  certificate trust, permitted off-site paths, and revocation after recovery.
- Candidate ordering, subject to C: foundation/network plus independently
  controlled emergency access → identity and recoverable keys/secrets/certificates
  → storage/database restore → management → workloads. If identity requires its
  database or keys require management, document a separate bootstrap recovery
  path and custody owner rather than assuming the cycle resolves itself.
  A backup encrypted by a key available only in the lost site is not recoverable.
- Restore authorization state with the data, then reconcile current entitlements
  and rotate/revoke stale restored credentials before enabling writers or user
  access. Never resurrect revoked privileges merely because an older backup
  contains them. Recovery of keys must respect the approved retention window.
- All upstream assignments, objectives, placement, bootstrap paths, and provider
  capabilities above remain unresolved assumptions, not implemented prerequisites.
  Relevant explicit decision acceptance and separately reviewed safety/evidence
  work are required before dependent implementation or live validation.
- GITOPS-01, PERSIST-01, CAPACITY-01, and RESTORE-01 remain separate and open as
  documented; neither accepting G nor any single drill closes them.

## Consequences

- If accepted, each dataset needs a policy owner, operators, independent access
  and key recovery, a deletion process, and recurring restore evidence.
- Recovery verification consumes isolated capacity and operator time. Replication
  adds cost and may increase data-loss or availability trade-offs during partitions.
- Failed integrity, key, log-chain, or fencing checks must stop recovery/promotion
  rather than weaken controls. Restored targets stay isolated until approved.
- Decision acceptance is not an implementation grant, achieved objective, proof
  of independent sites, or production-readiness claim.

## Acceptance criteria and evidence

Before acceptance, require a reviewed disposition containing:

1. Named accountable owners/reviewers and a complete dataset/component matrix:
   inclusion/exclusion, consistency boundary, backup type/schedule, retention and
   deletion/hold rules, off-site/key custody, restore target, and verification
   cadence. Every numeric policy field must have an approved value with units
   or an owned, time-bounded deferral identifying blocked work.
2. C-linked RPO/RTO and availability/failure-scope requirements, and independent
   dispositions for backup restore, PITR, HA, and site continuity for every
   applicable scope. No objective is inferred from historical drill durations.
3. A future isolated restore plan covering all included data classes, source-site
   loss, unavailable normal identity/key services, and corrupt/missing copies.
   Require all specified integrity/application/grant checks to pass and
   unauthorized access or restored revoked privileges to remain denied.
4. For any proposed PITR scope, a future base/log continuity and point-selection
   test plan with known before/after data markers, measured recovered point,
   complete approved window coverage, and safe failure on gaps or missing keys.
   For off-site/retention policy, plan source-independent restore, unauthorized
   deletion denial, expiry/hold checks, and key/log lifecycle verification.
5. Distinct future HA and site-loss plans where required, including partition,
   uncertain fencing, standby capacity, both switch directions, old-site return,
   and failback. Require zero simultaneous authoritative writers, promotion
   denial without proven fencing, and D-compatible approvals and rollback limits.
6. For each later drill, define loss/outage start and recovery-ready end events,
   recovered-data comparison, elapsed recovery and data-loss measurement against
   approved C objectives, and numeric pass/fail bounds. Retain target, revision,
   configuration, commands, timestamps, approvals, copy/checksum and log-range
   identifiers, failures, and sanitized durable evidence outside the task
   environment. Never include data exports or credentials in public evidence.

These are decision-completeness criteria and future evidence requirements.
No backup, retention/deletion, PITR, HA, failover, or failback check ran for this
proposal. Historical gate evidence and outstanding retention obligations are
unchanged.

## Explicit deferrals / out of scope

| Deferred item | Accountable role (named assignment unresolved) | Revisit condition | Work that remains blocked |
|---|---|---|---|
| Numeric RPO/RTO and final recovery order | Platform recovery and service/data owners via C | C's impact/failure review before G acceptance | Objective-dependent mechanism selection and recovery acceptance claims |
| Providers, backup schedules, retention/deletion values, off-site/key custody, and PITR tooling/windows | Database/data owners with recovery, identity/key, and retention/security reviewers | C objectives and A/B/E/F boundaries reconciled, before G acceptance | Scheduled backup/expiry automation, PITR rollout, dependent live drills |
| HA topology, replication, promotion automation, fencing implementation, and failback tooling | Platform/database operations with D's recovery authority | C/D/F explicitly accepted and component capacity/failure constraints reviewed | HA/site provisioning, replication and automated failover/failback |
| Bootstrap identity/key recovery and external workload-data protection handoffs | Identity/key administrators and workload-data owners | C/E/F dependency inventory reviewed, before dependent G scope is accepted | Recovery relying on unverified access/key paths or unowned datasets |
| Destructive/site-loss or production exercises | Platform recovery operations and service owners | Relevant decisions accepted and separately scoped safety, rollback, and evidence plan reviewed | Execution of those exercises under this proposal; no production authorization |

No gate-status/history change, FreeLens edit, or deeper runtime/infrastructure
implementation is part of this record.

## Follow-up work

- [ ] Platform team: confirm accountable owner, required reviewers, recovery authority, and upstream dispositions; record acceptance or owned, time-bounded deferrals.
- [ ] Database/data owners and retention/security reviewers: prepare the protection matrix and option/cost comparison without inventing approved schedules or objectives.
- [ ] Recovery operations with identity/key administrators: map bootstrap, copy/key availability, grant reconciliation, and D/F fencing dependencies.
- [ ] After relevant acceptance, platform operations: propose separate backup/restore, PITR, HA, and continuity implementation/validation work with scoped targets, safe stop conditions, and sanitized durable evidence.
