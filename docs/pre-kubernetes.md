# Kubernetes Deployment Baseline

Assessment date: October 16, 2026.

This records the platform's starting point before deploying the platform services
themselves to Kubernetes. It is a baseline and roadmap, not a deployment guide or
a claim of production readiness.

## Current platform and workflows

- **Frontend:** React, TypeScript, and Vite provide the environment lifecycle and
  status portal. The browser calls the backend API, never Kubernetes.
- **Backend/worker:** one Spring Boot application provides the API and an opt-in
  background worker. PostgreSQL stores environments, operations, leases,
  idempotency records, and audit history; Flyway applies schema migrations.
- **Automation:** the manually invoked Python expiration CLI defaults to dry-run
  and requests deletion through the backend API. It has no database or Kubernetes
  access; there is no expiration scheduler.
- **Local development:** `compose.yaml` runs PostgreSQL with a named data volume;
  the backend runs on the host through Maven/IntelliJ, and the frontend through
  Vite with an API proxy. API-only use needs no Kubernetes cluster. See the
  [local setup](../README.md#14-local-setup-plan) and [lifecycle demo](demo.md).
- **Single-host deployment:** frontend/backend Dockerfiles and
  `compose.deploy.yaml` already package the portal/API and PostgreSQL with a
  persistent volume, a loopback TLS endpoint, probes, and container restrictions.
  This separate Compose project's `deploy` profile disables the worker: accepted
  create/delete operations cannot complete there without a worker. Do not attach
  the host kind worker to that database or mount admin kubeconfig into containers.
  See the [API-only deployment runbook](../infra/README.md#hardened-single-host-deployment-api-only).

## Existing kind workflow versus planned K3s target

Today, the **host-run** worker uses the dedicated `kind-developer-platform-lab`
context and a loopback HTTPS API server. It provisions platform-owned namespaces
and the fixed, approved `sample-service` Deployment, not the platform itself.
Readiness must be observed before `READY`; safe deletion checks ownership and
waits for namespace absence before `DELETED`. Arbitrary manifests/images and
remote clusters are deliberately unsupported. See the [kind lab](../infra/README.md#local-kind-lab).

The separately prepared **local single-node K3s cluster** is the planned platform
deployment target. Its successful NGINX/port-forward smoke test (demo since
removed) establishes basic cluster access, not platform deployment or lifecycle
validation. It does not replace the repository's existing kind development/test
workflow. Switching the current kubectl context does not retarget the worker:
K3s and in-cluster access require an explicit client/authentication design.

## Missing Kubernetes deployment pieces

Existing Docker images, Compose storage/TLS, and kind provisioning tests are useful
starting points, but do not supply a full platform Kubernetes deployment:

- No platform manifests or Helm chart package frontend, backend/worker, and
  PostgreSQL; `infra/kubernetes/` is still a placeholder.
- No Kubernetes persistent storage configuration or validated database
  backup/restore flow.
- No platform ingress, hostname, TLS certificate provisioning, or renewal flow.
- No in-cluster worker credentials/service-account and least-privilege RBAC design.
- No platform cluster-side hardening configuration, including network policies,
  pod security, resource requests/limits, and secret handling.
- No full-stack Kubernetes validation flow for the deployed portal, API, database,
  and worker. Existing real-kind tests exercise sample-workload provisioning;
  the Compose smoke test exercises the API-only stack.

## Next-phase implementation checklist

- [ ] Package frontend, backend/worker, and PostgreSQL for Kubernetes, including
  configuration, Services, health probes, and migration/startup behavior.
- [ ] Configure persistent database storage, retention, and backup/restore;
  verify recovery and plan backups before migrations or upgrades.
- [ ] Design explicit K3s/in-cluster client access and worker service-account
  authentication with narrowly scoped RBAC. Disable automatic token mounting
  for components that do not need Kubernetes access; never reuse admin kubeconfig.
  Preserve namespace ownership and deletion preconditions.
- [ ] Configure platform ingress, hostname, and TLS with trusted certificates
  and a renewal plan; do not treat the existing Compose TLS endpoint as ingress.
- [ ] Apply baseline hardening: restricted pod security, non-root/read-only
  application containers, dropped capabilities, resource requests/limits,
  network isolation, and protected secrets. Review anonymous reads and operator
  permissions before broadening exposure.
- [ ] Validate the deployed stack end to end: authenticated creation, observed
  workload readiness, durable operation history, safe deletion and namespace
  absence, API-driven expiration cleanup, restart persistence, and database
  restore. Keep existing local/kind checks working.

These are planned capabilities, not completed deployment instructions.
