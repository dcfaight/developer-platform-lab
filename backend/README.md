# Backend

Java 17 + Spring Boot 4.1.1, built with the existing Maven Wrapper. Open this
directory as a Maven project in IntelliJ IDEA. On Windows use `mvnw.cmd`.
The server binds to http://127.0.0.1:8080.

The persistent slice stores environments, operations, idempotency reservations,
and audit events in PostgreSQL, with Flyway migrations applied on startup.
See the [API contract](../docs/api.md) for schemas and validation.
Only `REQUESTED` environments and `PENDING` create operations exist: there is
no worker, provisioning, deletion, events endpoint, or expiration cleanup.
`sample-service` is an approved identifier allowlist only, not a deployed
template implementation. Omitted or `null` TTL defaults to eight hours.
The frontend remains a static scaffold and is not connected to this API.

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

No Kubernetes cluster is required. There is no authentication or added CORS
configuration; keep this API in a trusted local environment. Audit events use
a fixed local-development actor, never caller actor headers.

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
