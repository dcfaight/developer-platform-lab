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
development proxy; it holds no Kubernetes credentials. There is no automatic
expiration cleanup, periodic post-success health reconciliation, production RBAC,
or authentication/authorization. Namespace ownership is a resource-safety check,
not a user identity or access policy.
