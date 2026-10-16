# Demo notes

The portal is still static; demonstrate provisioning through the backend API.

1. Start PostgreSQL and export `DB_*` as in [backend/README.md](../backend/README.md).
2. Create the dedicated kind cluster using [infra/README.md](../infra/README.md).
3. Enable the backend worker and start Spring Boot.
4. Send the documented create request; expect `202` with `REQUESTED` / `PENDING`.
5. Poll the returned operation `Location` for attempts, progress events, and
   eventual `SUCCEEDED`; the environment becomes `READY` only after the workload
   is observed ready. Image pulls can take time.
6. Replay the same request/key: the original accepted IDs/body are retained;
   read operation detail for current state. Inspect the namespace with kubectl.
7. Stop/restart the backend: persisted pending work and expired leases recover.
   Stop the cluster to exercise sanitized retry/failure reporting.

There is no automatic cleanup on TTL expiry, failed provisioning, or backend
shutdown. Manually remove the disposable kind cluster when done. Deletion,
expiration automation, continuous health monitoring, and portal integration are
follow-ups; this is not a production-ready platform.
