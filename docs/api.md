# API contract

The persistent backend slice implements the routes below under `/api/v1`.
Requests are accepted separately from infrastructure readiness. An opt-in worker
provisions and deletes the approved sample's owned namespace in local kind and
exposes progress in operation detail. A manually invoked, dry-run-first
[expiration CLI](../automation/README.md) uses the delete API; there is no cleanup
daemon. Operation timelines are embedded in operation detail/history, and
aggregate counts and timings are available from [operation metrics](#read-operation-metrics). The broader
[blueprint](../README.md#9-proposed-rest-api) describes future behavior.

## Create an environment

`POST /api/v1/environments`, with `Content-Type: application/json` and a
required `Idempotency-Key` header:

```json
{
  "name": "demo",
  "templateId": "sample-service",
  "ttlHours": 8,
  "teamId": "payments"
}
```

| Input | Validation |
|---|---|
| `name` | Required string; trim surrounding whitespace before validation and persistence. The resulting name is 1–63 ASCII letters, digits, spaces, underscores, or hyphens, starting with a letter or digit: `[A-Za-z0-9][A-Za-z0-9 _-]{0,62}`. |
| `templateId` | Required string, exactly `sample-service`. |
| `ttlHours` | Optional JSON integer, defaults to `8` when omitted or `null`; inclusive range `1`–`24`. Fractional numbers are rejected. |
| `teamId` | Lower-case slug, 1–63 characters, naming an existing `platform_teams` row. Required for `DEVELOPER` and must be one of the caller's memberships; missing, unknown or nonmember teams return `403 FORBIDDEN`. Optional for `OPERATOR`; omission keeps compatibility by creating an unowned, operator-managed environment. |
| `Idempotency-Key` | Required; matches `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. |

Unknown JSON fields are rejected. A successful request returns `202 Accepted`,
`Location: /api/v1/operations/{operationId}`, and:

```json
{
  "environmentId": "99f42e18-bab9-40ad-83d1-85bc3f6378c0",
  "operationId": "27f56e9b-382a-4373-a26c-c4dab824d127",
  "environmentStatus": "REQUESTED",
  "operationStatus": "PENDING"
}
```

`expiresAt` is the creation time plus the requested TTL. It is persisted
metadata only; passing that time does not invoke cleanup. Expiry is currently
immutable. An operator must invoke the cleanup CLI explicitly.
`sample-service` is the only approved template, mapped to a fixed sample
Deployment/image by the worker. No arbitrary manifests or images are accepted.

### Durable idempotency and atomicity

New reservations are scoped by authenticated caller and team (including the
operator's unowned scope), rather than a global key. The normalized name, exact
template ID, effective TTL, and team identify the request.
For example, `" demo "` and `"demo"` are equivalent, as are omitting TTL,
sending `null`, and explicitly sending `8`. Reusing the same key for equivalent input returns the
original `202` body and `Location`, including the original IDs and accepted
states. Replays perform no database writes. Reusing the key with different
normalized input returns `409 IDEMPOTENCY_CONFLICT`.

Reservations persist indefinitely in PostgreSQL, including across backend
restarts; there is no in-memory cache or expiration window. A transaction
reserves the key using PostgreSQL `ON CONFLICT DO NOTHING`, inserting nonnull
environment and operation IDs immediately. Its foreign keys are
`DEFERRABLE INITIALLY DEFERRED`, so references are checked at commit after
the environment, operation, and audit event are written; no reservation
completion update is needed. These writes commit or roll back together. Concurrent
requests using one key cannot create duplicate environments or operations.
Different callers or team scopes can independently use the same key. Legacy
global reservations remain replay-compatible for `OPERATOR` only; developers
cannot replay them to discover or claim legacy environments. Replays still
require current authorization; membership removal is not bypassed by an old key.

**V7 deployment compatibility:** V7 changes the idempotency primary key from
`key` to `(scope, key)`. Stop all old backend processes before starting the
upgraded backend and its Flyway migrations. This is not a mixed-version rolling
migration: the old backend's `ON CONFLICT (key)` no longer works after V7.
Existing environment, operation, idempotency and audit rows remain intact.

## Delete an environment

`DELETE /api/v1/environments/{id}` accepts no payload and needs no idempotency
key. It returns `202 Accepted`, `Location: /api/v1/operations/{operationId}`, and:

```json
{
  "environmentId": "99f42e18-bab9-40ad-83d1-85bc3f6378c0",
  "operationId": "59a65a78-33a0-4bb0-9648-2722377f3d99",
  "environmentStatus": "DELETING",
  "operationStatus": "PENDING"
}
```

Only `READY`, `FAILED`, or `DELETE_FAILED` records are eligible for a new delete.
Creation must finish first. An environment row lock serializes concurrent
submissions; repeating a pending/running delete returns that same operation
(`operationStatus` may be `RUNNING`). Environment, operation, audit, and acceptance
history are persisted together. No competing delete is created.

Malformed IDs return `400`; unknown IDs return `404`. Already deleted records
and conflicting lifecycle states are rejected, rather than starting new work.
After a terminal `DELETE_FAILED`, an explicit new DELETE creates a new durable
operation, preserving the failed attempt's history.

### Guarded expiration deletion

Cleanup uses the same route with optional `?expiredBefore=<ISO-8601 instant>`,
for example `?expiredBefore=2026-10-16T13%3A00%3A00Z`. No body or idempotency
key is added. The cutoff must include a timezone and must not be later than the
backend clock (`400 INVALID_REQUEST`). Under the same environment row lock, a
**new** guarded deletion requires `expiresAt <= expiredBefore` and `READY` or
`FAILED`; otherwise it returns `409 ENVIRONMENT_STATE_CONFLICT`. An active
`DELETING` operation is reused. `DELETE_FAILED` never starts a new operation via
this guarded route, even if another deletion fails between the client's recheck
and submission. The unguarded portal/manual retry contract is unchanged.

The CLI never retries DELETE; ambiguous responses trigger bounded authenticated detail reads
to recover the operation ID or report a failure/unknown outcome. Use operation
detail/history to verify completion; a 202 only requests deletion. The CLI
requires this backend revision so the guard is enforced.

The worker verifies the canonical namespace and platform/environment ownership
in both labels and annotations before removal. New namespaces also carry an
original create-operation marker, which must match in both places if either is
present. Older owned namespaces with neither marker remain deletable using the
original ownership checks; they are never relabeled or adopted. The persisted
create operation must exist. The worker never deletes by prefix alone. Kubernetes deletion
uses namespace identity/version preconditions to protect against replacement or
ownership changes between inspection and deletion. A namespace already absent
is success; a namespace still terminating is not. Finalizers are not stripped.
The environment remains `DELETING` until absence is observed, then becomes
`DELETED`; terminal failures become `DELETE_FAILED` with sanitized operation errors.
Leases, bounded retries, and restart recovery apply to delete work too. This slice
reuses `platform.worker.readiness-timeout` as the overall deletion deadline and
`readiness-poll-interval` for namespace-absence polling.

Ownership conflicts discovered in Kubernetes are asynchronous operation failures,
not a claim that request acceptance validated live cluster ownership. These checks
protect platform resources; they are separate from the user
[authorization](#authentication-and-authorization) that DELETE requires.
Any `OPERATOR` may delete any environment. A `DEVELOPER` may delete only an
environment owned by one of their current teams; unowned legacy environments
are operator-managed. Deletion removes the namespace and everything
inside it, not the PostgreSQL environment or operation history.

## Read environments

`GET /api/v1/environments?page=0&size=20` returns `200 OK`:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

`page` is a zero-based integer, defaults to `0`, and is bounded to
`0`–`1000000`; `size` defaults to `20` and is bounded to `1`–`100`.
Items are ordered by `createdAt DESC, id DESC`. Items and totals are read
from a consistent database snapshot **per request**, not across multiple pages.
Concurrent inserts can shift offset pages; cleanup deduplicates but cannot promise
run-wide snapshot completeness. A valid page beyond the last page returns an empty
`items` array with the actual totals. No status filter is implemented.

`GET /api/v1/environments/{id}` returns `200 OK` and an environment DTO:

```json
{
  "id": "99f42e18-bab9-40ad-83d1-85bc3f6378c0",
  "name": "demo",
  "namespace": "env-99f42e18bab940ad83d185bc3f6378c0",
  "templateId": "sample-service",
  "teamId": "payments",
  "createdBy": "alice",
  "status": "REQUESTED",
  "expiresAt": "2026-10-16T13:00:00Z",
  "createdAt": "2026-10-16T05:00:00Z",
  "updatedAt": "2026-10-16T05:00:00Z",
  "latestOperation": {
    "id": "27f56e9b-382a-4373-a26c-c4dab824d127",
    "type": "CREATE_ENVIRONMENT",
    "status": "PENDING",
    "progressStep": "ACCEPTED",
    "failureCode": null,
    "failureMessage": null
  }
}
```

List `items` use this same DTO. The namespace is `env-` followed by the
environment UUID with hyphens removed. Worker-enabled provisioning creates this
namespace only if it is absent or already owned by this environment. Environment
status progresses through `REQUESTED`, `PROVISIONING`, and `READY` or `FAILED`.
Deletion adds `DELETING`, `DELETED`, and `DELETE_FAILED`.
`latestOperation` is an additive read-only summary of the newest operation by
`createdAt DESC, id DESC`, or `null` when none exists. `PENDING` and `RUNNING`
indicate an active operation. This summary does not change creation responses.
Its nullable `failureCode` and `failureMessage` match operation detail/history,
including failed provisioning and deletion. The portal displays these summaries
with code-specific remediation hints; no raw upstream diagnostics are returned.

## Read environment operation history

`GET /api/v1/environments/{id}/operations?page=0&size=20` returns `200 OK`
with the same pagination envelope as the environment list. Each `items` entry
has the full operation-detail shape below, including sanitized failure fields
and progress events. Operations are ordered by `createdAt DESC, id DESC`;
events within each operation retain their chronological backend order.

Page/size defaults and bounds match the environment list. Items, events, and
totals are read in one consistent database snapshot. An existing environment
with no operations, or a page beyond the last, returns empty `items` with actual
totals. Invalid identifiers/parameters return `400`; a missing environment
returns `404`. History is scoped to the requested environment and includes both
`CREATE_ENVIRONMENT` and `DELETE_ENVIRONMENT` operations.

## Read an operation

`GET /api/v1/operations/{id}` returns `200 OK`:

```json
{
  "id": "27f56e9b-382a-4373-a26c-c4dab824d127",
  "environmentId": "99f42e18-bab9-40ad-83d1-85bc3f6378c0",
  "type": "CREATE_ENVIRONMENT",
  "status": "PENDING",
  "attemptCount": 0,
  "nextAttemptAt": "2026-10-16T05:00:00Z",
  "leaseExpiresAt": null,
  "startedAt": null,
  "completedAt": null,
  "createdAt": "2026-10-16T05:00:00Z",
  "updatedAt": "2026-10-16T05:00:00Z",
  "failureCode": null,
  "failureMessage": null,
  "progressStep": "ACCEPTED",
  "correlationId": "94257154-484e-4c67-aed2-8958854c7e6a",
  "events": []
}
```

Timestamps are ISO-8601 instants. Initially `nextAttemptAt = createdAt`,
`attemptCount = 0`, and the three nullable lifecycle timestamps are `null`.
Operations transition from `PENDING` to `RUNNING`, then `SUCCEEDED` or `FAILED`.
Transient failures return to `PENDING` with a future `nextAttemptAt`; expired
`RUNNING` leases can be reclaimed. The create acceptance response (including idempotent
replays) always retains the original accepted states; poll this endpoint for
the current state. `READY` is never inferred from request acceptance or merely
creating Kubernetes objects.

Operation detail also includes progress, failure, request correlation, and
durable events (see below). Terminal failure messages are fixed sanitized
summaries, not Kubernetes response bodies, kubeconfig contents, or stack traces.
`completedAt` is populated only for terminal outcomes. All timestamps are UTC.

| Field | Meaning |
|---|---|
| `attemptCount` | Execution attempts, including reclaimed abandoned attempts; create readiness-only polls do not increment it. |
| `progressStep` | Current worker step, including `ACCEPTED`, `PROVISIONING`, `DELETING`, `WAITING`, `RETRY`, `READY`, `DELETED`, or `FAILED`. |
| `failureCode`, `failureMessage` | Nullable sanitized last failure summary; retries can have a summary before reaching a terminal state. |
| `correlationId` | Server-generated ID of the original acceptance request; older migrated records may have `null`. A replay has its own response `X-Request-Id` without replacing this original correlation. |
| `events` | Ordered, durable worker events with `type`, `code`, `message`, `correlationId`, and UTC `createdAt`. No raw Kubernetes event/log stream is returned. |

Event types include `CLAIMED`, `PROGRESS`, `PROVISIONED`, `WAITING`, `RETRY`,
`SUCCEEDED`, and `FAILED`. Each state change and its event commit atomically.
Every event carries the operation's original request `correlationId`; the
operation and environment IDs come from the enclosing operation.

The operation timeline uses these codes for key lifecycle milestones:

| Type | Code | Recorded when |
|---|---|---|
| `CLAIMED` | `CLAIMED` / `LEASE_RECOVERED` | A worker acquires the operation, or reclaims it after a previous lease expired. |
| `PROGRESS` | `PROVISIONING_STARTED` | The worker begins creating environment resources. |
| `PROGRESS` | `NAMESPACE_CREATED` | Kubernetes accepted the environment namespace. |
| `PROGRESS` | `WORKLOAD_CREATED` | Kubernetes accepted the `sample-service` Deployment. |
| `PROVISIONED` | `PROVISIONED` | All resources exist; readiness polling begins. |
| `SUCCEEDED` | `ENVIRONMENT_READY` | The workload is ready (create). |
| `PROGRESS` | `DELETION_STARTED` | The worker begins deleting the namespace. |
| `PROGRESS` | `NAMESPACE_DELETE_REQUESTED` | Kubernetes accepted the guarded namespace DELETE. |
| `SUCCEEDED` | `ENVIRONMENT_DELETED` | The namespace is confirmed absent (delete). |
| `WAITING` / `RETRY` / `FAILED` | see codes below | Readiness/absence waits, transient retries, and terminal failures. |

`PROGRESS` milestones are lease-fenced, recorded at most once per operation, and
best-effort: a failure to record one never fails the Kubernetes work. Recovered
operations that find resources already present do not repeat Kubernetes
milestones. Successes recorded before this release keep the legacy `SUCCEEDED` code.
The worker also logs each recorded event as one structured line
(`event`, `code`, `operationId`, `environmentId`, `operationType`), never messages or credentials. Readiness waits
are separate from transient failure retries; they do not exhaust the error budget.
`READINESS_TIMEOUT` and `ATTEMPTS_EXHAUSTED` report bounded execution/recovery
limits. No lease tokens or Kubernetes credentials are exposed.

Worker failure codes include:

| Code | Handling |
|---|---|
| `KUBERNETES_UNAVAILABLE` | Transient transport, conflict, throttling, or server error; bounded retry. |
| `KUBERNETES_AUTHENTICATION_FAILED` | Kubernetes HTTP 401; check the worker service account token or kubeconfig credentials and expiry. Non-retryable. |
| `KUBERNETES_FORBIDDEN` | Kubernetes HTTP 403; check the worker service account's RBAC roles and bindings. Non-retryable. |
| `KUBERNETES_REQUEST_REJECTED` | Other non-retryable Kubernetes API/resource request failure; check admission policies, quotas, and the resource specification. |
| `KUBERNETES_VALIDATION_FAILED` | Invalid persisted provisioning input; fail without provisioning. |
| `KUBERNETES_OWNERSHIP_CONFLICT` | Resource is not an active, owned platform resource; never adopt it. |
| `READINESS_TIMEOUT` | Initial provisioning did not reach readiness before its deadline; check Pods, image pulls, readiness probes, scheduling, and namespace events. |
| `DELETION_TIMEOUT` | Namespace absence was not confirmed before the deletion deadline; check remaining resources and finalizers before retrying deletion. |
| `ATTEMPTS_EXHAUSTED` | Recovered work exhausted its provisioning attempts. |
| `INTERNAL_ERROR` | Unexpected failure with a fixed sanitized summary. |

These are asynchronous worker failures, separate from HTTP Basic authentication
or authorization errors on API mutation requests. Fix the reported cause before
taking further action. After a failed create, deletion can clean up partial resources;
after `DELETE_FAILED`, the portal/manual DELETE can request a new deletion operation.
There is no create-operation retry endpoint. Older persisted failures retain their
original codes/messages; new classifications apply to subsequent worker failures.

## Read operation metrics

`GET /api/v1/operations/metrics` returns `200 OK` with a point-in-time summary
computed from durable PostgreSQL state. Values therefore survive backend restarts
and are the same on every replica, whether or not that replica runs the worker:

```json
{
  "generatedAt": "2026-10-16T05:10:00Z",
  "workerEnabled": true,
  "operations": [
    {
      "type": "CREATE_ENVIRONMENT",
      "pending": 1, "running": 0, "succeeded": 12, "failed": 1,
      "averageQueueSeconds": 0.42,
      "succeededDuration": {"count": 12, "averageSeconds": 21.3, "p95Seconds": 34.8, "maxSeconds": 36.1}
    },
    {
      "type": "DELETE_ENVIRONMENT",
      "pending": 0, "running": 0, "succeeded": 0, "failed": 0,
      "averageQueueSeconds": null,
      "succeededDuration": {"count": 0, "averageSeconds": null, "p95Seconds": null, "maxSeconds": null}
    }
  ],
  "worker": {
    "dueOperations": 1,
    "expiredLeases": 0,
    "oldestDueAt": "2026-10-16T05:09:58Z",
    "lastEventAt": "2026-10-16T05:09:41Z"
  },
  "failureCodes": [{"code": "KUBERNETES_FORBIDDEN", "count": 1}]
}
```

| Field | Meaning |
|---|---|
| `workerEnabled` | Whether *this* backend replica runs the worker (`platform.worker.enabled`). |
| `operations[]` | One entry per operation type, always both types. `pending` includes readiness/absence waits and scheduled retries. |
| `averageQueueSeconds` | Mean time from acceptance to first claim for started operations; `null` if none started. |
| `succeededDuration` | End-to-end acceptance-to-completion seconds for `SUCCEEDED` operations; statistics are `null` when `count` is 0. |
| `worker.dueOperations` | `PENDING` operations claimable now. A steadily growing value means no healthy worker is polling. |
| `worker.expiredLeases` | `RUNNING` operations whose lease expired, which usually means a worker stopped or crashed mid-operation. They are reclaimed automatically. |
| `worker.oldestDueAt` | Earliest due time among claimable work; an old value indicates a stalled worker. `null` when nothing is due. |
| `worker.lastEventAt` | Most recent operation event; a coarse worker activity signal. |
| `failureCodes` | Terminal `FAILED` operation counts by sanitized failure code (top 20). |

This route requires HTTP Basic authentication with `OPERATOR` and exposes no
environment names, lease tokens, or credentials.

Both detail routes require UUID identifiers. A malformed identifier returns
`400`; a valid UUID with no record returns `404`.

## Errors and request tracing

Errors have the following shape; `fieldErrors` is an empty array when no
field-specific details apply:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "requestId": "94257154-484e-4c67-aed2-8958854c7e6a",
  "fieldErrors": [
    {"field": "name", "message": "must be a valid environment name"}
  ]
}
```

Messages above are illustrative; clients should rely on stable codes:

| HTTP status | Code | Meaning |
|---|---|---|
| `400` | `INVALID_REQUEST` | Malformed JSON, unknown fields, invalid input types, or malformed identifiers/parameters. |
| `400` | `VALIDATION_ERROR` | Input fails required-field, format, or range constraints. |
| `409` | `IDEMPOTENCY_CONFLICT` | Key already belongs to different normalized input. |
| `409` | `ENVIRONMENT_STATE_CONFLICT` | Deletion conflicts with creation, another active operation, or an already deleted record. |
| `409` | `INVALID_ENVIRONMENT_NAMESPACE` | Persisted namespace is not canonical; no deletion is accepted. |
| `404` | `NOT_FOUND` | Requested record does not exist. |
| `500` | `INTERNAL_ERROR` | Unexpected failure; internal details are not returned. |

Every response, including errors and replays, carries a server-generated UUID
in `X-Request-Id`. Error bodies use the same identifier. Caller-supplied IDs
are not trusted. Completion logs include request ID through MDC, HTTP method,
status, and duration, without logging query strings, request bodies, or
idempotency keys.

No CORS policy is added. Audit writes record the authenticated username, never
a caller-provided actor header (see below).

## Authentication and authorization

The API uses stateless HTTP Basic authentication against accounts configured at
backend startup ([configuration](../backend/README.md#authentication-and-authorization)).
No session or cookie is created, and CSRF protection is not needed because
credentials are never sent ambiently by the browser.

| Request | Requirement |
|---|---|
| Health probes (`/actuator/health/**`) | Anonymous; deployment exposure remains unchanged |
| `GET /api/v1/me` | Any authenticated account; returns `{"username":"alice","roles":["DEVELOPER"],"teams":["payments"]}` |
| Environment list/detail discovery | `OPERATOR`: all; `DEVELOPER`: only owned-team environments (including list rows and totals); `CLEANUP`: all, including legacy unowned rows |
| Environment operation history and direct operation reads | `OPERATOR`: all; `DEVELOPER`: only environments owned by their teams; `CLEANUP`: denied |
| `GET /api/v1/operations/metrics` | `OPERATOR` only |
| `POST /api/v1/environments` | `OPERATOR`: optional explicit team owner, otherwise unowned; `DEVELOPER`: explicit member `teamId`; `CLEANUP`: denied |
| `DELETE /api/v1/environments/{id}` | `OPERATOR`: all; `DEVELOPER`: owned teams only; `CLEANUP`: denied |
| `DELETE /api/v1/environments/{id}?expiredBefore=…` | `OPERATOR`: all; `DEVELOPER`: owned teams only; `CLEANUP`: all, subject to the expiry/state guard |
| Any other method or path | Role `OPERATOR` (fail closed) |

All API reads (including list, detail, history and operation reads) require Basic
credentials; there is no anonymous API read path. `HEAD` must not bypass the same
authorization. `CLEANUP` is an exclusive automation role and cannot be combined
with `OPERATOR` or `DEVELOPER`; it is not a portal or diagnostic-reader role.
Teams authorize the persisted owner, not `createdBy` or a caller-supplied actor.
Environment DTOs add `teamId` and `createdBy`; both may be `null` for legacy rows.
There is no ownership transfer or automatic legacy adoption.

Authentication/authorization rejection consumes no idempotency reservation and
performs no mutation:

| Status | Code | Meaning |
|---|---|---|
| `401` | `UNAUTHENTICATED` | Missing, malformed, or wrong credentials. |
| `403` | `FORBIDDEN` | Valid credentials without the required role. |

Both use the standard error body with `requestId` and the `X-Request-Id` header.
`401` deliberately omits `WWW-Authenticate`, so browsers never show a native
prompt or cache credentials. A rejected creation consumed nothing: retrying the
same `Idempotency-Key` after authenticating is safe.

`audit_events.actor` is the authenticated username for creation and deletion
requests (including cleanup-CLI deletions, recorded as the `CLEANUP` account).
Earlier rows keep `local-development`, which can no longer be configured as a
username. Request completion logs do not record the actor.

### Trusted team provisioning

Passwords and roles remain in the existing account configuration, now also
accepting `DEVELOPER`. Do not add team fields to account configuration. Membership
is stored in PostgreSQL `platform_teams(id varchar(63))` and
`account_team_memberships(username varchar(64), team_id REFERENCES platform_teams(id))`.
Team IDs are constrained to lower-case slugs of 1–63 characters.
Provision it only through a trusted SQL administrator, for example:

```sql
BEGIN;
INSERT INTO platform_teams (id) VALUES ('payments') ON CONFLICT DO NOTHING;
INSERT INTO account_team_memberships (username, team_id)
VALUES ('alice', 'payments') ON CONFLICT DO NOTHING;
COMMIT;
```

`alice` must separately be a configured account with role `DEVELOPER`. `/me`
reports current memberships; revoking membership removes access to that team's
environments, including history, operations and idempotent replay. No team or
membership management API is added.

Not provided: SSO/OIDC, token expiry, rate limiting or lockout, runtime account
management, GitOps, ownership transfer, or production multi-tenant hardening.
Plain Basic is acceptable only on loopback; use the separate TLS deployment
path for container access. Kubernetes RBAC and ingress are unchanged.
