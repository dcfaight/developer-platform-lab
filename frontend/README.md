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
http://127.0.0.1:8080. No CORS changes or frontend credentials are needed.

## Available now

- Paginated environment inventory (10 per page): name, status, template,
  namespace, created/expires times, and latest/active operation.
  Ready/in-progress/failed counts summarize only the loaded page, not the
  entire inventory or live workload health.
- Select an environment to see richer metadata and paginated operation history
  (5 per page), newest first. Hash links such as `#environment/{id}` support
  reloads and browser back/forward navigation without a router dependency.
- Operation state, recorded progress step, attempt count, lifecycle/lease/retry
  timestamps, sanitized failure summaries, request correlation IDs, and durable
  progress events in oldest-first backend order with event counts. Progress is
  not an invented percentage.
- Loading, empty, unavailable/not-found, and retry states. Views refresh every
  10 seconds after a request finishes; Refresh retries immediately. A failed
  refresh retains and labels the last loaded data. Navigation cancels obsolete
  requests. All displayed times use the browser's local timezone.
- Create requests for the fixed `sample-service` template with a validated name
  and 1–24-hour lifetime. A successful `202` navigates to the returned
  environment's existing detail and operation history; acceptance is not readiness.
- Ambiguous submissions retain the exact normalized payload and idempotency key
  in tab-scoped session storage for explicit replay. Refreshing the same tab can
  recover it; closing the tab or unavailable browser storage can lose that recovery
  state. Starting a new request after an uncertain result requires explicit
  confirmation and warns that the earlier request may already exist. This is not
  cancellation and does not guarantee absence.
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

No authentication, filtering, push updates, log stream, or direct Kubernetes
actions are exposed. Expiry metadata alone does not trigger removal; the separate
[manual cleanup CLI](../automation/README.md) defaults to dry-run and requires
explicit `--execute`. The portal does not invoke expiration cleanup.
Only backend-sanitized summaries are available; messages render as text, not HTML.
Polling and offset pagination are intended for a small local demo, not a large
multi-user service. No consistent snapshot across separate HTTP requests is promised.

`npm run build` creates static assets in `dist/`. Vite's API proxy is for local
development; serving that bundle elsewhere requires a same-origin reverse proxy
for `/api/v1` to the backend. Vite preview alone is not a production deployment.
Keep both services local/trusted; this PR does not add a production security boundary.
