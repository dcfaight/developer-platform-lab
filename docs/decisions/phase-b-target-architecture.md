# Phase B: Target Architecture

**Status:** Accepted

## Decision

The platform is an **Internal Developer Platform (IDP)** for approximately
**3,000 developers**. It supports standard software lifecycle environments such
as **dev, QA, and UAT**. Production is a separate continuity domain made up of
two independent sites/clusters, **`prod1`** and **`prod2`**, for planned
switchover and business continuity.

Implementation proceeds at available compute capacity, but uses
production-oriented mechanisms from the start so that scalability, resilience,
backup and restore, identity, policy, and observability can be validated rather
than assumed.

## Assumptions and scope

- The 3,000 figure is the approximate developer population; it is not a claim
  about environment count, concurrent operations, pods, or nodes.
- Dev, QA, and UAT are lifecycle environments. Production sites are separate
  from that non-production lifecycle.
- `prod1` and `prod2` are intended to be independent sites/clusters. Their
  detailed placement, data replication, failover, and traffic-management
  mechanisms remain implementation decisions.
- Capacity, availability, and recovery objectives must be measured and
  documented. This decision does not claim they have been met.

## What this decision means

- Design implementations and validation around the target topology, even when
  initial deployments use less compute.
- Treat planned switchover, backup/restore, identity, policy, and observability
  as mechanisms to implement and validate, not as assumed capabilities.
- Use **Internal Developer Platform (IDP)** or **IDP** in product-facing
  language instead of “lab.”

## What this decision does not mean

- It does not claim the current implementation is production-ready, supports
  3,000 developers at measured capacity, or has validated site independence.
- It does not prescribe a Kubernetes distribution, hosting model, control-plane
  ownership model, active/active or active/passive operation, or specific
  recovery objectives.
- It does not authorize production deployment, failover, or destructive
  recovery operations.
- It does not rename compatibility-sensitive technical identifiers.

## Legacy identifiers

The repository and existing deployments still use “lab” in technical
identifiers, including the repository name, the
`kind-developer-platform-lab` context, the `io.github.dcfaight.platformlab`
Java package, the `platform-lab` Kubernetes namespace, and `platform_lab`
database names. These are intentionally preserved for compatibility; this
decision changes product language, not resource identities. Any future
identifier migration should be planned and validated separately.

## Operating model and delivery sequence

The [operating model and delivery sequence](../operating-model-and-sequence.md)
records how this target informs current implementation without resolving the
remaining control-plane and recovery decisions.
