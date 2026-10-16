# API contract

The persistent backend slice implements the routes below under `/api/v1`.
Requests are accepted separately from infrastructure readiness. An opt-in worker
provisions the approved sample in local kind and exposes progress in operation
detail. There is no deletion, expiration cleanup, or environment events endpoint. The broader
[blueprint](../README.md#9-proposed-rest-api) describes future behavior.

## Create an environment

`POST /api/v1/environments`, with `Content-Type: application/json` and a
required `Idempotency-Key` header:

```json
{
  "name": "demo",
  "templateId": "sample-service",
  "ttlHours": 8
}
```

| Input | Validation |
|---|---|
| `name` | Required string; trim surrounding whitespace before validation and persistence. The resulting name is 1–63 ASCII letters, digits, spaces, underscores, or hyphens, starting with a letter or digit: `[A-Za-z0-9][A-Za-z0-9 _-]{0,62}`. |
| `templateId` | Required string, exactly `sample-service`. |
| `ttlHours` | Optional JSON integer, defaults to `8` when omitted or `null`; inclusive range `1`–`24`. Fractional numbers are rejected. |
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
metadata only; passing that time does not trigger cleanup in this slice.
`sample-service` is the only approved template, mapped to a fixed sample
Deployment/image by the worker. No arbitrary manifests or images are accepted.

### Durable idempotency and atomicity

The normalized name, exact template ID, and effective TTL identify the request.
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
from a consistent database snapshot. A valid page beyond the last page returns an empty
`items` array with the actual totals. No status filter is implemented.

`GET /api/v1/environments/{id}` returns `200 OK` and an environment DTO:

```json
{
  "id": "99f42e18-bab9-40ad-83d1-85bc3f6378c0",
  "name": "demo",
  "namespace": "env-99f42e18bab940ad83d185bc3f6378c0",
  "templateId": "sample-service",
  "status": "REQUESTED",
  "expiresAt": "2026-10-16T13:00:00Z",
  "createdAt": "2026-10-16T05:00:00Z",
  "updatedAt": "2026-10-16T05:00:00Z",
  "latestOperation": {
    "id": "27f56e9b-382a-4373-a26c-c4dab824d127",
    "type": "CREATE_ENVIRONMENT",
    "status": "PENDING",
    "progressStep": "ACCEPTED"
  }
}
```

List `items` use this same DTO. The namespace is `env-` followed by the
environment UUID with hyphens removed. Worker-enabled provisioning creates this
namespace only if it is absent or already owned by this environment. Environment
status progresses through `REQUESTED`, `PROVISIONING`, and `READY` or `FAILED`.
`latestOperation` is an additive read-only summary of the newest operation by
`createdAt DESC, id DESC`, or `null` when none exists. `PENDING` and `RUNNING`
indicate an active operation. This summary does not change creation responses.

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
returns `404`. History is scoped to the requested environment. No new write
or provisioning behavior is introduced.

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
`RUNNING` leases can be reclaimed. The acceptance response (including idempotent
replays) always retains the original accepted states; poll this endpoint for
the current state. `READY` is never inferred from request acceptance or merely
creating Kubernetes objects.

Operation detail also includes progress, failure, request correlation, and
durable events (see below). Terminal failure messages are fixed sanitized
summaries, not Kubernetes response bodies, kubeconfig contents, or stack traces.
`completedAt` is populated only for terminal outcomes. All timestamps are UTC.

| Field | Meaning |
|---|---|
| `attemptCount` | Provisioning attempts, including reclaimed abandoned attempts; readiness-only polls do not increment it. |
| `progressStep` | Current worker step, including `ACCEPTED`, `PROVISIONING`, `WAITING`, `RETRY`, `READY`, or `FAILED`. |
| `failureCode`, `failureMessage` | Nullable sanitized last failure summary; retries can have a summary before reaching a terminal state. |
| `correlationId` | Server-generated ID of the original acceptance request; older migrated records may have `null`. A replay has its own response `X-Request-Id` without replacing this original correlation. |
| `events` | Ordered, durable worker events with `type`, `code`, `message`, `correlationId`, and UTC `createdAt`. No raw Kubernetes event/log stream is returned. |

Event types include `CLAIMED`, `PROVISIONED`, `WAITING`, `RETRY`, `SUCCEEDED`,
and `FAILED`. Each state change and its event commit atomically. Readiness waits
are separate from transient failure retries; they do not exhaust the error budget.
`READINESS_TIMEOUT` and `ATTEMPTS_EXHAUSTED` report bounded execution/recovery
limits. No lease tokens or Kubernetes credentials are exposed.

Worker failure codes include:

| Code | Handling |
|---|---|
| `KUBERNETES_UNAVAILABLE` | Transient transport, conflict, throttling, or server error; bounded retry. |
| `KUBERNETES_REQUEST_REJECTED` | Non-retryable Kubernetes request/permission failure. |
| `KUBERNETES_VALIDATION_FAILED` | Invalid persisted provisioning input; fail without provisioning. |
| `KUBERNETES_OWNERSHIP_CONFLICT` | Resource is not an active, owned platform resource; never adopt it. |
| `READINESS_TIMEOUT` | Initial provisioning did not reach readiness before its deadline. |
| `ATTEMPTS_EXHAUSTED` | Recovered work exhausted its provisioning attempts. |
| `INTERNAL_ERROR` | Unexpected failure with a fixed sanitized summary. |

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
| `404` | `NOT_FOUND` | Requested record does not exist. |
| `500` | `INTERNAL_ERROR` | Unexpected failure; internal details are not returned. |

Every response, including errors and replays, carries a server-generated UUID
in `X-Request-Id`. Error bodies use the same identifier. Caller-supplied IDs
are not trusted. Completion logs include request ID through MDC, HTTP method,
status, and duration, without logging query strings, request bodies, or
idempotency keys.

No authentication or CORS policy is added. Audit writes use a fixed
local-development actor, never a caller-provided actor header. This is not
authenticated identity: use the API only in a trusted local environment.
