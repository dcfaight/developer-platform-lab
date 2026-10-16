# Frontend

Read-only React + TypeScript + Vite environment status portal. Requires Node.js
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
- Select an environment to see richer metadata and paginated operation history
  (5 per page), newest first. Hash links such as `#environment/{id}` support
  reloads and browser back/forward navigation without a router dependency.
- Operation state, recorded progress step, attempt count, lifecycle/lease/retry
  timestamps, sanitized failure summaries, request correlation IDs, and durable
  progress events in backend order. Progress is not an invented percentage.
- Loading, empty, unavailable/not-found, and retry states. Views refresh every
  10 seconds after a request finishes; Refresh retries immediately. A failed
  refresh retains and labels the last loaded data. Navigation cancels obsolete
  requests. All displayed times use the browser's local timezone.

Create an environment through the backend's
[manual API checks](../backend/README.md#manual-api-checks) to populate the portal.
With the worker disabled the operation stays pending; enable the optional
local-kind worker to demonstrate actual progress. The portal sends only GETs.

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

No create/delete controls, authentication, filtering, push updates, log stream,
or Kubernetes actions are exposed. Expiry is metadata, not automated cleanup.
Only backend-sanitized summaries are available; messages render as text, not HTML.
Polling and offset pagination are intended for a small local demo, not a large
multi-user service. No consistent snapshot across separate HTTP requests is promised.

`npm run build` creates static assets in `dist/`. Vite's API proxy is for local
development; serving that bundle elsewhere requires a same-origin reverse proxy
for `/api/v1` to the backend. Vite preview alone is not a production deployment.
Keep both services local/trusted; this PR does not add a production security boundary.
