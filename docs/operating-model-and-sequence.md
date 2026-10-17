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
  evidence and limitations. This PR is the current bounded increment: resource
  budgets for newly provisioned development namespaces, with lifecycle and
  ownership safety preserved.

The next delivery sequence is:

1. Implement identity and team authorization for self-service access.
2. Establish reproducible GitOps deployment and rollback.
3. Validate backup and restore, including recovery evidence.
4. Measure capacity with explicit workload and concurrency targets.

Nightly CI is supporting backlog automation, not a substitute for these
milestones. None of these steps establishes production readiness until its
acceptance evidence exists.
