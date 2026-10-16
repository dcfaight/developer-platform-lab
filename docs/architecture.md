# Architecture

The [root README](../README.md#6-architecture) is the current design blueprint.
The backend API and opt-in worker share one Spring Boot application, with
separate request, operation/lease, worker, and Kubernetes packages.

1. The request transaction reserves the idempotency key and writes the environment,
   operation, and audit event together. `202` means accepted, not provisioned.
2. Workers use PostgreSQL row locks with `SKIP LOCKED` to claim due operations.
   A new lease token fences each claim; expired claims are recoverable after restart.
3. Kubernetes calls happen outside database transactions. Repeatable provisioning
   reconciles only platform-owned resources in the dedicated local kind cluster.
4. Fenced transactions persist progress, retries, completion, and failure events
   alongside environment state. `READY` requires observed Deployment readiness.
5. Operation detail exposes sanitized durable history and the original request
   correlation ID. Raw Kubernetes errors and credentials are not returned.

This is at-least-once processing, not distributed exactly-once execution.
Lease expiry can overlap Kubernetes calls; ownership checks and optimistic
concurrency make repeat execution safe. The lease token protects database writes,
not the Kubernetes API. There is no deletion, periodic post-success health
reconciliation, production RBAC, authentication expansion, or frontend API wiring.
