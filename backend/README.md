# Backend

Java 17 + Spring Boot 4.1.1, built with the existing Maven Wrapper. Open this
directory as a Maven project in IntelliJ IDEA. On Windows use `mvnw.cmd`.
The server binds to http://127.0.0.1:8080.

The persistent slice stores environments, operations, idempotency reservations,
and audit events in PostgreSQL, with Flyway migrations applied on startup.
See the [API contract](../docs/api.md) for schemas and validation.
An opt-in worker claims create and delete operations with PostgreSQL leases, provisions
platform-owned namespaces and an approved sample Deployment in local kind,
and records durable progress and sanitized failures in operation detail.
Acceptance remains `REQUESTED` / `PENDING`; only observed workload readiness
permits `READY`. Deletion is explicit; TTL expiration does not trigger cleanup.
The [manual expiration CLI](../automation/README.md) defaults to dry-run and
requests deletion only with `--execute`; no expiration daemon is installed.
Omitted or `null` TTL defaults to eight hours.
The [frontend portal](../frontend/README.md) supports environment creation,
confirmed deletion, list/detail reads, and paginated operation history. It uses
only the backend API and never accesses Kubernetes directly. Environment responses include
a nullable latest-operation summary; existing operation detail fields are unchanged.

## Run locally

Docker with Compose and a nonempty local database password are required.
From the repository root:

```sh
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD to a nonempty local-only value.
docker compose up -d --wait postgres

# Source only your trusted local .env: this executes shell code.
set -a
. ./.env
set +a
export DB_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DB}"
export DB_USERNAME="$POSTGRES_USER"
export DB_PASSWORD="$POSTGRES_PASSWORD"

cd backend
./mvnw spring-boot:run
```

Compose reads `.env` automatically; Spring Boot does **not**. The shell exports
above map Compose variables to the backend configuration. For IntelliJ, set
these same `DB_*` variables in the run configuration.

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/platform_lab` |
| `DB_USERNAME` | `platform_lab` |
| `DB_PASSWORD` | Required; no default |

No Kubernetes cluster is required for API-only use. There is no authentication or added CORS
configuration; keep this API in a trusted local environment. Audit events use
a fixed local-development actor, never caller actor headers.

## Enable local provisioning

Create the dedicated cluster using [infra/README.md](../infra/README.md), then,
in the shell with the same `DB_*` exports:

```sh
export WORKER_ENABLED=true
export PLATFORM_KUBERNETES_KUBECONFIG="$HOME/.kube/config"
cd backend
./mvnw spring-boot:run
```

The worker runs inside Spring Boot; there is no separate worker process.
Multiple backend instances may share PostgreSQL. Each uses a unique claim token
and short database transactions; Kubernetes calls never hold database row locks.
Due operations are claimed with `FOR UPDATE SKIP LOCKED`, and abandoned leases
are reclaimable after restart. Old/expired claim holders cannot publish results.

The client explicitly selects `kind-developer-platform-lab`, requires a loopback
HTTPS API server, and rejects executable credential plugins. It does not use the
current context as permission to reach arbitrary clusters. Keep the kubeconfig
trusted and local; never commit it. Only persisted `env-<UUID without hyphens>`
namespaces and the fixed `sample-service` Deployment/image are allowed.
Unowned resources are not adopted or overwritten.

Default worker settings under `platform.worker`:

| Property | Default |
|---|---|
| `enabled` | `false` (`WORKER_ENABLED`) |
| `poll-delay-ms` | `1000` |
| `lease-duration` | `30s` |
| `max-attempts` | `5` |
| `retry-backoff` | `2s` |
| `readiness-poll-interval` | `5s` |
| `readiness-timeout` | `5m` |

Readiness polls do not consume the transient-error retry budget. Transient
Kubernetes errors use bounded retries; validation, authorization, and ownership
failures are terminal. A readiness deadline prevents indefinite waiting.
Operation detail retains UTC timestamps, attempt counts, sanitized messages,
and progress events. Lease expiry permits at-least-once execution; idempotent
Kubernetes reconciliation and database fencing protect repeated work.

## Safe asynchronous deletion

`DELETE /api/v1/environments/{id}` returns `202 Accepted`, a `Location` pointing
to `/api/v1/operations/{operationId}`, and an `AcceptedEnvironmentResponse`:

```json
{"environmentId":"<uuid>","operationId":"<uuid>","environmentStatus":"DELETING","operationStatus":"PENDING"}
```

No request body or idempotency key is required. Environment row locks and a
partial unique active-operation index serialize concurrent requests. Repeats
reuse the active `DELETE_ENVIRONMENT` operation and return its durable `PENDING`
or `RUNNING` status. `REQUESTED`, `PROVISIONING`, and `DELETED` return `409`;
`READY`, `FAILED`, and `DELETE_FAILED` permit a new deletion. Malformed/unknown
IDs return `400`/`404`. After terminal failure, retrying creates a new operation
and audit record without replacing history.

The optional `expiredBefore` query parameter is an ISO-8601 instant no later than
the backend clock. Under the environment lock it restricts **new** deletion to
expired `READY`/`FAILED` records, returning `409` for unexpired or `DELETE_FAILED`
records. Active deletes are reused; the normal unguarded manual retry remains
unchanged. Cleanup rechecks detail and uses this atomic guard rather than claiming
a client read removes lifecycle/expiry races. See the [API contract](../docs/api.md#guarded-expiration-deletion).

API errors use `INVALID_REQUEST` (`400`, malformed UUID), `NOT_FOUND` (`404`),
`ENVIRONMENT_STATE_CONFLICT` (`409`, disallowed state or active create), and
`INVALID_ENVIRONMENT_NAMESPACE` (`409`, noncanonical persisted namespace).

The worker requires the canonical namespace, matching managed-by/environment
labels **and** annotations, and a persisted original CREATE operation. Newly
provisioned namespaces additionally carry the original operation ID in the
`platform-lab/create-operation-id` label and annotation. If either marker is
present, both must match that persisted original operation; partial or mismatched
markers fail with `KUBERNETES_OWNERSHIP_CONFLICT`.

For compatibility with existing platform environments, deletion also accepts
the original ownership scheme when **both** create-operation markers are absent.
All canonical-name, managed/environment label/annotation, persisted-original-
operation, lease, and UID/resourceVersion checks still apply. No namespace is
relabelled or adopted. Missing managed/environment ownership or a missing original
CREATE operation fails closed, including for legacy namespaces. Namespace DELETE
requests include both UID and resourceVersion preconditions; ownership races
are retried after a fresh read, never by forcing deletion.

Accepted Kubernetes deletion is not completion. A terminating namespace stays
`DELETING` until a subsequent read observes absence, then becomes `DELETED`.
Transient failures use the existing retry budget/backoff; deletion uses the
existing `readiness-timeout` as its durable deadline (`DELETION_TIMEOUT`).
Terminal failure sets `DELETE_FAILED`. Expired leases cannot publish results,
and lease ownership is rechecked immediately before issuing a Kubernetes
DELETE. After an accepted DELETE followed by a crash, reclamation reads the
namespace again, including after the last attempt, rather than recreating
resources or assuming deletion completed.

There is exactly one original `CREATE_ENVIRONMENT` operation per environment,
enforced by a unique index. Claims reference that exact operation, with no
oldest/latest-operation fallback. Missing or mismatched namespace ownership
fails closed. CREATE execution checks its lease before starting and immediately
before each namespace/Deployment create or replace. Existing legacy namespaces
remain readable/reconcilable but are not assigned new create-operation ownership.

Database lease checks and Kubernetes requests are not one atomic transaction.
A process can pause after its final lease check or leave an HTTP request in
flight when its lease expires; a late CREATE could therefore finish after
failure/deletion acceptance. Preflights reduce this window but cannot eliminate
it: a database lease cannot revoke an in-flight Kubernetes request. The same
limitation applies to an already-issued DELETE; its UID/resourceVersion
preconditions protect the inspected namespace, not the database lease.
Preventing this residual cross-system race requires a Kubernetes-side
admission/fencing controller or equivalent external coordination. Database
fencing still prevents stale holders from publishing operation results.

The default deletion deadline is five minutes from the first claim; reclaiming
does not reset it. `DELETION_TIMEOUT` reports
`Deletion did not complete within its limits.` Other durable failure codes are
`KUBERNETES_OWNERSHIP_CONFLICT`, `KUBERNETES_VALIDATION_FAILED`,
`KUBERNETES_UNAVAILABLE` (retryable), `KUBERNETES_REQUEST_REJECTED`,
`KUBERNETES_CONFIG_INVALID`, and `INTERNAL_ERROR`. Only absent namespaces can
complete successfully; no finalizers are removed to force completion.

DELETE crash retries use `max-attempts` until an accepted/terminating namespace
observation is persisted. An exhausted reclaim performs a final read: absence
succeeds and termination continues polling, but an active namespace fails with
`ATTEMPTS_EXHAUSTED` / `Deletion did not complete within its attempt limits.`
without another DELETE. Normal termination polls do not consume attempts.
Repeated crashes during reconciliation remain bounded by the original durable
deadline, including crashes before the accepted DELETE could be persisted.

This slice observes initial Deployment readiness, not ongoing health after
success. It does not provide custom manifests/images, Services, ingress,
scheduled cleanup, production RBAC, or authentication. The portal supports
creation and confirmed deletion through the backend API; it has no direct
Kubernetes access.
Expired TTL metadata does not remove resources.

## Manual API checks

Run these in another terminal. The first request returns `202`, both IDs,
`REQUESTED` / `PENDING`, a `Location` operation URL, and `X-Request-Id`:

```sh
curl -i -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-demo-1' \
  -d '{"name":" demo ","templateId":"sample-service"}'

# Equivalent normalized request: same IDs, states, and Location; no new writes.
curl -i -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-demo-1' \
  -d '{"name":"demo","templateId":"sample-service","ttlHours":8}'

# Different normalized input with the same key: 409 IDEMPOTENCY_CONFLICT.
curl -i -X POST http://127.0.0.1:8080/api/v1/environments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: local-demo-1' \
  -d '{"name":"different","templateId":"sample-service","ttlHours":8}'

curl -i 'http://127.0.0.1:8080/api/v1/environments?page=0&size=20'

# Replace these values with the IDs from the first response.
ENVIRONMENT_ID='<environmentId>'
OPERATION_ID='<operationId>'
curl -i "http://127.0.0.1:8080/api/v1/environments/$ENVIRONMENT_ID"
curl -i "http://127.0.0.1:8080/api/v1/operations/$OPERATION_ID"

# Malformed UUID: 400. Valid but unknown UUID: 404.
curl -i http://127.0.0.1:8080/api/v1/environments/not-a-uuid
curl -i http://127.0.0.1:8080/api/v1/operations/00000000-0000-0000-0000-000000000000
```

To verify persistence, stop the backend, run `docker compose restart postgres`
from the repository root, then restart the backend with the same configuration.
Repeat the replay and detail requests: IDs and records are retained. Even
`docker compose down` followed by `docker compose up -d --wait postgres`
retains the named volume. `docker compose down --volumes` intentionally
discards all local database data, including idempotency history.

## Tests

From `backend/`, with Docker running:

```sh
docker info
./mvnw verify
```

Backend integration tests use Testcontainers with `postgres:18.6`; they require
a working Docker daemon and do not skip when Docker is unavailable. They use
their own database container, not the Compose database, and need no local
`DB_*` exports. CI checks Docker availability before Maven verification.

Kubernetes HTTP tests exercise the actual client against a local API test
server. The smaller cluster test is opt-in locally and runs in its own CI job:

```sh
# After creating the dedicated kind cluster:
PLATFORM_KIND_TEST=true ./mvnw -Dtest=KindProvisioningTests,KindWorkerFlowTests test
```

These tests provision and re-apply a unique namespace/Deployment and exercise
HTTP acceptance through the worker to real workload readiness and namespace
deletion (the full-flow test also uses Testcontainers PostgreSQL). The smaller
provisioning test leaves resources in the disposable cluster; use the manual
whole-cluster reset in the infrastructure guide when finished.
