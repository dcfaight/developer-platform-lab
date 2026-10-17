# Frontend

React + TypeScript + Vite environment lifecycle and status portal. Requires Node.js
24 and npm 11. This is a work-in-progress portfolio platform, not production software.

From this directory:

```sh
npm ci
npm run dev
```

Start PostgreSQL and the backend using the [root setup guide](../README.md#14-local-setup-plan),
then open http://localhost:5173. Vite proxies same-origin `/api` requests to
http://127.0.0.1:8080. No CORS changes or build-time credentials are needed.
Sign in through the account panel before reading API data; every API read requires
Basic authentication.

## Available now

- Paginated environment inventory (10 per page): name, status, template,
  namespace, team ownership, creator, created/expires times, and latest/active operation.
  Developers see environments owned by any of their teams; the backend applies
  membership filtering, without a frontend team-filter parameter.
  Ready/in-progress/failed counts summarize only the loaded page, not the
  entire inventory or live workload health.
- Developers and operators can select an environment to see richer metadata and paginated operation history
  (5 per page), newest first. Hash links such as `#environment/{id}` support
  reloads and browser back/forward navigation without a router dependency.
- Operation state, recorded progress step, attempt count, lifecycle/lease/retry
  timestamps, sanitized failure summaries, request correlation IDs, and durable
  progress events in oldest-first backend order with event counts. Lifecycle
  milestones are titled by their code, each event shows time elapsed since
  acceptance, and completed operations show a total duration. Progress is
  not an invented percentage.
- Loading, empty, unavailable/not-found, and retry states. Views refresh every
  10 seconds after a request finishes; Refresh retries immediately. A failed
  refresh retains and labels the last loaded data. Navigation cancels obsolete
  requests. All displayed times use the browser's local timezone.
- Create requests for the fixed `sample-service` template with a validated name
  and 1–24-hour lifetime. Developers must select one of their memberships, including
  when they belong to multiple teams. Operators can select a membership, enter any
  existing team ID, or omit the team for a legacy operator-managed environment.
  A successful `202` navigates to the returned
  environment's existing detail and operation history; acceptance is not readiness.
- Ambiguous submissions retain the exact normalized payload and idempotency key
  in tab-scoped session storage for explicit replay. Refreshing the same tab can
  recover it; closing the tab or unavailable browser storage can lose that recovery
  state. Starting a new request after an uncertain result requires explicit
  confirmation and warns that the earlier request may already exist. This is not
  cancellation and does not guarantee absence.
  Recovery is scoped to the signed-in username; signing in again is required
  after a reload, and another account cannot replay the previous account's intent.
- Environment detail exposes guarded deletion for READY, FAILED, and DELETE_FAILED
  environments without a pending/running latest operation. Confirm the exact
  `namespace/name` in a keyboard-accessible dialog, or cancel without sending a request.
  Duplicate submission is blocked; navigation aborts outstanding browser requests,
  not server-side cleanup. `DELETE /api/v1/environments/{id}` sends no body or
  idempotency key: the server serializes deletion by environment ID.
- Deletion acceptance is not completion. Acceptance immediately refreshes detail
  and resets history to the newest page, then normal polling continues. Sanitized
  errors include safe request IDs. An interrupted/uncertain request can be retried
  for the same environment when its current status allows deletion; if already
  DELETING, cancel the dialog and monitor history instead.

The portal uses only the same-origin `/api` proxy and does not access Kubernetes
credentials or accept arbitrary templates/images. Use the
[local demo and troubleshooting guide](../docs/demo.md) or the backend's
[manual API checks](../backend/README.md#manual-api-checks). With the worker
disabled the operation stays pending; enable the optional local-kind worker to
demonstrate actual progress.

## Validation

```sh
npm run lint
npm test
npm run build
```

Vitest and React Testing Library cover the API boundary, main UI states,
navigation, pagination, refresh recovery, cancellation, and safe text rendering.
CI runs these checks. Production code uses native fetch and React hooks; the
new dependencies are development-only testing tools.

## Current limitations

All API reads and writes require credentials. The account panel verifies them
with `GET /api/v1/me`, whose response is `{username, roles, teams: string[]}`.
Memberships are durable backend SQL data, not client-supplied headers or configuration.
Credentials remain only in page memory (not `localStorage`, `sessionStorage`, or
cookies), so reloading or closing the tab signs you out.

`DEVELOPER` accounts can read and manage only environments owned by their member
teams; they cannot manage unowned operator environments. `OPERATOR` accounts can
read and manage all environments. `/me` returns the operator's memberships, not
a catalog of every team. `CLEANUP` is an exclusive automation role and cannot be
combined with developer/operator roles. It is not a general portal role: permitted
environment list/detail discovery is read-only, and the portal does not request
operation history or permit creation/deletion for this account. Direct operation
reads are also forbidden to CLEANUP. Global `/operations/metrics` is operator-only;
the portal does not request global metrics.

Signing out, switching accounts, or starting another sign-in immediately clears
loaded inventory, detail, history, and errors. In-flight responses from previous
sessions cannot repopulate them or sign out a newer account, including when the
new sign-in fails. A `401` signs the portal out and keeps a pending creation intent
and key for safe replay by the same account after signing in again; a `403`
reports the team/role rejection. There is no sign-up, password change, SSO, or
session timeout. No client-side team filtering, push updates, log stream, or direct Kubernetes
actions are exposed. Expiry metadata alone does not trigger removal; the separate
[manual cleanup CLI](../automation/README.md) defaults to dry-run and requires
explicit `--execute`. The portal does not invoke expiration cleanup.
Only backend-sanitized summaries are available; messages render as text, not HTML.
Polling and offset pagination are intended for a small local demo, not a large
multi-user service. No consistent snapshot across separate HTTP requests is promised.

`npm run build` creates static assets in `dist/`. Vite's API proxy and preview
are for local development, not production serving.
The [container deployment](../infra/README.md#hardened-single-host-deployment-api-only)
builds that same bundle with `npm ci`, then serves only static assets and
same-origin `/api/v1/` through non-root nginx on TLS port 8443. No credentials
or API URLs are baked into the bundle. Certificates are mounted read-only at
runtime; there is no plaintext published listener. nginx uses bounded `/tmp`,
disables access logging (including credential-bearing request query strings),
and sets HSTS, nosniff, frame-denial and referrer headers. Its internal HTTP
`/healthz` checks nginx only, not backend or database readiness.

Keep this deployment restricted to trusted users: Basic authentication and
backend team authorization are not SSO or public-service hardening.
The host-run local workflow above is unchanged.
