# ARCH-01F: Network and Credential/Data-Access Hardening

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; required reviewers: platform operations, network administrators, identity/key administrators, database/data owners, and security. Named assignments and acceptance authority remain unresolved and must be confirmed during review.
**Roadmap reference:** [ARCH-01F — Network and credential/data-access hardening](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01B — Environment boundaries](ARCH-01B-management-placement-environment-boundaries.md), [ARCH-01E — Identity/account management](ARCH-01E-identity-account-management.md), [ARCH-01C — Recovery objectives](ARCH-01C-recovery-objectives-restore-order.md), and [ARCH-01G — Recovery mechanisms](ARCH-01G-retention-pitr-ha-failover.md).

## Context

Management services, workers, GitOps, identity dependencies, platform data, and
developer workloads need explicit network and credential boundaries. Dev/QA/UAT
access must not implicitly confer production access; intended `prod1`/`prod2`
independence must not become unrestricted peer control.

ARCH-01A–E remain Proposed even where their documentation PRs merged. In
particular, B's separate management boundary and E's identity lifecycle are
recommendations, not accepted placement or authentication designs. This record
must be reconciled with their eventual dispositions and C's unresolved recovery
order. It neither describes deployed isolation nor authorizes configuration.
The accepted [Phase B intent](phase-b-target-architecture.md) does not settle
these controls or establish production readiness.

## Options considered

- **Shared network and broad shared credentials:** simplest for disposable
  targets, but increases lateral movement, cross-environment compromise, and
  ambiguous audit ownership. Retaining it is not a production hardening outcome.
- **Boundary-scoped default-deny connectivity and purpose-specific identities:**
  makes permitted paths, authority, and revocation testable. It adds policy,
  credential distribution, exception review, and recovery-access costs.
- **Dedicated infrastructure plus layered controls:** stronger isolation where
  namespace/network policy cannot enforce a reviewed boundary, but adds cost and
  operational dependencies. Physical separation alone does not replace access
  policy or credential lifecycle controls.
- **Defer enforcement selection:** retain bounded disposable use while reviewing
  B/E/C; avoids choosing an unsupported policy engine but blocks dependent
  hardening rollout and production-facing access expansion.

## Decision

**Pending review: recommend explicit permitted flows, least-privilege identities,
and independently recoverable security controls for each reviewed boundary.**
No policy engine, secret/key provider, certificate authority, or database role
implementation is selected.

### Network boundaries and permitted flows

Recommend default-deny ingress and egress at enforcement points appropriate to
the accepted trust boundaries. Kubernetes NetworkPolicy is only a candidate for
supported pod traffic with a verified enforcing network implementation; it does
not by itself protect host/control-plane traffic, external services, or separate
sites. Review cluster, host, gateway/firewall, and service-level controls where
needed; do not equate namespace separation or a policy manifest with enforcement.

Build an allowlist matrix before rollout. Candidate flows requiring review are:
client ingress to the portal/API; API and worker access to their database;
workers to explicitly assigned target Kubernetes APIs; GitOps to approved
repositories/artifact sources and reconciliation targets; identity, secret/key,
certificate, DNS, time, and telemetry services; and G's backup/restore paths.
For each source/destination, specify direction, port/protocol, purpose, identity,
data classification, environment/site, enforcement point, and accountable owner.
An omitted flow is denied, not implicitly approved.

Apply B's reviewed management/workload and dev/QA/UAT/production boundaries;
deny workload-to-management administrative access, unauthorized environment/team
crossing, and peer-site control by default. Any required cross-boundary or
recovery path needs a narrowly scoped, reviewed exception with expiry and audit.
Do not silently share production identities or credentials with non-production.

### Worker credentials and secret/certificate lifecycle

Recommend worker identities scoped to target environment/site and approved
resource operations, with short-lived credentials where supported. Document
residual permissions such as cluster-scoped namespace operations and why they
cannot be narrowed; a compromised worker must not gain peer-site or unrelated
environment authority. Neither network access nor recovery access grants
cluster-admin. Preserve existing ownership/deletion safeguards and E's distinct
operator, developer, and expiry-only cleanup responsibilities.

Inventory every service credential, secret, key, and certificate with issuer,
custodian, consumers, scope, storage, lifetime, rotation/revocation authority,
and recovery dependency. Recommend encryption in transit and at rest, including
secret stores and backups, with separately controlled decryption-key custody;
encoded Kubernetes Secret content is not encryption. Keep credentials out of
Git, logs, and retained drill output.

Review numeric lifetime, rotation cadence, maximum revocation delay, and
exception expiry for each class; no values are committed here. Rotation must
cover issuance, controlled distribution/overlap, consumer reload, verification,
and old-credential revocation, including incident rotation and unavailable
consumers. Never roll back by re-enabling a compromised credential.

Where TLS or client certificates are used, assign trust-root and issuance
owners, peer identity verification, renewal alerts and lead time, automated or
operator renewal, trust-bundle rollover, and expiry/revocation response. Reject
invalid identities; disabling certificate verification is not a recovery path.

### Database roles and data-access responsibilities

Recommend separate runtime, migration/schema-owner, backup, and restore/admin
identities. Runtime receives only required data operations, not schema ownership
or administrative grants; migration privileges are separately approved and
time-bounded. Backup access is limited to required readable data/metadata and
the backup destination, without unnecessary source writes; restore privileges
are restricted to an approved recovery target. Document engine/tool-specific
privilege requirements rather than assuming backup or restore can always be
unprivileged.

Database/data owners approve datasets, classifications, and grants; platform
operations execute approved access changes; security reviews privileged
exceptions and audit access. Reviewers must confirm named assignments and
independent approval or record an owned, expiring exception. Specify which
identity may access platform versus workload data, who may export/restore it,
and who verifies post-restore grants. Do not combine all duties into a shared
application account.

## Rationale

Explicit flow and grant inventories make blast radius reviewable without
presuming B/E accepted. Layered enforcement avoids overstating pod-policy
coverage. Separating key custody, routine writes, schema changes, and recovery
reduces privilege reuse, while lifecycle tests expose stale credentials and
circular recovery dependencies. The trade-off is more operating overhead and
the possibility that strict policies interrupt legitimate recovery.

## Dependencies

- Review [ARCH-01A](ARCH-01A-hosting-control-plane-ownership.md), B, E, and C
  ownership, boundaries, credential authority, and recovery dispositions before
  acceptance; reconcile site-scoped controls with
  [ARCH-01D](ARCH-01D-prod1-proxy-continuity-model.md) without selecting its model.
- With C/E/G, map foundation/network and emergency access → identity and
  independently retrievable keys/secrets/certificates → protected storage/data →
  management → workloads. This is a candidate dependency order, not a runbook.
  Resolve any identity store needing database recovery or key service needing
  management recovery through an independently controlled bootstrap path.
- G owns backup scope, retention, PITR, HA, and failover mechanisms. F must
  support authenticated backup/restore flows, recoverable decryption keys, and
  post-restore credential revocation without bypassing D's fencing.
- Dependent enforcement rollout, migration, and live hardening/recovery checks
  remain blocked until relevant decisions are explicitly accepted and separate
  implementation/validation work is reviewed. GITOPS-01, PERSIST-01,
  CAPACITY-01, and RESTORE-01 remain separate and open as documented.

## Consequences

- If accepted, every permitted path and credential class needs an owner and
  review process; denied paths and expired credentials may interrupt service.
- Rotation, policy rollout, and database-grant changes need staged validation
  and a safe stop/rollback plan preserving access restrictions and audit.
- Encryption is insufficient if keys share the protected data's failure domain;
  emergency access must remain bounded and independently recoverable.
- Acceptance would record a design disposition, not proven enforcement,
  credential safety, or production readiness.

## Acceptance criteria and evidence

Before acceptance, require a reviewed disposition containing:

1. Named accountable owner/reviewers and complete flow/enforcement and credential
   inventories for every in-scope boundary; each entry has an owner and either
   an approved purpose or an explicit owned deferral.
2. A database grant/responsibility matrix for runtime, migration, backup, and
   restore/admin, with no unexplained shared privileged identity; numeric
   lifecycle/revocation/renewal bounds and exception expiries, or owned deferrals.
3. A future disposable-target test plan for every allowed flow and representative
   denied management, environment/team, and peer-site paths. Require all approved
   flows to pass and zero unauthorized successful connections or operations,
   including unsupported/host-network enforcement cases identified in review.
4. A future worker/grant test matrix proving permitted operations still work
   while unauthorized target access, schema mutation, and excessive backup or
   restore privileges are denied. Preserve cleanup and deletion safeguards.
5. Planned rotation, compromise revocation, consumer outage, certificate expiry,
   and trust-rollover checks, measuring elapsed times against reviewed bounds.
   Old credentials must fail after the bound; invalid/expired certificates must
   fail verification. Include lost identity/key-service recovery and restored
   data/credentials, with no uncontrolled reactivation of revoked access.
6. A target/revision/configuration, command, timestamp, expected/actual result,
   approval, and sanitized durable evidence contract for each later check.
   Retain grant/policy summaries and audit references, never secret values.

These are completeness criteria and planned tests, not recorded results. No
network, rotation, certificate, database-role, or recovery validation ran for
this proposal; existing gate evidence and history remain unchanged.

## Explicit deferrals / out of scope

| Deferred item | Accountable role (named assignment unresolved) | Revisit condition | Work that remains blocked |
|---|---|---|---|
| Final placement/enforcement products, flow exceptions, and worker scope | Platform/network operations with security | A/B/E boundary and ownership dispositions reviewed, before F acceptance | Dependent policy deployment, cross-target credential rollout, production-facing access expansion |
| Secret/key/certificate tooling, numeric lifecycle bounds, and database grants | Identity/key administrators and database/data owners with security | E/C recovery constraints and G's consumer requirements reviewed, before F acceptance | Rotation/renewal automation, privileged-role migration, dependent live checks |
| Backup schedule, retention/deletion, off-site copies, PITR, HA, and data failover | Platform recovery/database owner via G | C/D/F requirements reconciled and G explicitly accepted | Scheduled data protection, replication/HA/failover rollout and related drills |
| Destructive or production hardening/recovery exercises | Platform operations and service owners | Relevant decisions accepted and a separate scoped safety/evidence plan reviewed | Live enforcement/recovery exercises under this proposal; no deployment authorization |

Gate changes, historical evidence edits, and FreeLens changes are outside this
record; none is implied by these deferrals.

## Follow-up work

- [ ] Platform team: confirm accountable owner/required reviewers, reconcile A–E/G dependencies, and record an explicit disposition or owned, time-bounded deferral.
- [ ] Network operations and security: prepare the flow/enforcement and worker-blast-radius matrices, including policy coverage gaps.
- [ ] Identity/key administrators and database/data owners: propose lifecycle bounds, grants, recovery custody, and audit responsibilities.
- [ ] After relevant acceptance, platform operations: propose separate implementation and disposable-target validation PRs with rollback boundaries and sanitized durable evidence.
