# Local demo: request → provision → observe → delete

**Story:** the portal submits a request through the API, a leased worker provisions
the approved sample in kind, and the portal shows the persisted operation progress.
Confirmed deletion then removes only the owned namespace and retains its history.
Acceptance is not readiness; `READY` means initial Deployment readiness was observed,
not that the application is continuously monitored.

This remains the full lifecycle demo. The separate
[hardened container path](../infra/README.md#hardened-single-host-deployment-api-only)
serves the portal/API with TLS but deliberately disables the host-only kind worker;
do not use that API-only path to claim successful provisioning or deletion.

## Prerequisites and startup

Use a trusted local machine with Bash, Python 3.12, Docker + Compose, kind, kubectl, Java 17,
Node.js 24 + npm 11, curl, and jq. Allow image pulls and Maven/npm downloads.
Reserve ports 5432, 8080, and 5173 (8081 is optional for the sample).
Commands start from the repository root unless stated otherwise.
Mutations require HTTP Basic sign-in for locally configured accounts; reads are
anonymous. Credentials travel unencrypted, so keep every service on loopback and
do not expose them publicly.

1. Follow [backend local setup](../backend/README.md#run-locally) to create/edit
   your ignored `.env`, start PostgreSQL, and export `DB_*`. Do not start the
   backend yet. On repeat runs, retain your existing `.env` and database password.
2. Create the dedicated cluster (skip creation if it already exists):

   ```sh
   kind create cluster --name developer-platform-lab --config infra/kind/cluster.yaml
   kubectl --context kind-developer-platform-lab get nodes
   kubectl --context kind-developer-platform-lab wait --for=condition=Ready nodes --all --timeout=180s
   kubectl --context kind-developer-platform-lab -n kube-system \
     rollout status deployment/coredns --timeout=180s
   ```

3. In the terminal with the `DB_*` exports, configure an `OPERATOR` account (and,
   for section 5, a `CLEANUP` account) as described in
   [backend authentication](../backend/README.md#authentication-and-authorization):
   generate a password hash with the documented Python one-liner and export
   `PLATFORM_SECURITY_USERS_0_USERNAME`, `..._PASSWORDHASH`, and `..._ROLES`.
   Remember the plaintext passwords yourself; only hashes are configured. Then
   start the worker-enabled backend:

   ```sh
   export WORKER_ENABLED=true
   export PLATFORM_KUBERNETES_MODE=local-kind
   export PLATFORM_KUBERNETES_KUBECONFIG="$HOME/.kube/config"
   cd backend
   ./mvnw spring-boot:run
   ```

   Wait for Spring Boot to start on `127.0.0.1:8080`. The worker is disabled by
   default; the dedicated kubeconfig context and loopback API server are required.
4. In another terminal, from the repository root:

   ```sh
   cd frontend
   npm ci
   npm run dev -- --host 127.0.0.1 --strictPort
   ```

   Open http://127.0.0.1:5173. Vite proxies `/api` to the backend.
   The sample is the worker's fixed `nginxinc/nginx-unprivileged:1.28-alpine`
   Deployment; no custom sample build or image load is needed.
   If node/DNS readiness fails, fix cluster networking/resources before continuing;
   do not disable workload probes. Registry access is needed for a cold sample pull.

## 1. Create from the portal

1. Open http://127.0.0.1:5173 and sign in with the `OPERATOR` account in the
   panel at the top. The portal checks the credentials with `GET /api/v1/me` and
   holds them only in page memory; reloading signs you out. Browsing works without
   signing in, but the create and delete buttons stay disabled. Choose
   **Create environment**.
2. Enter a name using ASCII letters, digits, spaces, underscores, or hyphens,
   starting with a letter or digit. Choose a whole-number lifetime from 1 to 24
   hours; it defaults to 8. The only template is the approved `sample-service`.
3. Submit once. The portal posts through Vite's same-origin `/api` proxy and,
   after `202 Accepted`, opens the environment detail and operation history using
   the returned environment ID.

The initial state is `REQUESTED` / `PENDING`; acceptance is not readiness.
With the worker disabled it remains pending. Enable the optional worker and kind
cluster to watch the existing detail view refresh with persisted progress. TTL is
metadata only and does not trigger cleanup.

If the result is ambiguous, use **Retry the same request**. The portal retains
the exact payload and idempotency key in this tab's session storage. An explicit
new-request confirmation abandons that retry choice but cannot cancel server work
or guarantee that the earlier environment was not created. Closing the tab or
browser storage being unavailable can remove local recovery state.

## 2. Exercise API idempotency replay

The following optional API walkthrough creates a separate request, demonstrating
the durable replay contract independently of the portal submission.

Run the following in a **new Bash terminal**; keep it open for the observe step.
`set -euo pipefail` stops this shell on a failed command rather than continuing
with missing IDs. curl requests have bounded timeouts and fail on HTTP errors.

curl prompts for the operator password on each mutation (`-u` with only a
username), so it never appears in shell history or process arguments.

```bash
set -euo pipefail
API=http://127.0.0.1:8080/api/v1
OPERATOR_USERNAME=alice   # your configured OPERATOR account
DEMO_KEY="demo-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM"
BODY='{"name":"interview-demo","templateId":"sample-service","ttlHours":1}'

request_demo() {
  curl --fail-with-body --silent --show-error --connect-timeout 3 --max-time 15 \
    -u "$OPERATOR_USERNAME" \
    "$API/environments" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $DEMO_KEY" -d "$BODY"
}

ACCEPTED=$(request_demo)
printf '%s\n' "$ACCEPTED" | jq .
ENVIRONMENT_ID=$(jq -er '.environmentId' <<< "$ACCEPTED")
OPERATION_ID=$(jq -er '.operationId' <<< "$ACCEPTED")
# The API namespace is env- followed by the environment UUID without hyphens.
NAMESPACE="env-${ENVIRONMENT_ID//-/}"

# The same key and normalized input return the original accepted body, even
# after provisioning. This is not a current-status read.
REPLAY=$(request_demo)
diff <(jq -S . <<< "$ACCEPTED") <(jq -S . <<< "$REPLAY")
printf 'Replay matched. Portal: http://127.0.0.1:5173/#environment/%s\n' "$ENVIRONMENT_ID"
```

**Expected:** the first response is `202` with `REQUESTED` / `PENDING` and two
IDs; replay has the same IDs/body and `diff` prints nothing. Each new run generates
a new key and creates one more environment; reuse the saved key/body to replay
instead. A different body with the same key returns `409`, not a new environment.
Without credentials the same POST returns `401` `UNAUTHENTICATED` and writes
nothing. The audit event for this request records your username as the actor.

## 3. Observe with a bounded wait

For the API-created request, in the **same Bash terminal**, poll the operation for up to seven minutes.
Cold image pulls can take several minutes. The wait is diagnostic: timing out
does not cancel work or delete anything.

```bash
deadline=$((SECONDS + 420))
status=PENDING
while (( SECONDS < deadline )); do
  OPERATION=$(curl --fail-with-body --silent --show-error \
    --connect-timeout 3 --max-time 10 "$API/operations/$OPERATION_ID")
  jq '{status, progressStep, attemptCount, failureCode, failureMessage}' <<< "$OPERATION"
  status=$(jq -er '.status' <<< "$OPERATION")
  case "$status" in
    SUCCEEDED) break ;;
    FAILED) jq '.events' <<< "$OPERATION"; exit 1 ;;
  esac
  sleep 3
done
if [[ "$status" != SUCCEEDED ]]; then
  printf 'Timed out; inspect operation %s and the runbook below.\n' "$OPERATION_ID" >&2
  exit 1
fi

ENVIRONMENT=$(curl --fail-with-body --silent --show-error \
  --connect-timeout 3 --max-time 10 "$API/environments/$ENVIRONMENT_ID")
jq '{name, status, namespace, expiresAt}' <<< "$ENVIRONMENT"
NAMESPACE=$(jq -er '.namespace' <<< "$ENVIRONMENT")
kubectl --context kind-developer-platform-lab -n "$NAMESPACE" get deployments,pods
```

**Expected:** the operation reaches `SUCCEEDED`, environment status is `READY`,
and the sample Deployment has a ready replica. In the portal, show the page-local
summary, select the environment, then narrate attempt count and oldest-first
progress events. Operations themselves are newest-first. The portal refreshes
every ten seconds; use **Refresh** for an immediate read. Times are browser-local.
TTL is metadata only, not a cleanup promise.

Optional: show the actual sample from the same terminal, then stop the
port-forward with Ctrl+C:

```sh
kubectl --context kind-developer-platform-lab -n "$NAMESPACE" \
  port-forward --address 127.0.0.1 deployment/sample-service 8081:8080
# Open http://127.0.0.1:8081 in a browser.
```

## 4. Delete safely and verify absence

Stop any sample port-forward first. Signed in as an `OPERATOR`, open the environment detail,
choose **Delete environment**, inspect the target, and confirm. The action is
unavailable during creation or an active delete, and for deleted records.
Acceptance means durable `DELETING`, not that Kubernetes removal is complete.
Watch the newest `DELETE_ENVIRONMENT` operation and its request correlation.
The terminal outcome is `DELETED` / `SUCCEEDED` or `DELETE_FAILED` / `FAILED`.

For the API-created environment, use the **same Bash terminal**:

```bash
DELETE_ACCEPTED=$(curl --fail-with-body --silent --show-error \
  --connect-timeout 3 --max-time 15 -u "$OPERATOR_USERNAME" -X DELETE \
  "$API/environments/$ENVIRONMENT_ID")
jq . <<< "$DELETE_ACCEPTED"
DELETE_OPERATION_ID=$(jq -er '.operationId' <<< "$DELETE_ACCEPTED")
deadline=$((SECONDS + 420))
status=PENDING
while (( SECONDS < deadline )); do
  OPERATION=$(curl --fail-with-body --silent --show-error \
    --connect-timeout 3 --max-time 10 "$API/operations/$DELETE_OPERATION_ID")
  jq '{status, progressStep, attemptCount, failureCode, failureMessage}' <<< "$OPERATION"
  status=$(jq -er '.status' <<< "$OPERATION")
  case "$status" in
    SUCCEEDED) break ;;
    FAILED) jq '.events' <<< "$OPERATION"; exit 1 ;;
  esac
  sleep 3
done
[[ "$status" == SUCCEEDED ]] || { echo 'Deletion still incomplete; inspect history.' >&2; exit 1; }
curl --fail-with-body --silent --show-error --connect-timeout 3 --max-time 10 \
  "$API/environments/$ENVIRONMENT_ID" | jq '{status, latestOperation}'
# Success prints nothing; connection/permission errors must not be mistaken for absence.
REMAINING=$(kubectl --context kind-developer-platform-lab get namespace \
  "$NAMESPACE" --ignore-not-found -o name)
[[ -z "$REMAINING" ]] || { echo 'Namespace remains; inspect it.' >&2; exit 1; }
```

Expected: the record remains readable as `DELETED`, namespace is absent, and
history includes creation plus deletion. A repeat DELETE while active returns
the same operation; after completion it is rejected as already deleted. For a
deterministic replay demonstration, disable the worker **after creation finishes**,
submit DELETE twice, compare operation IDs, then restart the worker with the same
database. Pending work survives restart. Do not delete a still-provisioning record.

If deletion fails, inspect the sanitized error and correlation ID. Fix the cause
before explicitly retrying from the detail view or API; the previous failure stays
in history. Never relabel an unrelated namespace to bypass ownership checks, strip
finalizers, or delete namespaces directly as a recovery shortcut. Kubernetes
absence is success, but an owned namespace stuck terminating remains pending until
the deadline and can report `DELETE_FAILED`.

## 5. Manual expiration cleanup

This is a **separate** create → expire → dry-run → execute → observe walkthrough,
not a way to shorten application TTL. Keep the worker and dedicated kind cluster
running. Use a disposable trusted-local inventory: execution selects **all**
expired `READY`/`FAILED` records, not just the example. Review the dry-run IDs.
There is no scheduler or public/shared deployment support. Creating needs the
`OPERATOR` account; `--execute` needs the `CLEANUP` account in
`PLATFORM_CLEANUP_USERNAME`/`PLATFORM_CLEANUP_PASSWORD` (dry-run needs none).

In a new Bash terminal at the repository root:

```bash
set -euo pipefail
API=http://127.0.0.1:8080/api/v1
OPERATOR_USERNAME=alice   # your configured OPERATOR account
CREATED=$(curl --fail-with-body --silent --show-error \
  --connect-timeout 3 --max-time 15 -u "$OPERATOR_USERNAME" "$API/environments" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: expiry-demo-$(date -u +%Y%m%dT%H%M%SZ)-$RANDOM" \
  -d '{"name":"expiration-demo","templateId":"sample-service","ttlHours":1}')
ENVIRONMENT_ID=$(jq -er '.environmentId' <<< "$CREATED")
CREATE_OPERATION_ID=$(jq -er '.operationId' <<< "$CREATED")

deadline=$((SECONDS + 420))
status=PENDING
while (( SECONDS < deadline )); do
  OPERATION=$(curl --fail-with-body --silent --show-error \
    --connect-timeout 3 --max-time 10 "$API/operations/$CREATE_OPERATION_ID")
  status=$(jq -er '.status' <<< "$OPERATION")
  case "$status" in
    SUCCEEDED) break ;;
    FAILED) jq . <<< "$OPERATION"; exit 1 ;;
  esac
  sleep 3
done
[[ "$status" == SUCCEEDED ]] || { echo 'Creation incomplete; inspect history.' >&2; exit 1; }
ENVIRONMENT=$(curl --fail-with-body --silent --show-error \
  --connect-timeout 3 --max-time 10 "$API/environments/$ENVIRONMENT_ID")
EXPIRES_AT=$(jq -er '.expiresAt' <<< "$ENVIRONMENT")
NAMESPACE=$(jq -er '.namespace' <<< "$ENVIRONMENT")
printf 'Wait for the real expiry: %s (up to one hour)\n' "$EXPIRES_AT"
# Bounded real-clock wait, not an injected production cutoff or database edit.
python3 -c 'import datetime, math, sys, time
expiry = datetime.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
seconds = max(0, math.ceil((expiry - datetime.datetime.now(datetime.timezone.utc)).total_seconds()) + 1)
if seconds > 3660: raise SystemExit("Unexpected expiry; inspect rather than extending the wait")
time.sleep(seconds)' "$EXPIRES_AT"

python3 automation/cleanup_expired.py --api-base-url "$API" --dry-run --json
# STOP and review selectedIds before the next command. This is a fresh scan.
```

TTL passing alone has made no cleanup request. After reviewing the inventory:

```bash
export PLATFORM_CLEANUP_USERNAME=cleanup-bot   # your configured CLEANUP account
read -r -s -p 'Cleanup password: ' PLATFORM_CLEANUP_PASSWORD; echo
export PLATFORM_CLEANUP_PASSWORD
if SUMMARY=$(python3 automation/cleanup_expired.py --api-base-url "$API" --execute --json); then
  :
else
  printf '%s\n' "$SUMMARY"
  echo 'Cleanup incomplete; inspect summary/history before rerunning.' >&2
  exit 1
fi
unset PLATFORM_CLEANUP_PASSWORD
jq . <<< "$SUMMARY"
DELETE_OPERATION_ID=$(jq -er --arg id "$ENVIRONMENT_ID" \
  '.acceptedOperations[] | select(.environmentId == $id) | .operationId' <<< "$SUMMARY")
# This UUID is acceptance/recovery evidence, not proof of completed removal.
deadline=$((SECONDS + 420))
status=PENDING
while (( SECONDS < deadline )); do
  OPERATION=$(curl --fail-with-body --silent --show-error \
    --connect-timeout 3 --max-time 10 "$API/operations/$DELETE_OPERATION_ID")
  jq '{status, progressStep, failureCode, failureMessage}' <<< "$OPERATION"
  status=$(jq -er '.status' <<< "$OPERATION")
  case "$status" in
    SUCCEEDED) break ;;
    FAILED) jq '.events' <<< "$OPERATION"; exit 1 ;;
  esac
  sleep 3
done
[[ "$status" == SUCCEEDED ]] || { echo 'Deletion incomplete; inspect history.' >&2; exit 1; }
ENVIRONMENT=$(curl --fail-with-body --silent --show-error \
  --connect-timeout 3 --max-time 10 "$API/environments/$ENVIRONMENT_ID")
jq -e '.status == "DELETED"' <<< "$ENVIRONMENT"
REMAINING=$(kubectl --context kind-developer-platform-lab get namespace \
  "$NAMESPACE" --ignore-not-found -o name)
[[ -z "$REMAINING" ]] || { echo 'Namespace remains; inspect it.' >&2; exit 1; }
python3 automation/cleanup_expired.py --api-base-url "$API" --dry-run --json
```

The last run skips the retained `DELETED` record; DB operations and audit history
remain available. A terminal `DELETE_FAILED` is skipped on every cleanup run.
Investigate before deliberately retrying through the portal/manual API. If a CLI
response is ambiguous, inspect detail/history first, not repeated DELETEs.
Exit 1 means errors/incomplete work (including limits); exit 2 means invalid
arguments. See [full policy, counters and recovery runbook](../automation/README.md).

For fast repeatable verification, behavioral tests inject a clock and backend
tests seed controlled expired fixtures. Those are test-only mechanisms, not
operator instructions to edit the database or weaken the 1–24-hour TTL validation.

## Troubleshooting and presenter fallback

Current cleanup-specific [verification evidence and remaining gates](../automation/README.md#evidence-and-remaining-gates-for-this-change)
distinguish fake HTTP, real backend/PostgreSQL, browser, and kind results. The
historical deletion evidence below is not a new expiration end-to-end pass.

| Symptom | Check / expected behavior |
|---|---|
| Portal cannot load or gets a proxy error | Confirm PostgreSQL and Spring Boot started, then `curl --fail http://127.0.0.1:8080/api/v1/environments`. Use the Vite dev server, not preview alone. |
| Database login fails | Compose reads `.env`; Spring Boot does not. Re-export `DB_*`. An existing database volume retains its original password. |
| Operation stays `PENDING` with zero attempts | Restart the backend with `WORKER_ENABLED=true` and the same database exports. API-only acceptance still works with the worker off. |
| Kubernetes configuration or ownership failure | Inspect sanitized failure fields and backend logs locally. Verify the trusted kubeconfig contains `kind-developer-platform-lab` and a loopback HTTPS endpoint; do not bypass safety checks. |
| Provisioning times out / image pull stalls | Inspect `kubectl --context kind-developer-platform-lab -n "$NAMESPACE" describe pods` and operation events; check Docker resources/network access. Failed operations are terminal; fix the cause and use a new request key. |
| Deletion fails or stays pending | Inspect the delete operation and namespace metadata. Check worker configuration, ownership labels, connectivity, and finalizers. The worker does not force removal. After correcting the cause, retry a `DELETE_FAILED` record through the API/portal. |
| Refresh fails after data loaded | The portal retains and labels last-loaded data. Restore the backend and use **Try again**; do not narrate stale status as live health. |

For a portal/API-only walkthrough, omit kind and leave the worker disabled:
demonstrate durable `REQUESTED` / `PENDING`, safe replay, and portal reads. **Do not claim that
resources were provisioned.** Restarting the backend with the worker enabled
later processes pending work. Never use a shared cluster for failure injection.

### Prior deletion-slice verification limits

Automated frontend and PostgreSQL-backed backend checks cover acceptance,
ownership conflicts, duplicate requests, retries, and lease recovery. A real-kind
namespace deletion check was also exercised. The complete create → readiness →
delete test could not finish in the implementation sandbox: sample and CoreDNS
HTTP probes timed out, with infrastructure pods unhealthy as well. Browser
verification was blocked by the Playwright transport's OAuth requirement.
Neither a successful full browser-to-cluster flow nor production readiness is
claimed. Run the walkthrough above on a working local kind installation to close
these evidence gaps; do not disable probes or bypass ownership checks to do so.

## Finish / reset

Stop the frontend/backend and any port-forward with Ctrl+C. Stop PostgreSQL
without discarding its named volume:

```sh
docker compose stop postgres
```

Use the portal/API deletion workflow or explicitly invoke the expiration CLI
before stopping the worker. There is no daemon-triggered cleanup on expiry,
failure, or shutdown.
If you intentionally want to remove **all workloads in this disposable local
kind cluster**, the separate manual reset remains:

```sh
kind delete cluster --name developer-platform-lab
```

This does not remove database records; old `READY` records are historical, not
live health. Avoid `docker compose down --volumes` unless you deliberately want
to erase all local requests and idempotency history. See [infrastructure
safety notes](../infra/README.md) and the [API contract](api.md) for details.
