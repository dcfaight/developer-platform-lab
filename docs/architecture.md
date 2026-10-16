# Architecture

The [root README](../README.md#6-architecture) is the current design blueprint.
The backend API and worker will initially share one Spring Boot application.
PostgreSQL will persist environments, operations, and audit events; Kubernetes
will supply observed workload state.

Current scaffold: a static React page, a bootable backend without API routes,
and local PostgreSQL/kind configuration. Persistence and orchestration are not
implemented. Add implementation diagrams and trade-offs here as work proceeds.
