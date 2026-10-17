"""Offline GitOps contract checks and an opt-in, externally driven rollback drill."""

import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import time
import unittest
import urllib.parse


ROOT = Path(__file__).resolve().parents[2]
GITOPS = ROOT / "infra/gitops"


def render(path):
    return subprocess.check_output(
        ["kubectl", "kustomize", str(path)], text=True, timeout=120)


@unittest.skipUnless(shutil.which("kubectl"), "kubectl is required to render Kustomize")
class GitOpsConfigurationTests(unittest.TestCase):
    def test_target_excludes_bootstrap_and_environment_resources(self):
        rendered = render(GITOPS / "local-k3s")
        self.assertEqual(3, rendered.count("kind: Deployment\n"))
        self.assertEqual(3, rendered.count("kind: Service\n"))
        for kind in ("Namespace", "PersistentVolumeClaim", "Secret", "ServiceAccount",
                     "ClusterRole", "ClusterRoleBinding", "Application"):
            self.assertNotIn(f"kind: {kind}\n", rendered)
        self.assertNotIn("env-", rendered)
        self.assertEqual(2, rendered.count("type: Recreate"))
        self.assertEqual(2, rendered.count("imagePullPolicy: Never"))
        self.assertIn("Prune=false,Delete=false", rendered)

    def test_bootstrap_owns_storage_and_worker_but_not_deployments(self):
        rendered = render(GITOPS / "bootstrap")
        for kind in ("Namespace", "PersistentVolumeClaim", "ServiceAccount",
                     "ClusterRole", "ClusterRoleBinding", "AppProject", "Application"):
            self.assertIn(f"kind: {kind}\n", rendered)
        self.assertNotRegex(rendered, r"(?m)^kind: Deployment$")
        self.assertNotRegex(rendered, r"(?m)^kind: Service$")
        self.assertIn("storage: 5Gi", rendered)
        self.assertIn("storageClassName: local-path", rendered)

    def test_reconciliation_and_permissions_are_bounded(self):
        rendered = render(GITOPS / "bootstrap")
        self.assertIn("path: infra/gitops/local-k3s", rendered)
        self.assertIn("targetRevision: main", rendered)
        self.assertIn("prune: false", rendered)
        self.assertIn("selfHeal: true", rendered)
        self.assertNotIn("resources-finalizer", rendered)
        self.assertNotIn("CreateNamespace", rendered)
        application = (GITOPS / "bootstrap/application.yaml").read_text()
        self.assertIn("clusterResourceWhitelist: []", application)
        for kind in ("Secret", "Namespace", "PersistentVolumeClaim", "Role", "ClusterRole"):
            self.assertNotIn(f"kind: {kind}\n", application)
        access = (GITOPS / "bootstrap/access.yaml").read_text()
        role = access.split("---", 1)[0]
        for denied in ("delete", "secrets", "persistentvolumeclaims", "namespaces", "*"):
            self.assertNotIn(denied, role)
        self.assertIn("namespace: platform-lab", role)
        self.assertIn('clusterResources: "false"', access)

    def test_controller_is_commit_pinned_and_namespaced(self):
        text = (GITOPS / "controller/kustomization.yaml").read_text()
        self.assertIn("a5038b0e2bbac1715754380ae0084df8e3d5b48c", text)
        self.assertIn("/manifests/namespace-install.yaml", text)
        self.assertNotIn("/manifests/install.yaml", text)
        self.assertIn("resource.respectRBAC: strict", text)


@unittest.skipUnless(os.environ.get("PLATFORM_GITOPS_TEST") == "true",
                     "set PLATFORM_GITOPS_TEST=true for the externally driven rollback drill")
class GitOpsRollbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        required = ("PLATFORM_K3S_CONTEXT", "PLATFORM_K3S_API_URL", "PLATFORM_K3S_CA_FILE",
                    "PLATFORM_K3S_OPERATOR_USERNAME", "PLATFORM_K3S_OPERATOR_PASSWORD",
                    "PLATFORM_GITOPS_ENVIRONMENT_ID", "PLATFORM_GITOPS_EVIDENCE")
        if any(not os.environ.get(name) for name in required):
            raise RuntimeError("GitOps drill requires explicit cluster, HTTPS, credentials, fixture and evidence path")
        cls.context = os.environ["PLATFORM_K3S_CONTEXT"]
        cls.url = urllib.parse.urlsplit(os.environ["PLATFORM_K3S_API_URL"])
        if (cls.url.scheme != "https" or not cls.url.hostname or cls.url.username
                or cls.url.password or cls.url.path not in ("", "/") or cls.url.query or cls.url.fragment):
            raise RuntimeError("PLATFORM_K3S_API_URL must be an HTTPS origin without credentials")
        cls.tls = ssl.create_default_context(cafile=os.environ["PLATFORM_K3S_CA_FILE"])
        cls.authorization = "Basic " + base64.b64encode(
            (os.environ["PLATFORM_K3S_OPERATOR_USERNAME"] + ":" +
             os.environ["PLATFORM_K3S_OPERATOR_PASSWORD"]).encode()).decode()
        cls.environment_id = os.environ["PLATFORM_GITOPS_ENVIRONMENT_ID"]
        if not re.fullmatch(r"[0-9a-f-]{36}", cls.environment_id):
            raise RuntimeError("Fixture must be an environment UUID")
        cls.evidence = Path(os.environ["PLATFORM_GITOPS_EVIDENCE"]).resolve()
        if not cls.evidence.is_relative_to(Path("/tmp")) or cls.evidence.is_relative_to(ROOT):
            raise RuntimeError("Evidence must be outside the checkout, under /tmp")
        cls.releases = json.loads((GITOPS / "local-k3s/releases.json").read_text())

    def kubectl(self, *arguments):
        return subprocess.check_output(
            ["kubectl", "--context", self.context, *arguments], text=True, timeout=30)

    def resource(self, kind, name, namespace="platform-lab"):
        return json.loads(self.kubectl("-n", namespace, "get", kind, name, "-o", "json"))

    def request(self, path, authenticated=True):
        connection = http.client.HTTPSConnection(
            self.url.hostname, self.url.port or 443, context=self.tls, timeout=15)
        try:
            connection.request("GET", path,
                               headers={"Authorization": self.authorization} if authenticated else {})
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            return json.loads(response.read())
        finally:
            connection.close()

    def application_at(self, digest, previous_revision=None):
        application = self.resource("application", "platform-local", "argocd")
        spec = application["spec"]
        self.assertEqual("infra/gitops/local-k3s", spec["source"]["path"])
        self.assertEqual("https://github.com/dcfaight/developer-platform-lab.git",
                         spec["source"]["repoURL"])
        self.assertNotIn("kustomize", spec["source"], "No out-of-Git image overrides allowed")
        status = application.get("status", {})
        revision = status.get("sync", {}).get("revision", "")
        operation = status.get("operationState", {})
        if (status.get("sync", {}).get("status") != "Synced"
                or status.get("health", {}).get("status") != "Healthy"
                or operation.get("phase") != "Succeeded"
                or operation.get("syncResult", {}).get("revision") != revision
                or not re.fullmatch(r"[0-9a-f]{40}", revision)
                or revision == previous_revision):
            return None
        deployment = self.resource("deployment", "frontend")
        if deployment["spec"]["template"]["spec"]["containers"][0]["image"] != digest:
            return None
        generation = deployment["metadata"]["generation"]
        if (deployment.get("status", {}).get("observedGeneration", 0) < generation
                or deployment["status"].get("updatedReplicas") != 1
                or deployment["status"].get("availableReplicas") != 1):
            return None
        pods = json.loads(self.kubectl("-n", "platform-lab", "get", "pods", "-l",
                                      "app=frontend", "-o", "json"))["items"]
        active = [pod for pod in pods if not pod["metadata"].get("deletionTimestamp")]
        if len(active) != 1:
            return None
        containers = active[0].get("status", {}).get("containerStatuses", [])
        if len(containers) != 1 or not containers[0].get("ready"):
            return None
        image_id = containers[0].get("imageID", "")
        self.assertIn(digest.split("@", 1)[1], image_id)
        return {"gitRevision": revision, "image": digest, "imageID": image_id,
                "frontendUID": deployment["metadata"]["uid"]}

    def wait_for_release(self, release, previous_revision=None):
        deadline = time.monotonic() + 1200
        print(f"Waiting for Git reconciliation of {release['releaseId']}", flush=True)
        while time.monotonic() < deadline:
            observed = self.application_at(release["image"], previous_revision)
            if observed:
                identity = self.request("/release.json", authenticated=False)
                if identity == {"sourceRevision": release["sourceRevision"],
                                "releaseId": release["releaseId"]}:
                    return observed
            time.sleep(5)
        self.fail("Timed out waiting for a healthy Git-sourced release; inspect Argo CD")

    def persistence(self):
        path = "/api/v1/environments/" + self.environment_id
        environment = self.request(path)
        self.assertEqual("READY", environment["status"])
        history = self.request(path + "/operations")
        readiness = json.loads(self.kubectl(
            "-n", "platform-lab", "exec", "deployment/backend", "--",
            "curl", "--fail", "--silent", "http://127.0.0.1:8080/actuator/health/readiness"))
        self.assertEqual("UP", readiness["status"])
        backend = self.resource("deployment", "backend")
        postgres = self.resource("deployment", "postgres")
        pods = json.loads(self.kubectl("-n", "platform-lab", "get", "pods", "-o", "json"))["items"]
        schema = self.kubectl("-n", "platform-lab", "exec", "deployment/postgres", "--",
                              "sh", "-c", 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At '
                              '-c "SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank"')
        return {
            "environment": environment, "history": history, "schema": schema,
            "backendSpec": backend["spec"], "postgresSpec": postgres["spec"],
            "backendUID": backend["metadata"]["uid"], "postgresUID": postgres["metadata"]["uid"],
            "pvcUID": self.resource("pvc", "postgres-data")["metadata"]["uid"],
            "secretUIDs": {name: self.resource("secret", name)["metadata"]["uid"]
                           for name in ("platform-secrets", "platform-tls")},
            "dataPods": sorted((pod["metadata"]["uid"],
                                [(c["name"], c["restartCount"]) for c in
                                 pod.get("status", {}).get("containerStatuses", [])])
                               for pod in pods if pod["metadata"].get("labels", {}).get("app")
                               in ("backend", "postgres")),
        }

    def test_git_upgrade_and_revert_preserve_backend_and_history(self):
        previous, candidate = self.releases["frontend"]
        baseline = self.wait_for_release(previous)
        preserved = self.persistence()
        records = [{"phase": "baseline", **baseline}]
        # The test never writes Git, patches the Application, or changes workloads.
        for phase, release in (("upgrade", candidate), ("rollback", previous)):
            observed = self.wait_for_release(release, records[-1]["gitRevision"])
            self.assertNotIn(observed["gitRevision"], [r["gitRevision"] for r in records],
                             "Rollback must be a new Git revert commit")
            self.assertEqual(preserved, self.persistence())
            records.append({"phase": phase, **observed})
        self.evidence.write_text(json.dumps({
            "result": "PASS", "observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "context": self.context, "phases": records,
            "preservedStateSHA256": hashlib.sha256(
                json.dumps(preserved, sort_keys=True).encode()).hexdigest(),
            "checks": ["API readiness", "READY environment and operation history", "Flyway history",
                       "backend/PostgreSQL specs, UIDs, pod UIDs and restart counts",
                       "PVC and external Secret UIDs", "served release identity", "runtime image digest"],
        }, indent=2) + "\n")
