# ARCH-01E: Identity and Account Management

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; reviewers: identity administrators, platform operations, service owners, and security. Confirm named owners during review.
**Roadmap reference:** [ARCH-01E — SSO/OIDC and account management](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01B — Environment boundaries](ARCH-01B-management-placement-environment-boundaries.md), [ARCH-01C — Recovery objectives and restore order](ARCH-01C-recovery-objectives-restore-order.md), and [ARCH-01D — prod1/prod2 continuity](ARCH-01D-prod1-proxy-continuity-model.md).

## Context

The current AUTH-01 boundary uses Basic-authenticated operator, developer, and
cleanup roles with team-scoped developer access. Its bounded evidence does not
establish SSO, an account lifecycle, or production identity assurance. The target
platform-team-operated IDP needs accountable access across management,
dev/QA/UAT, and production without silently widening existing permissions.

ARCH-01A/B/C remain Proposed. Identity trust and recovery must follow their
reviewed boundaries rather than assume a deployed management environment.
ARCH-01D also remains Proposed: recovery access must support its review without
turning identity failure into permission to bypass fencing or data checks.

## Options considered

- **Retain locally managed Basic accounts:** minimal integration work and useful
  for bounded development, but lifecycle, privileged access, and revocation
  remain separate operational burdens; this is not the target SSO model.
- **Federate human access to an authoritative organizational OIDC provider:**
  centralize authentication and lifecycle signals while keeping platform
  authorization explicit. This reduces duplicate human accounts but introduces
  provider availability, claim-trust, and session-revocation dependencies.
- **Operate a dedicated platform identity provider:** offers direct control and
  possible federation, but adds patching, availability, account governance,
  and recovery duties that must be reconciled with ARCH-01A/C.
- **Defer federation:** continue the current bounded model pending reviewed
  ownership and trust boundaries; production-facing access expansion stays blocked.

## Decision

**This proposal recommends organizational OIDC federation for human SSO, with
platform-owned authorization and explicit account lifecycle controls.** It does
not select a provider/product or change authentication in the current deployment.
Review must confirm the identity-service owner, availability and recovery path,
trusted issuer/client boundaries, and environment-specific audiences before
acceptance.

Use a stable issuer-and-subject identity rather than email as the account key.
Trust only explicitly configured issuers and validated tokens for the intended
audience; reject expired or invalid credentials. Use an appropriate reviewed
OIDC flow for browser sign-in, with MFA for privileged human access. Authentication
alone must not grant platform permissions: claims-to-role and team mapping must
be allowlisted, owned, and deny access for unmapped or revoked entitlements.
Production privileges must be separately approved, not inherited from
non-production access.

Account onboarding should require a named sponsor/owner, approved team
membership, and a least-privilege role. Developers remain team-scoped; operators
hold separately approved operational privileges; cleanup remains a distinct
non-human identity restricted to expiry-guarded deletion, not a general operator.
Identity administrators manage authentication/lifecycle, team owners approve
membership, and designated platform/security approvers authorize privileged
grants. Requesters must not approve their own elevation. Where staffing cannot
support independent approval, record the exception, compensating review, owner,
and expiry rather than silently combining duties.

Transfers and role changes must remove obsolete entitlements as well as add new
ones. Offboarding must disable authentication, revoke platform roles/memberships,
and invalidate or bound existing sessions/tokens within an approved maximum
revocation interval. Periodic access reviews must cover privileged users,
inactive accounts, team membership, and exceptions. Service identities need
individual owners, scoped purposes/environments, expiry/review dates, and a
revocation path; do not use shared human accounts for automation.

Break-glass access should use separately controlled, individually attributable
emergency identities and an access path that does not rely solely on the failed
SSO or management service. Require protected custody, limited scope/duration,
recorded activation and reason, notification to operations/security, and
independent post-use review with credential reset/revocation. Define emergency
activation authority in advance for cases where normal approval is unavailable.
Break-glass access is not routine self-service and must not bypass continuity
fencing, data-integrity checks, or namespace-deletion safeguards.

On identity-provider failure, deny new access that cannot be validated or
authorized. Any continued existing-session access must have a reviewed bounded
lifetime consistent with the revocation objective; never fall back to anonymous
or broadly privileged access.

## Rationale

Federation can consolidate human authentication and lifecycle signals without
delegating platform resource authorization to arbitrary identity claims.
Separating approval, execution, and emergency recovery reduces both accidental
privilege expansion and circular dependencies during an outage. The cost is
integration and governance work: SSO alone does not solve team authorization,
revocation, service identity ownership, or recovery access.

## Dependencies

- Review [ARCH-01A](ARCH-01A-hosting-control-plane-ownership.md), ARCH-01B, and
  ARCH-01C dispositions for identity ownership, trust boundaries, restore order,
  and outage access before accepting this model.
- Review recovery authority and break-glass assumptions alongside ARCH-01D;
  neither proposal supplies implemented failover or recovery access.
- Preserve current AUTH-01 role/team and cleanup restrictions until a separately
  reviewed migration proves equivalent or stronger protections.
- ARCH-01F must specify network/credential enforcement, secret storage/rotation,
  certificate renewal, and database roles; ARCH-01G must cover identity-state
  protection and recovery mechanisms where applicable.
- Existing live-validation gates remain separate and cannot prove identity
  lifecycle, federation, or production access assurance.

## Consequences

- If accepted, identity administrators and platform operators need an explicit
  lifecycle handoff, entitlement inventory, approval records, and audit ownership.
- Provider outages and stale sessions remain risks; reviewed session/revocation
  bounds and independently recoverable emergency access are prerequisites.
- Privileged access becomes deliberately more constrained than routine developer
  access. Role changes and emergency exceptions create review obligations.
- Acceptance is an architectural disposition, not implemented SSO, migration
  authorization, or evidence of production readiness.

## Acceptance criteria and evidence

Before this decision is accepted, its reviewed disposition must include:

1. A trust/ownership matrix covering portal/API, management access, dev/QA/UAT,
   production, cleanup, and other service identities. For each, record issuer or
   credential authority, audience/scope, owner, role/team source, and approver;
   no account class may have implicit privilege.
2. A joiner/mover/leaver and service-account lifecycle table with triggers,
   accountable executors/approvers, numeric maximum provisioning/revocation
   intervals, session/token lifetimes, review cadence, and exception expiry.
   Any unresolved value requires an owner, revisit date/condition, and blocked work.
3. An authorization/approval matrix preserving team isolation, operator separation,
   expiry-only cleanup, and denial of self-approved elevation. Include who can
   modify mappings and who independently reviews those changes.
4. A future migration test plan requiring rejection of invalid/expired tokens,
   wrong issuers/audiences, unmapped roles, cross-team access, unauthorized
   elevation, and cleanup misuse. It must verify that disabled users, removed
   memberships, and existing sessions lose affected access within the approved
   revocation interval, without an authorization gap during migration.
5. An identity-outage and break-glass exercise plan specifying activation
   authority, custody/access dependencies, bounded privilege, expiry, notification,
   and post-use review. Require zero unapproved privilege grants and no dependency
   solely on the failed service for emergency access.
6. Defined sanitized durable evidence for lifecycle events, approval decisions,
   access denials, revocation timing, emergency activation and closure, with an
   audit owner. Do not retain tokens, passwords, or recovery credentials in
   decision records or drill evidence.

These criteria measure decision completeness and define later checks; no
federation, revocation, or emergency-access result is claimed here.

## Explicit deferrals / out of scope

- Provider/product selection, client configuration, protocol/library changes,
  migration from Basic authentication, and executing identity outage drills.
- Final placement and recovery targets (ARCH-01A/B/C); implementing production
  continuity or automatic promotion (ARCH-01D and later implementation work).
- Network-policy enforcement, worker credential blast-radius controls, secret
  encryption/rotation, certificate renewal, and database runtime/migration/backup
  role separation (ARCH-01F).
- Backup retention/deletion, off-site protection, PITR, replication, database
  HA, and identity/platform data recovery mechanisms (ARCH-01G).
- Changing existing gate definitions, historical evidence, or production-readiness
  boundaries.

The platform team and security reviewers own the F/G handoff; revisit those
mechanisms after reviewed identity/trust and recovery dispositions and before
production-facing access expansion. Dependent credential/data hardening rollout
remains blocked until its own decisions are accepted.

## Follow-up work

- [ ] Platform team and identity administrators: confirm owners/provider constraints and reconcile the proposal with A–D; record acceptance or an owned, time-bounded deferral.
- [ ] Security, team owners, and operations: review lifecycle bounds, entitlement/approval mapping, and break-glass custody and outage dependencies.
- [ ] Platform team: hand detailed hardening/recovery requirements to ARCH-01F/G; after prerequisite acceptance, propose separate migration and validation work preserving AUTH-01 safeguards and retaining sanitized evidence.
