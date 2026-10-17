# Architecture

The [root README](../README.md#6-architecture) is the current design blueprint.
The backend API and opt-in worker share one Spring Boot application, with
separate request, operation/lease, worker, and Kubernetes packages.

1. The request transaction reserves the idempotency key and writes the environment,
   operation, and audit event together. `202` means accepted, not provisioned.
   Delete requests instead lock the environment row, reuse active deletion work,
   and atomically record `DELETING` with a new operation and audit event.
2. Workers use PostgreSQL row locks with `SKIP LOCKED` to claim due operations.
   A new lease token fences each claim; expired claims are recoverable after restart.
3. Kubernetes calls happen outside database transactions. Repeatable provisioning
   reconciles only platform-owned resources in the dedicated local kind cluster.
4. Fenced transactions persist progress, retries, completion, and failure events
   alongside environment state. `READY` requires observed Deployment readiness.
   `DELETED` requires observed namespace absence, not just an accepted Kubernetes
   delete. Deletion verifies platform/environment ownership and any original
   create-operation markers, and uses UID/resource-version preconditions. Older
   owned namespaces without these newer markers retain the original strict
   platform/environment ownership requirements.
5. Operation detail exposes sanitized durable history and the original request
   correlation ID. Raw Kubernetes errors and credentials are not returned.

This is at-least-once processing, not distributed exactly-once execution.
Lease expiry can overlap Kubernetes calls; ownership checks and optimistic
concurrency make repeat execution safe. The lease token protects database writes,
not the Kubernetes API. The React portal submits creation and confirmed deletion,
and consumes environment lists, details, and operation history through the Vite
development proxy; it holds no Kubernetes credentials. The manually invoked
Python expiration CLI defaults to dry-run and uses the same delete API, with a
row-locked cutoff/state guard preventing terminal-failure retries. It has no SQL
or Kubernetes access. All API reads and writes require stateless HTTP Basic
credentials. `OPERATOR` has platform-wide read/create/delete access; `DEVELOPER`
can read/delete only team-owned environments and must specify a member `teamId`
on creation. Operators may leave creation unowned for compatibility; legacy rows
stay operator-managed. Exclusive `CLEANUP` accounts can discover all environments
through list/detail reads and send expiry-guarded deletes, but cannot read
operation history/direct operations or metrics.
Metrics are operator-only; health probes remain anonymous. Audit events record
the authenticated username, and new idempotency reservations are caller/team-scoped.
Trusted SQL administrators provision `platform_teams` and
`account_team_memberships`; passwords/roles remain in account configuration.
There is no expiration scheduler, periodic post-success health reconciliation,
SSO, GitOps, ownership transfer, or production tenant isolation. Namespace
ownership remains a resource-safety check, separate from team authorization;
worker RBAC and ingress are unchanged.
