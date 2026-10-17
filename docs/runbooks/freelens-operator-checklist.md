# FreeLens operator checklist

Use FreeLens as a visual operator aid for a quick, read-only view of the
dedicated disposable cluster. This optional checklist supplements the
[infrastructure runbook](../../infra/README.md) and existing validation gates;
it is not a new gate, roadmap dependency, or production-readiness claim.
Current status and evidence requirements remain in the
[Operating Model and Delivery Sequence](../operating-model-and-sequence.md).

## Before looking

With FreeLens installed, open the cluster from your existing local kubeconfig.
Confirm its context and API server match the intended disposable target in the
infrastructure runbook; do not rely on the display name alone. Keep kubeconfigs
and credentials local and out of screenshots or follow-up notes.

Use list/detail views, logs, and events only. Do not edit, scale, restart, delete,
open pod shells, or change access permissions for this check. If a view is
unavailable under your current permissions, note it rather than widening access.

## Visual checks

- [ ] **Cluster:** confirm the correct context is selected and the expected
  single node reports `Ready`.
- [ ] **Namespaces:** check system namespaces and, for the K3s platform deployment,
  `platform-lab`. Existing provisioned environments use
  `env-<environment-UUID-without-hyphens>`; compare with the portal/API record.
  Do not create an environment just to complete this checklist.
- [ ] **Workloads:** in Workloads/Deployments, check desired and available replicas
  agree for deployed platform components and any existing `sample-service`.
- [ ] **Pods:** inspect readiness, status, and restart counts. Investigate pending,
  failed, or crash-looping pods; note increasing restarts rather than assuming
  every historical nonzero count is a new failure.
- [ ] **Services:** for the K3s platform, inspect `backend`, `frontend`, and
  `postgres` and their endpoints/EndpointSlices for ready pod addresses matching
  their selectors. Sample environments currently have a Deployment, not a Service.
- [ ] **Logs/events:** view recent logs for an existing sample or platform pod and
  namespace/pod events for readiness failures, scheduling warnings, or errors.
  A quiet event list alone is not proof of health.
- [ ] **Follow-up:** note the time, context, namespace/resource, and unexpected
  observation (or unavailable view), with sensitive log content redacted.
  Use the existing runbook for investigation; do not remediate through FreeLens
  as part of this checklist.

Visual observations do not replace retained gate evidence or close architecture/
recovery decisions. No restart, recovery drill, or capacity test is required here.
