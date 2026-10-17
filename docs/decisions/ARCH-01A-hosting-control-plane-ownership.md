# ARCH-01A: Hosting, Control-Plane Ownership, and Isolation

**Status:** Proposed
**Date:** 2026-10-17
**Owner / reviewers:** Proposed accountable owner: platform team; reviewers: platform operations and security. Confirm named owners during review.
**Roadmap reference:** [ARCH-01A — Hosting and control-plane ownership](../operating-model-and-sequence.md#open-decision-register); review alongside [ARCH-01C — Recovery objectives and restore order](ARCH-01C-recovery-objectives-restore-order.md).

## Context

The accepted [Phase B target architecture](phase-b-target-architecture.md)
describes an IDP for approximately 3,000 developers and an intended `prod1` /
`prod2` continuity pair. It does not choose a hosting model, control-plane
owner, management placement, isolation design, or production deployment.

This decision must establish who operates hosting and control planes, where
those control planes are hosted, and the required separation between management
and developer workloads. It must account for upgrades, patching, monitoring,
incident response, privileged access, failure domains, and recovery. The general
platform-team-operated model does not, by itself, settle provider or component
ownership.

## Options considered

- **Managed hosting and control planes:** use provider-operated infrastructure
  and control-plane services. Compare reduced infrastructure operations with
  provider dependency, service limits, access boundaries, and cost.
- **Self-operated hosting and control planes:** have the platform team operate
  the infrastructure and control-plane lifecycle. Compare direct operational
  control with staffing, upgrade, availability, and incident-response burden.
- **Split or hybrid ownership:** assign hosting, control plane, and management
  services to different operators or providers. Compare flexibility with
  cross-owner dependencies, escalation paths, and responsibility gaps.
- **Defer selection:** retain the current disposable implementation while
  collecting owner, cost, capability, and failure-domain constraints. This
  delays shared-infrastructure design and any production-facing expansion.

## Decision

**No hosting, control-plane ownership, or isolation option is selected by this
proposal.** Review the options against the criteria below, together with
ARCH-01C, and record an explicit selection or a time-bounded deferral before
dependent implementation is proposed.

## Rationale

The target topology and platform-team operating intent do not provide enough
information to infer who owns each service or what isolation is required.
Recording a proposed evaluation rather than treating the current disposable
setup as the target avoids silently committing to an unsupported provider,
operating model, or security boundary. Reviewing hosting constraints alongside
recovery objectives makes it possible to identify unsupported recovery
expectations before selecting infrastructure.

## Dependencies

- Preserve the accepted Phase B topology and its non-claims; do not treat it as
  a hosting decision.
- Review jointly with ARCH-01C so hosting and ownership constraints are
  evaluated against proposed recovery objectives and restore dependencies.
- Use the resulting boundaries to frame ARCH-01B management placement and
  environment boundaries, then ARCH-01D through ARCH-01G as identified in the
  roadmap.
- Existing GitOps, restart-persistence, capacity, and logical-restore gates
  remain separate; their results do not select or validate this architecture.

## Consequences

- No hosting model, control-plane location, isolation boundary, or production
  deployment is authorized by this record.
- Until a disposition is accepted, shared-infrastructure design and
  production-facing expansion that depend on ownership or isolation remain
  blocked.
- A managed, self-operated, split, or deferred outcome will each require an
  explicit operational owner, support boundary, cost model, and failure-domain
  account; the selected option may leave follow-up decisions open.

## Acceptance criteria and evidence

Before this decision is accepted, its reviewed disposition must include:

1. A named accountable owner and a responsibility matrix covering hosting,
   control-plane lifecycle, upgrades, patching, monitoring, incident response,
   privileged access, and recovery.
2. A hosting and control-plane boundary for each in-scope environment, with
   provider/operator dependencies and failure domains identified.
3. Explicit management/workload isolation boundaries covering administrative
   identity, credentials, network paths, and blast radius.
4. Approved availability, operating, and cost constraints, with units,
   measurement or estimation method, and accountable approver for each
   constraint.
5. A recorded comparison of the options above and the risks, mitigations, and
   implementation work for the selected option, or a deferral naming its owner,
   revisit condition, and blocked work.

These are decision-record completeness criteria, not a claim that hosting,
isolation, or production readiness has been implemented or validated. Any later
validation must name its target, checks, and durable evidence separately.

## Explicit deferrals / out of scope

- Exact placement of the portal/API, workers, GitOps, identity services, or
  platform database, and detailed disposable-versus-long-lived environment
  boundaries (ARCH-01B).
- The `prod1` / `prod2` deployment, active/active or active/passive design,
  traffic switching, replication, failover, and failback (ARCH-01D and ARCH-01G).
- SSO/OIDC, account lifecycle, detailed network policy, secret rotation, and
  database role design (ARCH-01E and ARCH-01F).
- Choosing a provider, purchasing capacity, provisioning shared infrastructure,
  or authorizing production changes in this proposal.
- Closing or changing any live-validation gate or inferring production
  readiness from existing disposable-target evidence.

## Follow-up work

- [ ] Platform team: review hosting/ownership options and isolation constraints with ARCH-01C; record accepted disposition or an owned, time-bounded deferral.
- [ ] Platform operations and security reviewers: confirm named owners, constraints, and evidence needed before dependent implementation is proposed.
- [ ] After acceptance, link separately scoped implementation and validation work; keep dependent ARCH-01 decisions open until their stated prerequisites are met.
