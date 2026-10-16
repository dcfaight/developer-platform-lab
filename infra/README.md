# Infrastructure

## Local kind lab

`kind/cluster.yaml` defines a dedicated, single-node local development cluster.
With Docker and kind installed, from the repository root:

```sh
kind create cluster --name developer-platform-lab --config infra/kind/cluster.yaml
kubectl --context kind-developer-platform-lab get nodes
```

Remove the local cluster when finished:

```sh
kind delete cluster --name developer-platform-lab
```

The backend worker builds the approved namespace and sample Deployment manifest
in its Kubernetes package. `kubernetes/` remains reserved for future standalone
manifests, quotas, and RBAC. There is no arbitrary manifest or image input.

Enable the worker as described in [backend/README.md](../backend/README.md).
It requires the `kind-developer-platform-lab` kubeconfig context and a loopback
HTTPS API server; remote clusters are deliberately unsupported. Use a host-run
backend, not a container with a different loopback network. Do not commit
kubeconfig files or secrets. The kind admin credentials are for this disposable
lab only, not a production permission model.

Provisioned namespaces are retained after failures or TTL expiry until explicitly
deleted through the portal/API. The leased worker removes only owned namespaces
and reports completion after absence is observed; see the [local lifecycle
runbook](../docs/demo.md#4-delete-safely-and-verify-absence). Expiration
cleanup is available through the manually invoked API-only CLI, not a scheduler.
Deleting the entire disposable kind cluster above is
a separate manual lab reset, not the platform deletion workflow.

## Hardened single-host deployment (API-only)

This is a separate, opt-in Compose project, not an overlay on the developer
Compose file. Run commands from the repository root with Docker Compose 2.24.4+
(`!reset` removes the inherited PostgreSQL host port), Docker BuildKit, Python 3.12
and OpenSSL. Existing `docker compose up postgres`, host-run backend/Vite, kind
provisioning, deletion and cleanup commands remain unchanged.

**Boundary:** this packages the portal and persistent API, not a production
provisioner. The backend's `deploy` profile disables the worker. Create requests
are authenticated and persisted but stay pending; accepted deletions also need a
worker to complete. Do not point a host worker at this separate deployment's
database or mount kind admin kubeconfig into a container. Use the existing
[local demo](../docs/demo.md) for the complete lifecycle. The cleanup CLI remains
trusted-loopback HTTP only and is not extended to this TLS endpoint in this slice.

### Configure and start

1. Use a **separate database password**, operator username and slow password hash.
   Keep them in your deployment environment or an access-restricted, ignored
   `.env.deploy` file (Compose accepts `--env-file .env.deploy`). Never commit
   credentials, hashes, certificates or private keys. Environment values are
   visible to Docker administrators; this is not external secret management.
   Generate an operator hash with the existing
   [backend recipe](../backend/README.md#authentication-and-authorization).
   Export these variables in the deployment shell:

   ```sh
   read -r -s -p 'Deployment database password: ' POSTGRES_PASSWORD; echo
   export POSTGRES_PASSWORD
   export DEPLOY_OPERATOR_USERNAME=alice
   # Paste the generated hash, not the plaintext login password:
   read -r -s -p 'Operator password hash: ' DEPLOY_OPERATOR_PASSWORD_HASH; echo
   export DEPLOY_OPERATOR_PASSWORD_HASH
   export TLS_CERT_FILE=/absolute/path/to/tls.crt
   export TLS_KEY_FILE=/absolute/path/to/tls.key
   export HTTPS_PORT=8443
   ```

   All five credentials/certificate variables must be nonempty or Compose refuses
   to start. Certificate files must already exist. The certificate must match the
   hostname used by clients, with a complete chain trusted by those clients.
   nginx runs as UID/GID `101:101`: ensure that identity can read the key, e.g.
   on a rootful Linux Docker host, `sudo chown root:101 "$TLS_KEY_FILE"` and
   `sudo chmod 640 "$TLS_KEY_FILE"`; do not make a real deployment key world-readable.
   Adjust ownership for rootless/user-namespace mappings. Mounts are read-only.

2. For **disposable localhost testing only**, generate a one-day self-signed
   certificate outside the checkout, then explicitly trust that certificate for
   test requests:

   ```sh
   TLS_DIR="$(mktemp -d /tmp/platform-tls.XXXXXX)"
   export TLS_CERT_FILE="$TLS_DIR/tls.crt"
   export TLS_KEY_FILE="$TLS_DIR/tls.key"
   openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
     -keyout "$TLS_KEY_FILE" -out "$TLS_CERT_FILE" -subj /CN=localhost \
     -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1'
   sudo chown root:101 "$TLS_KEY_FILE"
   sudo chmod 640 "$TLS_KEY_FILE"
   ```

3. Build and start:

   ```sh
   docker compose -f compose.deploy.yaml config --quiet
   docker compose -f compose.deploy.yaml build --pull
   docker compose -f compose.deploy.yaml up -d --wait --wait-timeout 180
   curl --fail --cacert "$TLS_CERT_FILE" https://localhost:8443/
   curl --fail --cacert "$TLS_CERT_FILE" https://localhost:8443/api/v1/environments
   ```

   Open `https://localhost:8443` in a browser that trusts the configured certificate.
   Sign in using the configured operator's **plaintext** password, not its hash.
   The frontend publishes only `127.0.0.1:${HTTPS_PORT}:8443`; adjust the example
   URL if changing the port. Do not bypass TLS verification or transmit Basic
   credentials to an HTTP URL. Public/interface binding is intentionally not
   provided; TLS alone does not make anonymous reads safe for internet exposure.

### Runtime and operations

- Backend and frontend images use digest-pinned build/runtime bases, separate
  build stages, numeric non-root users, no build tools in runtime images,
  read-only roots, writable size-bounded tmpfs, dropped capabilities,
  `no-new-privileges`, CPU/memory/PID limits and init process signal forwarding.
  Only nginx can reach the API network; only the backend can reach the database
  network. Both networks are internal; no backend or database port is published.
  Docker administrators and the host remain trusted. Compose provides limits,
  not scheduler resource requests or multi-host availability.
- PostgreSQL reuses the existing pinned image and persistent volume definition
  but runs in the **separate** `platform-lab-deploy` project/volume. It needs a
  writable data directory and its standard startup privileges; application
  container restrictions do not apply to it. The database account still owns
  migrations and is not a least-privilege runtime database role.
- Startup waits for PostgreSQL and backend readiness before starting nginx.
  Backend readiness checks database access; liveness measures the process.
  Flyway runs before readiness. nginx's health check measures its own listener,
  not end-to-end API availability. Check backend readiness independently:

  ```sh
  docker compose -f compose.deploy.yaml ps
  docker compose -f compose.deploy.yaml exec -T backend \
    curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness
  docker compose -f compose.deploy.yaml logs --tail 100 backend frontend
  ```

  Database loss makes readiness fail, not liveness; do not create a restart storm
  for a dependency outage. Compose does **not** restart unhealthy-but-running
  services or withdraw traffic based on health. Requests may receive 5xx during
  outages; restore PostgreSQL and check readiness again. Restart policy covers
  process exit/host restart, not HA. nginx re-resolves the backend's Docker DNS
  name after replacement. Allow the 30-second stop grace period.
- Rebuild periodically with updated reviewed base-image digests and dependency
  pins. Builds use the committed Maven Wrapper and npm lockfile, but are not
  byte-for-byte reproducible or a container vulnerability scan. Trust the host,
  build network and registries; never disable package-download TLS verification.
  Back up and restore-test PostgreSQL before upgrades; Flyway migrations may
  prevent simple image rollback. Take a backup before applying new migrations.
- Replace certificates before expiry and restart/recreate nginx to load them.
  HSTS lasts one year; use disposable localhost certificates only for testing.
  Rotate password hashes by recreating the backend. There is no certificate
  renewal, SSO, external secrets, rate limiting, read authorization, per-owner
  RBAC, multi-tenant isolation, database transport TLS, or worker service-account
  design here. No Kubernetes deployment manifests are claimed: future pod
  deployment must disable automatic service-account-token mounting for the
  API/portal, use restricted pod security/non-root/read-only roots, and design
  narrowly scoped worker RBAC before enabling Kubernetes access.
- Stop with `docker compose -f compose.deploy.yaml down`; this retains the
  database volume. `down --volumes` permanently deletes deployment history:
  use only for disposable tests after confirming the project name.

### Validation

CI keeps frontend, backend, automation and real-kind checks, and adds an isolated
deployment smoke job. It generates disposable credentials/certificates, builds
both images, starts the restricted stack, verifies TLS with certificate validation,
static serving, same-origin API auth/idempotent creation/delete guards, internal
probes and Docker runtime restrictions, then removes its own project/volumes.
Run it explicitly with:

```sh
PLATFORM_DEPLOY_TEST=true python3 -m unittest discover -s automation/tests -p test_deployment.py -v
```

Without that opt-in, automation discovery only validates deployment configuration
and missing-setting failures. Backend tests separately verify database-loss
readiness versus liveness. A successful API probe does not prove kind provisioning,
namespace deletion, backup recovery, certificate renewal or production readiness.
