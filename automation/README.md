# Automation

Python 3.12, standard library only. `cleanup_expired.py` requests asynchronous
deletion through the **same backend API** as the portal. It never accesses SQL or
Kubernetes. TTL timestamps alone do not start a daemon: cleanup happens only when
an operator invokes this CLI. No scheduler/CronJob is installed.

## Manual use

Start the matching backend from this revision using the
[local setup](../backend/README.md#run-locally). From the repository root:

```sh
# Read-only by default; --dry-run makes that intent explicit.
python3 automation/cleanup_expired.py --dry-run
python3 automation/cleanup_expired.py --api-base-url http://127.0.0.1:8080/api/v1 --json
# Review the plan, then explicitly request deletion:
python3 automation/cleanup_expired.py --execute --json
```

Each command captures its own single UTC cutoff. The execute command discovers a
fresh plan; it does not consume or guarantee the previous dry-run plan.
Only `READY`/`FAILED` records with timezone-aware `expiresAt <= cutoff` are selected.
Unexpired records and `REQUESTED`, `PROVISIONING`, `DELETING`, `DELETED`, and
`DELETE_FAILED` are skipped with reason counts. Unknown states, invalid UUIDs,
noncanonical namespaces, invalid timestamps, and malformed pages fail closed.
Additional unrelated DTO fields are allowed.

**`DELETE_FAILED` is never retried by cleanup**, even in later runs. Investigate
the failed operation and ownership/finalizer problems first, then deliberately use
the portal's retry or the unguarded manual delete API. Those actions create a new
durable operation. Never put that manual retry in an unattended loop.

Execute re-reads each selected detail and rechecks expiry/status. It sends
`DELETE /environments/{id}?expiredBefore=<cutoff>` only once per candidate/run.
The matching backend checks the cutoff and `READY`/`FAILED` eligibility under its
row lock, preventing a raced terminal failure from becoming a new delete attempt.
Expiry is currently immutable; the guarded check also protects against future
expiry changes. Use this revision of the backend, not an older server that ignores
the parameter. A future cutoff is rejected by the server; keep local clocks in sync.

`202` means **deletion requested, not completed**. Worker leases, bounded retries,
canonical namespace/ownership checks, and Kubernetes UID/resourceVersion
preconditions remain unchanged. Namespace absence must be observed before
`DELETED`; termination/finalizers are not bypassed. PostgreSQL environments,
operations, audit events, and idempotency history remain retained.

## Bounds, retries, and local-only transport

| Option | Default | Allowed |
|---|---:|---|
| `--page-size` | 20 | 1–100 |
| `--max-pages` | 100 | 1–1000 |
| `--max-scanned` | 1000 | 1–100000 returned rows, including duplicates |
| `--max-actions` | 100 | 1–1000 selected candidates |
| `--get-attempts` | 2 | 1–3 total attempts per GET |
| `--connect-timeout` | 3 seconds | finite, >0 and <=60 |
| `--request-timeout` | 10 seconds | finite, >0 and <=60 after connection |

Arguments are validated before any network activity. All responses are limited
to 1 MiB; each request has a wall-clock deadline after connection, including
headers/body. GET retries cover transport errors and HTTP 500/502/503/504, with
0.1/0.2-second backoff. **DELETE is never retried**, including on 5xx, timeout,
redirect, or malformed acceptance. A bounded detail GET attempts to recover an
accepted operation ID when the observed state is `DELETING`/`DELETED`. Otherwise
the outcome is failed or unknown, with nonzero exit. A missing record (404) is not
treated as proof of successful deletion. Inspect history before another run.

Only literal loopback IPs and `localhost` (connected as `127.0.0.1`) are permitted;
the default is `http://127.0.0.1:8080/api/v1`. HTTP and verified HTTPS are allowed.
Credentials, non-loopback hosts, other schemes, query/fragment base URLs and
non-API paths are rejected. There is no remote override. Environment proxy
settings are not used, and **no redirects are followed**, including for DELETE.
Never forward a public/shared service onto localhost to bypass this boundary.
There is still no authentication/authorization, multi-user isolation, or deployment
hardening. This is trusted-local manual tooling, not production readiness.

## Pagination and output

The CLI traverses the existing descending `createdAt`/ID offset pages, including
deleted records, and deduplicates IDs. Each page is a separate database snapshot.
Concurrent inserts can shift offsets, repeat IDs, or hide candidates: this is
**not a run-wide snapshot or a guarantee that every expired record was found**.
A later manual run can discover remaining candidates. Persistent growth may
require future cursor/snapshot support. No unbounded rescan is attempted.

Discovery completes before any mutation. Page/row/action limits, malformed
records, or discovery failures suppress **all** DELETEs for that execution.
Increase bounds deliberately after inspection; do not infer “nothing eligible”
from an incomplete scan.

Readable output includes each selected ID, intended action, operation IDs, and a
JSON summary. `--json` emits only one JSON object. Important fields:

- `cutoff`, `mode`, `policy`; `snapshotConsistent` is always false.
- `scanComplete`: traversal reached the last observed page, not global snapshot
  completeness; `failed` still reports invalid rows even if traversal finished.
- `scanned`: returned rows examined; `duplicates`: repeated valid IDs.
- `eligible`/`selectedIds`: unique discovery candidates, not completed deletions.
- `skipped`/`skipReasons`: discovery skips plus execution recheck skips; these
  phase counts are not a partition of `scanned`.
- `requested`: validated 202 responses; `alreadyDeleting`/`alreadyDeleted`:
  operations observed during ambiguous-response recovery, not attributed to us.
- `acceptedOperations`: environment/operation UUIDs and `source` (`202` or
  `recovery`); recovery additionally records the observed status.
- `failed`: definite validation/API/bounds failures; `unknown`: an attempted DELETE
  whose outcome could not be determined; `errors`: safe codes, HTTP status and
  request IDs when available, never raw response bodies or stack traces.

| Exit | Meaning |
|---|---|
| 0 | Bounded traversal finished without actionable errors; requested work is still asynchronous. Skipped states, including `DELETE_FAILED`, require the documented operator policy. |
| 1 | Incomplete scan, exceeded bound, invalid data, API/transport failure, or unknown deletion outcome. Check summary before rerunning. |
| 2 | Invalid command-line arguments; no network requests made. |

## Completion/recovery runbook

For each emitted operation UUID, use `GET /api/v1/operations/{id}` and
`GET /api/v1/environments/{environmentId}` (or portal detail/history). Follow the
[bounded observation/deletion walkthrough](../docs/demo.md). Success requires a
`SUCCEEDED` delete operation and environment `DELETED`; only the worker can
confirm namespace absence. `PENDING` with the worker disabled is expected, not
completion. Do not rerun DELETE to poll progress.

For errors, correlate safe `requestId` with local backend logs. If recovery is
unknown, inspect detail/history first. A later cleanup run skips active/completed
deletes, and guarded requests cannot create new operations from `DELETE_FAILED`.
For terminal failures inspect durable `failureCode`/events; fix ownership,
connectivity or finalizer issues through the owning system, never strip finalizers
or manually edit the database to force a success state.

## Verification

```sh
python3 -m unittest discover -s automation/tests -v
python3 -m compileall -q automation
```

CI runs these behavioral tests as well as existing frontend/backend/kind jobs.
Tests use an injected UTC clock and controlled expired fixtures, never reduced
production TTL validation or operator database edits. Local fake-HTTP/subprocess
tests exercise the real CLI transport; they are not real backend/DB or kind proof.
See the demo for a real-clock create → expire → dry-run → execute → observe flow.
