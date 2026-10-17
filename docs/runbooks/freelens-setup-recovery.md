# FreeLens setup and recovery: Windows client, Linux K3s server

Companion to the [read-only operator checklist](freelens-operator-checklist.md),
following [PR #34](https://github.com/dcfaight/developer-platform-lab/pull/34).
Use only for the dedicated disposable [local K3s platform](../../infra/README.md#local-k3s-platform).
Observation below is read-only; **issuing, installing, or revoking credentials is
separate administrator maintenance**, not a checklist step, recovery drill,
validation gate, or production-readiness claim.

## Quick recovery entry point

1. Locate the **exact kubeconfig FreeLens uses**, not merely your default
   `$HOME/.kube/config`. FreeLens can import its own copy. Inspect the existing
   connection's configuration/file information (wording varies by version);
   record the backing file path. If unclear, consult your installed version's
   documentation before changing files. Do not delete/reimport the connection.
2. Run the [Windows triage](#windows-powershell-read-only-triage). If CLI pods are
   visible, fix FreeLens namespace filtering before touching credentials.
3. Only for missing/invalid authentication, have an authorized administrator
   check [Linux prerequisites](#linux-terminal-administrator-prerequisites),
   choose **one** [token option](#credential-options), and complete the
   [shared Windows installation](#windows-powershell-transfer-and-install).
4. Verify namespaces **and** pods, reconnect the same FreeLens entry, then clean
   transfer files. Never delete workloads, reset the cluster, or grant
   `cluster-admin` to repair a connection.

The motivating incident established that installing a usable token restored
authentication, and that the empty pod view was a namespace filter (`default`
had no pods). It did **not** establish why the original credential failed;
expiry is one possibility, not a forensic conclusion.

## Windows PowerShell: read-only triage

Use Windows PowerShell with `kubectl`. Set these reusable placeholders **once in
the same PowerShell session**; replace every angle-bracket value. Keep paths with
spaces quoted. The existing connection in this example uses user `lens-readonly`
and context `lens-readonly@k3s-lan`; stop if yours differs rather than creating a
new user/context accidentally.

```powershell
$kc = 'C:\<exact FreeLens imported kubeconfig path>'
$context = 'lens-readonly@k3s-lan'
$linuxUser = '<Linux login>'
$linuxHost = '<K3s server LAN DNS name or address>'
```

Inspect only selected noncredential fields; never print `config view --raw`:

```powershell
& {
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path -LiteralPath $kc -PathType Leaf)) { throw 'Kubeconfig not found.' }
    $text = & kubectl --kubeconfig "$kc" config view -o json
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read kubeconfig.' }
    $cfg = ($text -join "`n") | ConvertFrom-Json
    if ($cfg.'current-context' -ne $context) { throw 'Unexpected current context; stop.' }
    $entry = @($cfg.contexts | Where-Object { $_.name -eq $context })
    if ($entry.Count -ne 1 -or $entry[0].context.user -ne 'lens-readonly') {
        throw 'Unexpected context/user mapping; stop.'
    }
    $cluster = @($cfg.clusters | Where-Object { $_.name -eq $entry[0].context.cluster })
    if ($cluster.Count -ne 1) { throw 'Cluster mapping missing or ambiguous.' }
    "Context: $context"
    "User: $($entry[0].context.user)"
    "Server: $($cluster[0].cluster.server)"
    # Redacted config still permits a presence check; never output its token.
    $userEntry = @($cfg.users | Where-Object { $_.name -eq 'lens-readonly' })
    $hasToken = $false
    if ($userEntry.Count -eq 1 -and
        $null -ne $userEntry[0].user.PSObject.Properties['token']) {
        $hasToken = -not [string]::IsNullOrWhiteSpace($userEntry[0].user.token)
    }
    "Nonempty static token configured: $hasToken"
}
```

Compare the server with the intended LAN endpoint and trusted cluster records.
Stop on an unexpected context, user, or server. The presence check does not
validate a token, and other kubeconfigs may use an exec credential plugin instead.
Do not substitute the Linux admin kubeconfig's loopback server.

Run these separately so you can see each result; nonzero `$LASTEXITCODE` means
failure. If prompted for `Username`, **Ctrl+C** cancels it: check for a missing
token, do not enter your Linux login as Kubernetes Basic auth.

```powershell
kubectl --kubeconfig "$kc" --context "$context" get namespaces --request-timeout=15s
kubectl --kubeconfig "$kc" --context "$context" get pods --all-namespaces --request-timeout=15s
kubectl --kubeconfig "$kc" --context "$context" auth can-i list pods --all-namespaces --request-timeout=15s
kubectl --kubeconfig "$kc" --context "$context" auth can-i watch pods --all-namespaces --request-timeout=15s
```

`Unauthorized` or missing authentication is different from `Forbidden` (an
authenticated identity lacks authorization). Successful namespace listing alone
does not prove pod access; pod listing alone does not prove all GUI permissions
(watch, events, logs, and other resources have separate permissions).
The name `lens-readonly` is **not** proof of read-only RBAC. Consult the existing
roles/bindings and intended access policy, without widening them for this repair.

If pods appear in CLI but not FreeLens, select **All Namespaces** or
`platform-lab` in the pod view, then reconnect the same entry if necessary.
The lab may show frontend/backend/postgres in `platform-lab`, `sample-service`
in existing `env-*` namespaces, and system workloads in `kube-system`; an empty
`default` namespace is not lost workloads. See the [decision table](#troubleshooting).

## Credential options

Both options authenticate as the **existing** `kube-system/lens-readonly`
ServiceAccount and inherit its same current permissions. Neither repairs RBAC.
Do not change roles/bindings, recreate the account, or assume its name limits it.
A LAN is not a security boundary.

| Option | Lifetime and renewal | Use/trade-off |
|---|---|---|
| **A: short-lived TokenRequest (preferred security choice)** | Request `--duration=1h` below. The server constrains the request and can issue a different actual lifetime. A static token saved in kubeconfig does **not** auto-refresh. | Renew deliberately when needed; prefer managed short-lived/refreshable authentication for ongoing use. A huge duration or `0` is not a promise of permanence. |
| **B: manually created service-account-token Secret** | No built-in time expiration or automatic rotation. Can still become invalid through revocation, signing-key rotation, account deletion/replacement, or cluster replacement. | Optional convenience for this disposable/local lab, **not recommended production best practice**. A stolen bearer credential can retain access until invalidated; protect and rotate it. |

See official [ServiceAccounts](https://kubernetes.io/docs/concepts/security/service-accounts/),
[administration and token invalidation](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/),
[manual token Secret guidance](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/),
and [`kubectl create token`](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_create/kubectl_create_token/).

## Linux terminal: administrator prerequisites

Work **locally in an interactive Bash terminal on the actual K3s server** as the
ordinary Linux login that Windows will use for transfer, with authorized sudo
access. Tools used below: K3s, Bash, GNU coreutils (`mktemp`, `base64`, `mv`), and
`ip`. Confirm the host/cluster before issuing credentials:

```bash
sudo -v
sudo k3s kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml \
  get namespace kube-system --request-timeout=15s
sudo k3s kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml \
  -n kube-system get serviceaccount lens-readonly --request-timeout=15s
ip -brief address
ip route
```

Stop on any failure. A missing namespace/account may mean the wrong host or
cluster; confirm it first. If the account really is absent, return to the
existing administrator-approved account setup/access record and infrastructure
runbook; this addendum intentionally supplies no new SA or RBAC bootstrap.

Identify the server's reachable LAN interface/address from the routes and
interface listing, not Docker bridges, CNI/pod networks, or loopback. Verify it
against the kubeconfig server and its TLS certificate; do not disable TLS checks.

An ordinary user's inability to read `/etc/rancher/k3s/k3s.yaml` is expected.
Use local sudo; **never `chmod 644` it, export its contents, or copy it to Windows**.
That [K3s admin kubeconfig](https://docs.k3s.io/cluster-access) grants unrestricted
cluster access, unlike the intended observer identity.

The shared transfer requires a running, reachable SSH server on Linux,
authorized SSH access, and Windows OpenSSH Client (`scp` and `ssh`). Verify the
host-key fingerprint through a trusted channel; do not bypass host-key checks.
If SSH is unavailable, stop: arrange an administrator-approved encrypted
transfer with authenticated endpoints and owner-only files, or establish SSH
through your normal host setup process before resuming. Do not use chat, email,
clipboard sharing, or a public file server as a substitute.

Do **not** capture `ssh ... "sudo ..."` stdout into a PowerShell token variable:
noninteractive sudo may fail to prompt, leaving an empty token; forcing a TTY
can mix prompts/output into credentials. Issue locally, transfer the file.
Disable shell tracing/transcription and avoid logging credentials throughout.

## Linux terminal: choose one issuance path

Each complete block below produces the **same** private transfer path
`$HOME/.freelens-transfer/token`, without printing its value. Run **A or B, not
both**. The parent directory is owner-only, and `umask 077` precedes all file
creation. An existing final file causes a stop, not overwrite: finish or
deliberately abandon the previous transfer first. If any command fails, **stop;
do not run Windows import**. The final file is published only after successful
issuance and a format sanity check (not proof of authentication).

### A. Short-lived TokenRequest

**Linux Bash**, interactive sudo; requested duration is not guaranteed lifetime:

```bash
(
set -euo pipefail
umask 077
k=(sudo k3s kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml --request-timeout=15s)
sudo -v
"${k[@]}" -n kube-system get serviceaccount lens-readonly >/dev/null
dir="$HOME/.freelens-transfer"
if [[ -L "$dir" || ( -e "$dir" && ! -d "$dir" ) ]]; then
  echo "Unsafe transfer directory; stop." >&2; exit 1
fi
mkdir -p -- "$dir"
chmod 700 -- "$dir"
if [[ -e "$dir/token" || -L "$dir/token" ]]; then
  echo "Previous transfer file exists; stop." >&2; exit 1
fi
tmp=$(mktemp "$dir/.pending.XXXXXXXX")
trap 'rm -f -- "$tmp"' EXIT
"${k[@]}" -n kube-system create token lens-readonly --duration=1h > "$tmp"
token=$(<"$tmp")
if [[ ! "$token" =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]; then
  echo "Blank or unexpected token format; stop." >&2; exit 1
fi
unset token
chmod 600 -- "$tmp"
mv -T -- "$tmp" "$dir/token"
echo "Token file ready; value not displayed. Continue to Windows installation."
)
```

### B. Persistent service-account-token Secret

**Linux Bash**, interactive sudo. This creates only the named Secret, not a
ServiceAccount or binding. It uses `create`, intentionally refusing to reuse an
existing Secret. Reapplying the same Secret is **not rotation**. If the name
already exists, stop and follow [rotation](#rotation-revocation-and-retention)
rather than deleting a working credential just to make this block succeed.

```bash
(
set -euo pipefail
umask 077
k=(sudo k3s kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml --request-timeout=15s)
sudo -v
"${k[@]}" -n kube-system get serviceaccount lens-readonly >/dev/null
dir="$HOME/.freelens-transfer"
if [[ -L "$dir" || ( -e "$dir" && ! -d "$dir" ) ]]; then
  echo "Unsafe transfer directory; stop." >&2; exit 1
fi
mkdir -p -- "$dir"
chmod 700 -- "$dir"
if [[ -e "$dir/token" || -L "$dir/token" ]]; then
  echo "Previous transfer file exists; stop." >&2; exit 1
fi
tmp=$(mktemp "$dir/.pending.XXXXXXXX")
trap 'rm -f -- "$tmp"' EXIT
"${k[@]}" create -f - <<'EOF'
apiVersion: v1
kind: Secret
metadata:
  name: lens-readonly-freelens-token
  namespace: kube-system
  annotations:
    kubernetes.io/service-account.name: lens-readonly
type: kubernetes.io/service-account-token
EOF
encoded=''
for attempt in {1..15}; do
  # API/authorization failures stop immediately, rather than looking like "not ready".
  encoded=$("${k[@]}" -n kube-system get secret lens-readonly-freelens-token \
    -o jsonpath='{.data.token}')
  [[ -n "$encoded" ]] && break
  sleep 2
done
if [[ -z "$encoded" ]]; then
  echo "Controller did not populate token; stop and inspect controller health." >&2
  exit 1
fi
printf '%s' "$encoded" | base64 --decode > "$tmp"
unset encoded
token=$(<"$tmp")
if [[ ! "$token" =~ ^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]; then
  echo "Blank or unexpected decoded token; stop." >&2; exit 1
fi
unset token
chmod 600 -- "$tmp"
mv -T -- "$tmp" "$dir/token"
echo "Token file ready; value not displayed. Continue to Windows installation."
)
```

The token controller populates `.data.token` asynchronously. The loop has 15
attempts, 2-second waits, and bounded API requests; it is not an unlimited wait.
On failure, check the named Secret's type, annotation and controller health with
the administrator; do not dump its data or repeatedly create Secrets. A failed
issuance may leave the newly created Secret behind: retain or explicitly revoke
that exact Secret after confirming whether anyone uses it.
**Do not add this Secret to the ServiceAccount's `.secrets` list**; that can
cause it to be classified as an auto-generated legacy token instead.

## Windows PowerShell: transfer and install

Use the same session/placeholders from triage, with Windows OpenSSH Client and
`kubectl` on PATH. Close/disconnect this FreeLens connection while updating its
file, to avoid concurrent writes. Confirm triage passed the expected
context/server/user checks; installation updates only `lens-readonly`'s token,
preserving server, CA, context, and other user fields.

Kubeconfigs **and backups are secrets**. The block creates private random
directories with inheritance disabled and access restricted to the current
Windows identity before writing credential files. It stops if ACL setup, scp,
format checking, or a native command fails. Run only on a trusted workstation.
`set-credentials --token` briefly exposes the token in a process command line
to sufficiently privileged local tools; do not run with process-argument
recording/transcription. If policy forbids this, use an approved credential
management workflow instead, not this block.

```powershell
& {
    Set-StrictMode -Version Latest
    $ErrorActionPreference = 'Stop'
    $tempDir = $null
    $token = $null
    function New-PrivateDirectory([string]$Parent, [string]$Prefix) {
        $path = Join-Path $Parent ($Prefix + [guid]::NewGuid().ToString('N'))
        $item = New-Item -ItemType Directory -Path $path
        $acl = Get-Acl -LiteralPath $item.FullName
        $acl.SetAccessRuleProtection($true, $false)
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
        $rule = [System.Security.AccessControl.FileSystemAccessRule]::new(
            $sid, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
        Set-Acl -LiteralPath $item.FullName -AclObject $acl
        return $item.FullName
    }
    try {
        foreach ($cmd in @('kubectl', 'scp', 'ssh')) {
            $null = Get-Command $cmd -CommandType Application -ErrorAction Stop
        }
        if (-not (Test-Path -LiteralPath $kc -PathType Leaf)) { throw 'Kubeconfig missing.' }
        $text = & kubectl --kubeconfig "$kc" config view -o json
        if ($LASTEXITCODE -ne 0) { throw 'Cannot read kubeconfig; stop.' }
        $cfg = ($text -join "`n") | ConvertFrom-Json
        $entry = @($cfg.contexts | Where-Object { $_.name -eq $context })
        $userEntry = @($cfg.users | Where-Object { $_.name -eq 'lens-readonly' })
        if ($cfg.'current-context' -ne $context -or $entry.Count -ne 1 -or
            $entry[0].context.user -ne 'lens-readonly' -or $userEntry.Count -ne 1) {
            throw 'Existing context/user mapping changed or missing; configuration unchanged.'
        }
        $tempDir = New-PrivateDirectory $env:TEMP 'freelens-transfer-'
        $temp = Join-Path $tempDir 'token'
        $remote = '{0}@{1}' -f $linuxUser, $linuxHost
        & scp "${remote}:.freelens-transfer/token" "$temp"
        if ($LASTEXITCODE -ne 0) { throw 'Transfer failed; configuration unchanged.' }
        $token = (Get-Content -LiteralPath $temp -Raw).Trim()
        if ($token -cnotmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$') {
            throw 'Blank or unexpected token format; configuration unchanged.'
        }
        # A private backup survives the finally block; record its path, not its contents.
        $backupDir = New-PrivateDirectory $env:LOCALAPPDATA 'freelens-backup-'
        $backup = Join-Path $backupDir 'kubeconfig'
        Copy-Item -LiteralPath $kc -Destination $backup
        "Protected backup: $backup"
        & kubectl --kubeconfig "$kc" config set-credentials lens-readonly --token="$token"
        if ($LASTEXITCODE -ne 0) { throw 'Kubeconfig update failed; retain backup.' }
        & kubectl --kubeconfig "$kc" --context "$context" get namespaces --request-timeout=15s
        if ($LASTEXITCODE -ne 0) { throw 'Namespace verification failed; retain Linux copy and backup.' }
        & kubectl --kubeconfig "$kc" --context "$context" get pods --all-namespaces --request-timeout=15s
        if ($LASTEXITCODE -ne 0) { throw 'Pod verification failed; retain Linux copy and backup.' }
        & ssh "$remote" 'rm -f -- "$HOME/.freelens-transfer/token"'
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Linux cleanup failed. Installation verified; delete Linux transfer file locally.'
        } else {
            'CLI checks passed; Linux transfer file removed.'
        }
    }
    finally {
        $token = $null
        if ($null -ne $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction Continue
        }
    }
}
```

`User "lens-readonly" set.` means **saved**, not authenticated. Stop on either
verification failure; do not claim success from that message. If kubectl prompts
for `Username`, cancel with Ctrl+C, inspect credential presence, and do not
continue to Linux cleanup. To undo an installation, close FreeLens and restore
the protected backup to the same `$kc` path only after checking the target and
whether the old credential is still usable. Do not blindly retry issuance.

The cleanup SSH connection can ask for the Linux login password **again**;
this is separate from sudo and from Kubernetes authentication. Let it finish
and check the result. If interrupted or warned, after verified installation run
locally on **Linux**:

```bash
rm -f -- "$HOME/.freelens-transfer/token"
```

Windows `finally` attempts cleanup even on failure; inspect any deletion error
and remove the reported private temporary directory manually if needed.
The Linux copy remains if installation/verification failed. Do not transfer it
later without confirming it is the intended, still-valid issuance.
Removing transfer files does **not** revoke the token saved in kubeconfig.

Reconnect the **same** FreeLens entry, choose All Namespaces or `platform-lab`,
and resume the [read-only checklist](freelens-operator-checklist.md).
If CLI lists pods but a GUI view is unavailable, inspect filtering and individual
list/watch/log permissions, not a new token or broader RBAC.

## Rotation, revocation, and retention

For a persistent token, prefer creating a **new, differently named Secret**
(for example `lens-readonly-freelens-token-next`): change both the manifest name
and the polling lookup in option B, install it, verify namespaces/pods and
FreeLens, then revoke the old Secret. Reapplying an existing Secret is not
rotation. For short-lived credentials, repeat A and verified installation before
expiry; deleting transfer files does not invalidate an issued TokenRequest.

**Destructive credential effect:** the following **Linux administrator command**
revokes the old persistent credential for **every client using that Secret's
token**, including saved configs/backups. Confirm the cluster, exact namespace,
Secret name, and migration of all affected clients before running it. It does
not delete workloads, the ServiceAccount, its RBAC, or tokens from other Secrets.
Do not delete the ServiceAccount as the default revocation procedure.

```bash
sudo k3s kubectl --kubeconfig /etc/rancher/k3s/k3s.yaml \
  -n kube-system delete secret lens-readonly-freelens-token --request-timeout=15s
```

If exposed, revoke promptly and install a replacement; coordinate disruption
with affected users. Retain backups only as long as your rollback/security policy
needs, under owner-only access; remove obsolete backups after verified rotation.
Keep the live kubeconfig protected too. File deletion (`rm`, Windows removal,
even `shred`) does not promise forensic erasure on SSDs, snapshots, or backups.
Never commit real kubeconfigs, tokens, populated Secret manifests, or admin
configs; never paste credentials into issues, chat, terminal recordings, or
screenshots.

## Troubleshooting

| Symptom | Distinction and next action |
|---|---|
| Linux user gets permission denied reading K3s admin config | Expected protection. Use authorized local `sudo k3s kubectl` with the explicit admin path. Do not relax file permissions or export admin credentials. |
| SSH command cannot prompt for sudo; captured token is empty | Remote noninteractive sudo failed, or TTY output contaminated capture. Stop; issue locally using A/B and transfer the private file. |
| Username prompt / blank token / `set-credentials` printed success | Ctrl+C. Saving is not authentication. Confirm exact FreeLens file, user/context mapping and nonempty token without printing it; scp/format failures must stop before a config write. |
| `kube-system` or `lens-readonly` not found | Check actual server, cluster/context and namespace first. Missing account requires the existing approved setup, not arbitrary SA creation/RBAC expansion. |
| `Unauthorized` / server asks for credentials | Missing, invalid, expired, or revoked credential, or wrong target. Verify file and endpoint, then consider issuance. Original incident's expiry was not proven. |
| `Forbidden` / `auth can-i` says no | Authentication may be valid but RBAC denies that specific action/scope. Review intended existing access with the administrator; do not rotate tokens or grant cluster-admin as a fix. |
| Timeout, connection refused, DNS or TLS error | Check reachable LAN endpoint, VPN/firewall/routing, server availability, trusted CA and certificate names/time. Token replacement cannot repair transport/TLS. Never use insecure-skip-TLS-verify. |
| CLI pods visible; GUI empty | Select All Namespaces or `platform-lab`; `default` may have no pods. Reconnect same entry, check exact backing file and relevant list/watch permissions. Do not delete/reimport first. |
| Short-lived token stops working later | Static kubeconfig does not renew. Expiry is possible; check issuance/lifetime and other invalidation causes, then renew deliberately. Persistent tokens are also revocable and not guaranteed forever. |
