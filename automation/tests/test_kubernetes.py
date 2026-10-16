"""K3s packaging checks; live checks require an explicitly selected cluster."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
MANIFESTS = ROOT / "infra/kubernetes"


@unittest.skipUnless(shutil.which("kubectl"), "kubectl is required to render Kustomize")
class KubernetesConfigurationTests(unittest.TestCase):
    def test_render_includes_platform_resources_and_local_images(self):
        rendered = subprocess.check_output(
            ["kubectl", "kustomize", str(MANIFESTS)], text=True)
        self.assertEqual(3, rendered.count("kind: Deployment\n"))
        self.assertEqual(3, rendered.count("kind: Service\n"))
        for expected in (
            "kind: PersistentVolumeClaim\n",
            "storageClassName: local-path",
            "kind: Ingress\n",
            "ingressClassName: traefik",
            "image: platform-lab-backend:k3s-v1",
            "image: platform-lab-frontend:k3s-v1",
            "key: operator-password-hash",
            "secretName: platform-tls",
        ):
            self.assertIn(expected, rendered)
        self.assertEqual(3, rendered.count("automountServiceAccountToken: false"))
        self.assertNotIn("kind: Secret\n", rendered)
        self.assertNotIn("kind: ClusterRole", rendered)
        self.assertNotIn("127.0.0.11", rendered)


@unittest.skipUnless(os.environ.get("PLATFORM_K3S_TEST") == "true",
                     "set PLATFORM_K3S_TEST=true and PLATFORM_K3S_CONTEXT for live checks")
class KubernetesSmokeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.context = os.environ.get("PLATFORM_K3S_CONTEXT")
        if not cls.context:
            raise RuntimeError("PLATFORM_K3S_CONTEXT must explicitly select the K3s cluster")

    def kubectl(self, *arguments):
        return subprocess.check_output([
            "kubectl", "--context", self.context, "--namespace", "platform-lab",
            *arguments,
        ], text=True, timeout=60)

    def test_deployments_available_and_database_persistent(self):
        for name in ("postgres", "backend", "frontend"):
            deployment = json.loads(self.kubectl("get", "deployment", name, "-o", "json"))
            self.assertEqual(1, deployment["status"].get("availableReplicas"))
            self.assertFalse(deployment["spec"]["template"]["spec"]["automountServiceAccountToken"])
        claim = json.loads(self.kubectl("get", "pvc", "postgres-data", "-o", "json"))
        self.assertEqual("Bound", claim["status"]["phase"])
        backend = json.loads(self.kubectl("get", "deployment", "backend", "-o", "json"))
        environment = {entry["name"]: entry for entry in
                       backend["spec"]["template"]["spec"]["containers"][0]["env"]}
        self.assertEqual("deploy", environment["SPRING_PROFILES_ACTIVE"]["value"])
        self.assertEqual("false", environment["PLATFORM_WORKER_ENABLED"]["value"])

    def test_frontend_routes_to_api_and_keeps_management_private(self):
        # Read-only requests over the cluster-internal listener; never send credentials.
        body = self.kubectl("exec", "deployment/frontend", "--", "wget", "-qO-",
                            "http://127.0.0.1:8080/")
        self.assertIn('<div id="root">', body)
        environments = self.kubectl("exec", "deployment/frontend", "--", "wget", "-qO-",
                                    "http://127.0.0.1:8080/api/v1/environments")
        self.assertIsInstance(json.loads(environments)["items"], list)
        readiness = self.kubectl("exec", "deployment/backend", "--", "curl", "--fail",
                                 "--silent", "http://127.0.0.1:8080/actuator/health/readiness")
        self.assertEqual("UP", json.loads(readiness)["status"])
        status = self.kubectl("exec", "deployment/backend", "--", "curl", "--silent",
                              "--output", "/dev/null", "--write-out", "%{http_code}",
                              "http://frontend/actuator/health/readiness")
        self.assertEqual("404", status)
