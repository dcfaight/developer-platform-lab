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
in its Kubernetes package. The separate [K3s packaging](#local-k3s-platform-api-only)
in `kubernetes/` deploys the platform itself, not those sample workloads.
There is no arbitrary manifest or image input.

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
  network. Those networks are internal; only the frontend also joins an edge
  network so Docker can publish its loopback TLS port. No backend or database
  port is published.
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
  design here. The separate K3s packaging below does not enable worker access
  either; narrowly scoped worker RBAC must be designed before enabling it.
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

## Local K3s platform (API-only)

`kubernetes/` is a first-pass Kustomize package for a **separate, single-node K3s**
installation: one backend API, one frontend, PostgreSQL 18.6 with a 5Gi
`local-path` PVC, ClusterIP Services, generated ConfigMaps, external Secrets, and
a Traefik TLS Ingress. It reuses the existing Dockerfiles without changing Compose
or the host-run kind workflow. The [baseline](../docs/pre-kubernetes.md) remains
the roadmap, not evidence of completed lifecycle validation.

**Boundary:** the `deploy` profile and explicit worker disablement make this
API-only. Authenticated creates are persisted but remain pending; accepted
deletions cannot complete. Do not connect the kind worker to this database, mount
admin kubeconfig, or enable the worker in these manifests. In-cluster worker
authentication/RBAC and end-to-end provisioning are later work.

### Prerequisites and images

Use Bash, Docker, kubectl with Kustomize, OpenSSL, and an existing K3s node with
the default `local-path` provisioner. Traefik with its `websecure` entrypoint is
needed only for ingress; TLS port-forwarding below also works without Traefik.
Run the commands from the repository root. Explicitly select the K3s context,
**not** `kind-developer-platform-lab`:

```sh
export K3S_CONTEXT=your-k3s-context
kubectl --context "$K3S_CONTEXT" get nodes
kubectl --context "$K3S_CONTEXT" get storageclass local-path
kubectl --context "$K3S_CONTEXT" get ingressclass traefik
docker build -t platform-lab-backend:k3s-v1 ./backend
docker build -t platform-lab-frontend:k3s-v1 ./frontend
docker save platform-lab-backend:k3s-v1 platform-lab-frontend:k3s-v1 \
  -o /tmp/platform-lab-images.tar
# Run on the K3s node (copy the archive there first if building elsewhere):
sudo k3s ctr images import /tmp/platform-lab-images.tar
rm /tmp/platform-lab-images.tar
```

Build for the node's architecture. Docker's image store is **not** K3s's containerd
store. Both app images use `imagePullPolicy: Never` intentionally: import them on
the node or pods fail with `ErrImageNeverPull`. PostgreSQL is pulled from Docker
Hub. For updates, build/import a new tag and update `images` in
`infra/kubernetes/kustomization.yaml` before reapplying; do not silently reuse tags.
Registry distribution and multi-node scheduling are not provided.

### Secrets and TLS

No usable credentials, hashes, or private keys are committed. Create the namespace
and two required Secrets separately; applying the package alone fails closed
until they exist. Generate the operator password hash using the existing
[backend recipe](../backend/README.md#authentication-and-authorization).
Use a **different database password** from development/Compose.

```sh
kubectl --context "$K3S_CONTEXT" apply -f infra/kubernetes/namespace.yaml
SECRET_DIR="$(mktemp -d /tmp/platform-k3s-secrets.XXXXXX)"
chmod 700 "$SECRET_DIR"
read -r -s -p 'Database password: ' DB_PASSWORD; echo
printf '%s' "$DB_PASSWORD" > "$SECRET_DIR/database-password"
read -r -p 'Operator username: ' OPERATOR_USERNAME
printf '%s' "$OPERATOR_USERNAME" > "$SECRET_DIR/operator-username"
read -r -s -p 'Operator password hash (not plaintext): ' OPERATOR_HASH; echo
printf '%s' "$OPERATOR_HASH" > "$SECRET_DIR/operator-password-hash"
chmod 600 "$SECRET_DIR/"*
kubectl --context "$K3S_CONTEXT" -n platform-lab create secret generic platform-secrets \
  --from-file="$SECRET_DIR/database-password" \
  --from-file="$SECRET_DIR/operator-username" \
  --from-file="$SECRET_DIR/operator-password-hash"
rm -rf "$SECRET_DIR"
unset DB_PASSWORD OPERATOR_USERNAME OPERATOR_HASH SECRET_DIR
```

Values must be nonempty. Secrets are Kubernetes Secrets, **not** an external secret
manager: restrict cluster access and configure K3s secrets encryption separately.
Avoid shell tracing and never commit credentials or generated Secret YAML.

For disposable local testing, create a one-day certificate **outside the checkout**:

```sh
TLS_DIR="$(mktemp -d /tmp/platform-k3s-tls.XXXXXX)"
chmod 700 "$TLS_DIR"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "$TLS_DIR/tls.key" -out "$TLS_DIR/tls.crt" \
  -subj /CN=platform.localhost -addext 'subjectAltName=DNS:platform.localhost'
chmod 600 "$TLS_DIR/tls.key"
kubectl --context "$K3S_CONTEXT" -n platform-lab create secret tls platform-tls \
  --cert="$TLS_DIR/tls.crt" --key="$TLS_DIR/tls.key"
```

For longer use, supply a certificate and chain trusted by clients for your chosen
hostname; update both ingress `rules.host` and `tls.hosts` if not using
`platform.localhost`. There is no cert-manager, issuance, or automatic renewal.
The Secret also provides nginx's direct TLS endpoint, with group-readable key
permissions for UID/GID 101. Never bypass TLS verification or send Basic
credentials over HTTP.

### Apply and smoke-test

```sh
kubectl kustomize infra/kubernetes
kubectl --context "$K3S_CONTEXT" apply --dry-run=server -k infra/kubernetes
kubectl --context "$K3S_CONTEXT" apply -k infra/kubernetes
kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/postgres --timeout=180s
kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/backend --timeout=300s
kubectl --context "$K3S_CONTEXT" -n platform-lab rollout status deployment/frontend --timeout=180s
kubectl --context "$K3S_CONTEXT" -n platform-lab get pods,svc,pvc,ingress
PLATFORM_K3S_TEST=true PLATFORM_K3S_CONTEXT="$K3S_CONTEXT" \
  python3 -m unittest discover -s automation/tests -p test_kubernetes.py -v
```

Services must exist before nginx starts so that it can resolve `backend` through
cluster DNS; `apply -k` creates them before Deployments. nginx resolves that stable
Service address on startup, not via Docker's `127.0.0.11` resolver. Restart nginx
if replacing the backend Service with a different ClusterIP.

For TLS ingress, map `platform.localhost` to the K3s node's reachable IP in local
DNS/hosts, or use curl's explicit override:

```sh
export K3S_NODE_IP=192.0.2.10 # Replace with your node's reachable address.
curl --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "platform.localhost:443:$K3S_NODE_IP" https://platform.localhost/
curl --fail --cacert "$TLS_DIR/tls.crt" \
  --resolve "platform.localhost:443:$K3S_NODE_IP" https://platform.localhost/api/v1/environments
```

The ingress is HTTPS-only on Traefik's `websecure` entrypoint, not an HTTP redirect.
It terminates TLS and forwards to nginx's cluster-internal HTTP listener; nginx
proxies only `/api/v1/` to the backend, not `/actuator`. Cluster-internal traffic is
not encrypted or isolated in this pass. Restrict node/firewall access to trusted
local clients: reads remain anonymous, and this is not safe public exposure.

Without ingress, keep this running in another terminal:

```sh
kubectl --context "$K3S_CONTEXT" -n platform-lab port-forward --address 127.0.0.1 svc/frontend 8443:443
```

Then use `curl --fail --cacert "$TLS_DIR/tls.crt" --resolve
platform.localhost:8443:127.0.0.1 https://platform.localhost:8443/` (on one line).
For browser use, map the hostname locally and trust the test certificate explicitly.
Sign in with the operator's **plaintext** password, not its hash.

The offline check is `python3 -m unittest discover -s automation/tests -p
test_kubernetes.py -v` (on one line); it renders manifests when kubectl is present.
Live checks are opt-in and read-only: they verify availability, a bound PVC,
worker disablement, UI/API routing and private management paths. They do not
prove ingress/TLS, worker lifecycle, restart persistence, or recovery.

### Persistence, startup, and remaining work

- PostgreSQL initializes its database/user/password only on an **empty** volume.
  Its mount is `/var/lib/postgresql`, matching the PostgreSQL 18 image and Compose.
  One replica and `Recreate` avoid concurrent database pods using the same data.
  K3s local-path data is node-local, not HA or a backup.
- Flyway migrations run in backend startup, before readiness. There is no ordered
  pod startup: the backend may restart until PostgreSQL accepts connections.
  Its startup probe allows five minutes; readiness includes the database,
  liveness does not. The UI probe checks nginx, not API availability.
- Pod restarts/reapplying preserve the PVC. **Deleting the PVC or namespace can
  permanently delete data** with local-path's default Delete reclaim policy.
  `kubectl delete -k` also deletes the PVC/namespace: it is a destructive teardown,
  not a routine stop. Do not use it to upgrade.
- Secret updates need app restarts; nginx also needs a restart to reload renewed
  TLS certificates. Changing the database Secret does **not** change the password
  inside an initialized database: coordinate a database-side password rotation
  before restarting consumers. Back up before image/schema upgrades; migration
  rollback is not assured.
- App pods run non-root with read-only roots, bounded memory-backed `/tmp`,
  dropped capabilities, seccomp, resource requests/limits and no service-account
  token. PostgreSQL retains its image's initialization privileges. These are
  useful defaults, not validated production hardening.
- **TODO:** test backup/restore and restart persistence on K3s; implement in-cluster
  worker client/authentication/RBAC without weakening ownership/deletion guards;
  add network policies, database role separation, secret encryption/rotation,
  certificate renewal and access review; validate the full environment lifecycle.
  No expiration scheduler or cleanup-CLI TLS extension is included.
