# Automation

This is the tooling/runbook layer. Current status, remaining work, and blockers
live in the [canonical roadmap table](../docs/operating-model-and-sequence.md#current-delivery-gates).
Update implementation and validation separately there; preserve the historical
evidence and explicit skipped/unknown states below.

Python 3.12, standard library only. `cleanup_expired.py` requests asynchronous
deletion through the **same backend API** as the portal. It never accesses SQL or
Kubernetes. TTL timestamps alone do not start a daemon: cleanup happens only when
an operator invokes this CLI. No scheduler/CronJob is installed.

`backup_restore.py` is a separate, explicitly opt-in disposable K3s logical
backup/restore workflow. It never connects to the live application database or
uses its PVC. See the [operator runbook](../infra/README.md#disposable-k3s-logical-backuprestore)
for safety gates, image preparation, evidence retention and limitations.
Its offline safety tests run in normal unittest discovery; the live test is
skipped unless `PLATFORM_BACKUP_RESTORE_TEST=true`.

## Opt-in lifecycle capacity baseline

`capacity_baseline.py` measures **10 existing `sample-service` environment
lifecycles with at most 2 concurrent workflows, including cleanup**. A slot
covers create → `READY` → delete → namespace absence, not just HTTP requests.
This is a small experimental **baseline measurement**, not an autoscaling
system, a monitoring stack, a saturation test, or a production-capacity claim.
It does not model 3,000 developers, concurrent application traffic, realistic
developer workloads, multi-replica throughput, or tenant isolation.

**CAPACITY-01 is the next focused validation gate** in the
[canonical roadmap](../docs/operating-model-and-sequence.md#current-delivery-gates).
The harness exists, but no successful retained target-specific baseline result
is recorded. Gate closure requires the [successful-run evidence contract](#validation-gate),
not merely executing the harness or passing its offline tests.

### Prepare a disposable target

1. Use a disposable cluster and platform installation from this revision with
   the existing provisioning worker enabled. Do not run against shared or
   production infrastructure. Do not change the worker, database/PVC, resource
   budgets, RBAC, or ownership controls to make this benchmark pass.
2. Check the explicit kubeconfig context and platform namespace. Forward the
   matching backend API onto loopback using that same context/namespace; do not
   forward an unrelated or shared API. The harness never selects the current
   context implicitly, follows redirects, or accepts a remote API URL.
3. Use an existing `OPERATOR` account for API operations/metrics. Keep credentials
   in the environment, never arguments, shell traces, reports, or pasted logs.
   Existing Kubernetes read access is needed for context collection, owned
   namespace observation, and absence verification. Resource sampling is
   optional: metrics-server is not installed by the harness, and unavailable
   sampling must remain visibly unavailable. Do not broaden permissions.
   Reserve the installation for one benchmark invocation: stop unrelated
   lifecycle requests and other harnesses, and inspect/recover previous runs'
   leftovers first. The concurrency limit is per run, not a cluster-wide quota
   or distributed lock. Record any pre-existing workload as background load.
4. Create a private artifacts directory **outside the checkout**. Record whether
   images are cold, warm, or unknown; do not claim a cold-cache result merely
   because the environments are new. Preserve the same node layout, images,
   worker settings, and cache conditions when comparing runs. Host-run or
   externally supplied configuration that cannot be observed must be recorded
   as unknown rather than inferred from repository defaults.

### Run deliberately

From the repository root, in a shell without tracing:

```sh
# In another terminal, forward ONLY the disposable installation:
kubectl --context disposable-k3s -n platform-lab \
  port-forward service/backend 8080:8080

mkdir -m 700 /tmp/platform-capacity-evidence
export PLATFORM_BENCHMARK_USERNAME=operator
read -r -s -p 'Operator password: ' PLATFORM_BENCHMARK_PASSWORD; echo
export PLATFORM_BENCHMARK_PASSWORD
export PLATFORM_CAPACITY_BASELINE=true
python3 automation/capacity_baseline.py \
  --confirm-disposable \
  --context disposable-k3s \
  --platform-namespace platform-lab \
  --api-base-url http://127.0.0.1:8080/api/v1 \
  --worker-host cluster \
  --cache-condition unknown \
  --artifacts /tmp/platform-capacity-evidence
baseline_exit=$?
printf 'exit_code=%s\n' "$baseline_exit" > /tmp/platform-capacity-evidence/run-exit.txt
unset PLATFORM_BENCHMARK_PASSWORD PLATFORM_CAPACITY_BASELINE
```

Replace the context and namespace deliberately; these example names are not
target discovery. Both the environment opt-in and confirmation flag are
required. `--help` lists the bounded deadline controls. The workload and
concurrency are deliberately fixed, not inferred from namespace quotas.
Slots are refilled as workflows finish, rather than executing five fixed pairs;
admission and evidence collection can introduce client-side gaps.
No benchmark invocation is added to CI or a scheduler; normal CI runs only the
offline safety tests.

Use a fresh private artifacts directory for each attempt, changing all paths
in the example accordingly. `/tmp` is staging, not durable retention. Record the
sanitized invocation and effective deadline/poll/sample settings in an operator
run record alongside the artifacts; never include credential values.

For a host-run backend, select `--worker-host host` and explicitly supply
`--worker-enabled true --worker-poll-delay-ms 1000` only if those are the actual
effective settings. Unobserved settings remain unknown, not verified defaults.
The normal bounds are 1,800 seconds overall, 600 seconds per lifecycle including
300 seconds reserved for cleanup, 10 seconds per HTTP request, and 5 seconds per
Kubernetes read. Polling defaults to 1 second and resource sampling to 15
seconds. Cleanup remains inside the overall and lifecycle deadlines; it is not
an unbounded extra phase.

### Evidence and interpretation

Retain the private run directory even when the command fails. Its durable JSON
`manifest.json` records generated names, original idempotency keys, accepted
environment/operation IDs, mutation intent, timings, and unresolved leftovers.
It is both the mutation journal and machine-readable evidence; `summary.txt`
provides the short readable result. Together they identify the selected
target, revision, workload, concurrency, observed cluster/node configuration,
images, worker context, cache declaration, and measurement availability.
Configuration collection is allowlisted: it must not dump kubeconfig, Secrets,
full pod specifications, database contents, credentials, or raw error bodies.

- **API latency** measures each client HTTP attempt. A successful replay after
  ambiguous acceptance is not the original acceptance latency; include attempts,
  errors, and recovery when interpreting results.
  Request timing records cover create/delete mutation attempts, not GET polls.
- **Queue wait and operation duration** come from this run's operation IDs and
  server timestamps. Queue wait is first start minus creation; end-to-end
  operation duration includes retries/waits. Readiness and namespace-absence
  observations include client polling delay. Missing values are not zero.
  Synchronize clocks; do not subtract client and server clocks to infer queue
  time.
  Concurrency describes client lifecycle workflows, not worker threads. The
  existing worker claims one operation per fixed-delay poll; a single backend
  instance interleaves work rather than launching two provisioning threads.
  Poll cadence, retries, and worker replica count affect these measurements;
  the benchmark does not change them.
- **Completions** require `READY`, successful deletion, and confirmed namespace
  absence. Report both completed/10 and completed per elapsed minute, including
  failures and cleanup time. Early termination is not a successful smaller run.
- **Operation-metrics snapshots** are supporting context from persisted platform
  history, not interval counters or this run's latency distribution. Other work
  and older operations can affect them. The preflight snapshot must be valid
  with the worker enabled before any create. Missing final metrics make the
  evidence incomplete and the exit nonzero even if all environments cleaned up.
- **Resource samples**, when available, are sampled CPU/memory observations,
  not peaks or proof of headroom. Each verified READY workload also gets a
  bounded sample before deletion, so brief lifetimes do not rely solely on
  periodic sampling. Unavailable/partial sampling prevents resource capacity
  conclusions. Cached images, node contention, polling cadence, and database
  history all affect comparability.

### Failure and recovery

All waits and requests are bounded. New workflows stop after a failure rather
than accumulating unresolved environments; already active workflows keep their
slots through bounded cleanup. Ambiguous create attempts stop admission even
if same-key replay later resolves them; that shortened run is not a passing
10-environment baseline. Cleanup uses only the backend DELETE API after
checking the run's accepted mapping, generated name, creator, template,
canonical namespace, and original create operation. The backend's Kubernetes
ownership/precondition enforcement remains authoritative. The harness never
deletes namespaces directly, strips finalizers, edits SQL, or touches a PVC.
Successful readiness also requires observing the matching owned namespace in
the explicitly selected context; absence in a different cluster is not proof
of deletion.

A timeout, interrupt, ownership mismatch, failed delete, or uncertain acceptance
is **not** success. Inspect the retained manifest and leftovers before doing
anything else. Do not start another benchmark to recover the old one.
For an ambiguous create, use the **same authenticated account, original
`Idempotency-Key`, and identical request body** from that run to recover the
original IDs; a fresh key risks a duplicate. Inspect the environment and original
operation before any manual delete. For an ambiguous delete, inspect detail,
latest operation, and history; do not blindly resend DELETE. A failed ownership
check requires investigation, not bypass. Follow the
[existing completion/recovery policy](#completionrecovery-runbook).

An abrupt process kill or machine failure can prevent cleanup. Mutation intent
is persisted before requests so an operator can investigate; the tool is not a
crash-proof controller and does not automatically resume mutations from an
edited manifest. Retain partial evidence and explicitly record every unresolved
environment or unknown acceptance. Never interpret a nonzero exit as clean
completion.

### Validation gate

The harness and offline tests are implementation evidence only. **Close
CAPACITY-01 only with one retained successful target-specific run** of all
10 existing `sample-service` environment lifecycles on a disposable target,
with at most 2 concurrent workflows including cleanup. Do not combine partial
attempts into a passing run. No measured baseline is established by this
documentation or by adding the tooling.

The retained bundle must contain `manifest.json`, `summary.txt`, `run-exit.txt`,
and an operator run record covering the following evidence. Use the generated
manifest/summary for fields the harness collects; supplement missing context
without inferring values from repository defaults.

| Required evidence | Acceptance / recording rule |
|---|---|
| Run identity and invocation | UTC date, run ID, checked-out revision (and any local changes), sanitized command, effective timeouts/poll/sample intervals, and exit code. Require exit `0`, manifest status `SUCCEEDED`, and `supportingEvidenceComplete=true`. |
| Disposable target and configuration | Explicit kubeconfig context, platform namespace and matching loopback API target; cluster/node layout and versions, platform/workload images, resource budgets, and pre-existing background load. Record unavailable observations as unavailable and externally supplied/unobserved settings as unknown. |
| Workload and concurrency | Exactly 10 `sample-service` lifecycles with 2 workflow slots maximum, including cleanup; retain generated names, original request bodies/keys, and accepted environment/create/delete operation IDs. |
| Worker and cache conditions | Worker placement (`cluster` or `host`), enabled state, effective poll delay, replica count and other observed worker settings; image cache declaration (`cold`, `warm`, or `unknown`). Preserve unknowns and distinguish declared from observed configuration. |
| Timings and completions | Mutation-attempt latency including errors/replays, available per-operation queue/provisioning/readiness/end-to-end timings, per-lifecycle duration, total elapsed time and completed per elapsed minute. Require completed `10/10`; retain missing measurements explicitly, never as zero. |
| Failures and cleanup | Retain all request/workflow errors, retries/replays, cleanup errors and leftovers, explicitly including zero failures when none occurred. All 10 must reach `READY`, successful create/delete operations, API cleanup confirmation and namespace absence in the selected context; require no unresolved leftovers. |
| Measurement availability | Require valid before/after operation-metrics snapshots with the worker enabled in preflight. Record availability of context and timing fields and READY/periodic CPU/memory samples, including unavailable/partial/not-collected states. Resource sampling is optional; unavailable/partial samples prevent resource-capacity conclusions but do not alone fail this lifecycle gate. |
| Interpretation and retention | Record sampling and comparability limitations, unknown configuration, and the sanitized durable artifact reference. This is one bounded baseline measurement, not production capacity, saturation, autoscaling, or measured support for 3,000 developers. |

After each attempt:

1. Inspect the exit record, manifest and summary together. A nonzero exit,
   incomplete supporting metrics, shortened run, failed lifecycle or unresolved
   leftover cannot close the gate, even if later manual cleanup succeeds.
   Preserve the failed attempt and follow [failure and recovery](#failure-and-recovery)
   before starting a new run in a fresh directory.
2. Review the bundle and operator record for secrets before transfer. Keep
   original idempotency keys and IDs in access-controlled operator storage;
   do not archive credentials, kubeconfig, Secrets or raw error bodies.
3. Copy the entire run directory and operator record to an operator-managed
   durable location **outside the checkout and ephemeral task environment**,
   verify the archived copy is readable and complete, and retain failed/partial
   attempts separately. A local `/tmp` path or unretained task log is not closure
   evidence.
4. Update **CAPACITY-01** in the
   [canonical roadmap](../docs/operating-model-and-sequence.md#current-delivery-gates)
   with date, target, revision, command/check, outcome, limitations and sanitized
   durable evidence reference (no credentials or signed URLs). Until the passing
   bundle is retained and referenced there, the gate remains evidence pending.
   Keep GitOps, restart-persistence and other gates separate; this result does
   not close them.

## Manual use

Start the matching backend from this revision using the
[local setup](../backend/README.md#run-locally). From the repository root:

```sh
# Both modes need a configured CLEANUP account:
export PLATFORM_CLEANUP_USERNAME=cleanup-bot
read -r -s -p 'Cleanup password: ' PLATFORM_CLEANUP_PASSWORD; echo
export PLATFORM_CLEANUP_PASSWORD
# Read-only by default; --dry-run makes that intent explicit.
python3 automation/cleanup_expired.py --dry-run
python3 automation/cleanup_expired.py --api-base-url http://127.0.0.1:8080/api/v1 --json
# Review the plan, then explicitly request deletion:
python3 automation/cleanup_expired.py --execute --json
unset PLATFORM_CLEANUP_PASSWORD
```

Both dry-run and `--execute` require
`PLATFORM_CLEANUP_USERNAME` and `PLATFORM_CLEANUP_PASSWORD` in the environment
(never on the command line) for an account with the `CLEANUP` (or `OPERATOR`)
role, [configured on the backend](../backend/README.md#authentication-and-authorization).
Missing or malformed values exit `2` before any network request. Credentials are
sent as HTTP Basic on every list/detail GET and guarded `DELETE`, and are never printed.
`CLEANUP` is exclusive: it cannot be combined with any other role. It can discover
all environments through list and detail reads, including legacy/unowned rows,
and send expiry-guarded deletes; the backend rejects creation, unguarded deletion,
operation history/direct operation reads, and metrics. This CLI uses only
list/detail discovery and guarded deletes, not those denied
diagnostic routes. A `401`/`403` on DELETE
is a definite rejection: the candidate is counted as failed (not unknown), it is
not retried, other candidates continue, and the exit code is nonzero. Audit
events record the cleanup account as the actor.

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
treated as proof of successful deletion. An operator must inspect history before another run.

Only literal loopback IPs and `localhost` (connected as `127.0.0.1`) are permitted;
the default is `http://127.0.0.1:8080/api/v1`. HTTP and verified HTTPS are allowed.
Credentials, non-loopback hosts, other schemes, query/fragment base URLs and
non-API paths are rejected. There is no remote override. Environment proxy
settings are not used, and **no redirects are followed**, including for DELETE.
Never forward a public/shared service onto localhost to bypass this boundary.
Basic credentials over plain HTTP are acceptable only because of this loopback
restriction. Team authorization is enforced by the backend, but this does not
establish strong tenant isolation, SSO, or production deployment hardening.
This is trusted-local manual tooling, not production readiness.

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
| 2 | Invalid command-line arguments or missing/malformed credentials; no network requests made. |

## Completion/recovery runbook

Sign in with an `OPERATOR` account (not the exclusive `CLEANUP` account). For each
emitted operation UUID, use authenticated `GET /api/v1/operations/{id}` and
`GET /api/v1/environments/{environmentId}` (or portal detail/history). Follow the
[bounded observation/deletion walkthrough](../docs/demo.md). Success requires a
`SUCCEEDED` delete operation and environment `DELETED`; only the worker can
confirm namespace absence. `PENDING` with the worker disabled is expected, not
completion. Do not rerun DELETE to poll progress.

For errors, correlate safe `requestId` with local backend logs. If recovery is
unknown, have an operator inspect detail/history first. A later cleanup run skips active/completed
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

### Evidence and remaining gates for the original cleanup change

This record predates the authentication boundary; its "absent auth/authz" note
is superseded by the credential requirements above.

- **Python:** 64 stdlib behavioral tests passed, including actual subprocess CLI
  requests to a local fake HTTP server; compileall passed.
- **Real backend/DB:** Maven `verify` passed (88 tests reported, four opt-in kind
  cases skipped). The real CLI → HTTP Spring backend → PostgreSQL fixture test
  proved dry-run writes nothing and execute creates durable deletes only for
  expired eligible fixtures. Row-lock race tests reject changed expiry/status.
  These tests do not prove Kubernetes removal.
- **Existing frontend:** lint, 73 tests and build passed; no new real-browser
  verification was performed. Compose configuration validation passed.
- **Real kind:** owned and legacy-owned namespace deletion/absence tests passed.
  The separate provisioning-readiness test failed: sample HTTP probes timed out,
  and CoreDNS was also unready. The remaining worker full-flow run was stopped
  after that failure; a separate targeted worker-flow attempt also failed
  readiness. Neither established completed create-to-cleanup evidence.
  Do not count deletion-only proof, interrupted work, or the earlier opt-in skips
  as a successful expiration walkthrough. No probes/safeguards were weakened.
- **Review/security:** a read-only safety review found no blocking defects, and
  changed-file secret scans were clean. Final automated review/CodeQL validation
  was blocked by the validation time limit, not passed.
- **Hosted CI:** runs require maintainer approval (`action_required`); local
  results are not a hosted-CI pass.

These are the original cleanup change's recorded verification gaps, not a claim
that current hosted CI or review is failing. Check current run/review results and
record a fresh disposable kind/browser walkthrough before treating the cleanup
acceptance gate as closed; see the
[canonical roadmap](../docs/operating-model-and-sequence.md#current-delivery-gates).
Offset-page concurrency limitations, trusted-local-only cleanup transport, and
remaining deployment hardening remain; passing tests alone do not establish
production readiness.
