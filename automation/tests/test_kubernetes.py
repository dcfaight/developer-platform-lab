"""K3s packaging checks; live checks require an explicitly selected cluster."""

import base64
import http.client
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import time
import unittest
import urllib.parse
import uuid


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
            "image: platform-lab-backend:k3s-v2",
            "image: platform-lab-frontend:k3s-v1",
            "key: operator-password-hash",
            "secretName: platform-tls",
        ):
            self.assertIn(expected, rendered)
        self.assertEqual(4, rendered.count("automountServiceAccountToken: false"))
        self.assertNotIn("kind: Secret\n", rendered)
        self.assertIn("kind: ClusterRole\n", rendered)
        self.assertIn("kind: ClusterRoleBinding\n", rendered)
        self.assertIn("serviceAccountName: platform-worker", rendered)
        self.assertIn("mountPath: /var/run/secrets/platform-worker", rendered)
        self.assertIn("expirationSeconds: 3600", rendered)
        self.assertIn("name: PLATFORM_KUBERNETES_MODE\n          value: in-cluster", rendered)
        self.assertNotIn("hostPath:", rendered)
        self.assertNotIn("127.0.0.11", rendered)

    def test_worker_permissions_are_explicit_and_do_not_grant_secret_or_rbac_access(self):
        rbac = (MANIFESTS / "worker-rbac.yaml").read_text()
        rules = rbac.split("rules:\n", 1)[1].split("---", 1)[0]
        self.assertEqual(
            '  - apiGroups: [""]\n'
            '    resources: [namespaces]\n'
            '    verbs: [get, create, delete]\n'
            '  - apiGroups: [apps]\n'
            '    resources: [deployments]\n'
            '    verbs: [get, create, update]\n'
            '  - apiGroups: [""]\n'
            '    resources: [limitranges, resourcequotas]\n'
            '    verbs: [get, create, update]\n', rules)
        self.assertEqual(1, rbac.count("rules:"))


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
        self.assertEqual("true", environment["PLATFORM_WORKER_ENABLED"]["value"])
        self.assertEqual("in-cluster", environment["PLATFORM_KUBERNETES_MODE"]["value"])
        self.assertEqual("platform-worker", backend["spec"]["template"]["spec"]["serviceAccountName"])

    def test_worker_rbac_allows_only_required_resource_operations(self):
        identity = "system:serviceaccount:platform-lab:platform-worker"
        for verb, resource, namespace, allowed in (
            ("get", "namespaces", None, True),
            ("create", "namespaces", None, True),
            ("delete", "namespaces", None, True),
            ("get", "deployments.apps", "worker-rbac-check", True),
            ("create", "deployments.apps", "worker-rbac-check", True),
            ("update", "deployments.apps", "worker-rbac-check", True),
            ("get", "limitranges", "worker-rbac-check", True),
            ("create", "limitranges", "worker-rbac-check", True),
            ("update", "limitranges", "worker-rbac-check", True),
            ("get", "resourcequotas", "worker-rbac-check", True),
            ("create", "resourcequotas", "worker-rbac-check", True),
            ("update", "resourcequotas", "worker-rbac-check", True),
            ("get", "secrets", "platform-lab", False),
            ("create", "pods/exec", "platform-lab", False),
            ("create", "rolebindings.rbac.authorization.k8s.io", "platform-lab", False),
            ("update", "namespaces", None, False),
            ("list", "namespaces", None, False),
            ("delete", "deployments.apps", "worker-rbac-check", False),
            ("watch", "deployments.apps", "worker-rbac-check", False),
            ("delete", "limitranges", "worker-rbac-check", False),
            ("delete", "resourcequotas", "worker-rbac-check", False),
        ):
            with self.subTest(verb=verb, resource=resource):
                arguments = ["kubectl", "--context", self.context, "auth", "can-i",
                             verb, resource, "--as", identity]
                if namespace:
                    arguments.extend(["--namespace", namespace])
                result = subprocess.run(arguments, capture_output=True, text=True, timeout=30)
                self.assertEqual("yes" if allowed else "no", result.stdout.strip(), result.stderr)

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


@unittest.skipUnless(os.environ.get("PLATFORM_K3S_LIFECYCLE_TEST") == "true",
                     "set PLATFORM_K3S_LIFECYCLE_TEST=true for authenticated, mutating lifecycle checks")
class KubernetesLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        required = ("PLATFORM_K3S_CONTEXT", "PLATFORM_K3S_API_URL",
                    "PLATFORM_K3S_CA_FILE", "PLATFORM_K3S_OPERATOR_USERNAME",
                    "PLATFORM_K3S_OPERATOR_PASSWORD")
        if any(not os.environ.get(name) for name in required):
            raise RuntimeError("Lifecycle checks require explicit context, HTTPS URL, CA and operator credentials")
        cls.context = os.environ["PLATFORM_K3S_CONTEXT"]
        cls.url = urllib.parse.urlsplit(os.environ["PLATFORM_K3S_API_URL"])
        if (cls.url.scheme != "https" or not cls.url.hostname or cls.url.username
                or cls.url.password or cls.url.path not in ("", "/")
                or cls.url.query or cls.url.fragment):
            raise RuntimeError("PLATFORM_K3S_API_URL must be an HTTPS origin without credentials")
        cls.tls = ssl.create_default_context(cafile=os.environ["PLATFORM_K3S_CA_FILE"])
        cls.authorization = "Basic " + base64.b64encode(
            (os.environ["PLATFORM_K3S_OPERATOR_USERNAME"] + ":" +
             os.environ["PLATFORM_K3S_OPERATOR_PASSWORD"]).encode()).decode()

    def request(self, path, method="GET", body=None, authenticated=False, headers=None):
        headers = dict(headers or {})
        if authenticated:
            headers["Authorization"] = self.authorization
        if body is not None:
            body = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        connection = http.client.HTTPSConnection(
            self.url.hostname, self.url.port or 443, context=self.tls, timeout=15)
        try:
            # HTTPSConnection neither follows redirects nor uses ambient proxies.
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            self.assertNotIn(response.status, (301, 302, 303, 307, 308))
            return response.status, json.loads(payload) if payload else None
        finally:
            connection.close()

    def get(self, path):
        status, body = self.request(path)
        self.assertEqual(200, status)
        return body

    def wait(self, operation_id):
        deadline = time.monotonic() + 420
        while time.monotonic() < deadline:
            operation = self.get("/api/v1/operations/" + operation_id)
            if operation["status"] in ("SUCCEEDED", "FAILED"):
                self.assertEqual("SUCCEEDED", operation["status"],
                                 f"Operation {operation_id}: {operation.get('failureCode')}")
                self.assertTrue(operation["events"])
                self.assertIsNotNone(operation["completedAt"])
                return operation
            time.sleep(3)
        self.fail(f"Operation {operation_id} did not complete; inspect its durable history")

    def kubectl(self, *arguments):
        return subprocess.check_output(
            ["kubectl", "--context", self.context, *arguments], text=True, timeout=30)

    def cleanup_environment(self, environment_id):
        path = "/api/v1/environments/" + environment_id
        environment = self.get(path)
        if environment["status"] == "DELETING":
            self.wait(environment["latestOperation"]["id"])
        elif environment["status"] in ("READY", "FAILED", "DELETE_FAILED"):
            status, deletion = self.request(path, "DELETE", authenticated=True)
            self.assertEqual(202, status)
            self.wait(deletion["operationId"])
        elif environment["status"] != "DELETED":
            print(f"Environment {environment_id} retained for diagnosis; use API deletion after creation finishes")

    def test_authenticated_create_readiness_history_and_owned_namespace_deletion(self):
        payload = {"name": "K3s lifecycle test", "templateId": "sample-service", "ttlHours": 1}
        key = "k3s-lifecycle-" + uuid.uuid4().hex
        headers = {"Idempotency-Key": key}
        self.assertEqual(401, self.request("/api/v1/environments", "POST", payload,
                                          headers=headers)[0])
        status, accepted = self.request("/api/v1/environments", "POST", payload,
                                        authenticated=True, headers=headers)
        self.assertEqual(202, status)
        environment_id = accepted["environmentId"]
        self.addCleanup(self.cleanup_environment, environment_id)
        self.assertEqual("REQUESTED", accepted["environmentStatus"])
        self.assertEqual("PENDING", accepted["operationStatus"])
        self.assertEqual((202, accepted), self.request(
            "/api/v1/environments", "POST", payload, authenticated=True, headers=headers))
        creation = self.wait(accepted["operationId"])
        self.assertEqual("READY", creation["progressStep"])
        environment = self.get("/api/v1/environments/" + environment_id)
        self.assertEqual("READY", environment["status"])
        namespace = "env-" + environment_id.replace("-", "")
        self.assertEqual(namespace, environment["namespace"])
        deployment = json.loads(self.kubectl(
            "-n", namespace, "get", "deployment", "sample-service", "-o", "json"))
        self.assertGreaterEqual(deployment["status"]["observedGeneration"],
                                deployment["metadata"]["generation"])
        self.assertEqual(1, deployment["status"]["availableReplicas"])
        owned_namespace = json.loads(self.kubectl("get", "namespace", namespace, "-o", "json"))
        for metadata in ("labels", "annotations"):
            self.assertEqual(environment_id,
                             owned_namespace["metadata"][metadata]["platform-lab/environment-id"])
            self.assertEqual(accepted["operationId"],
                             owned_namespace["metadata"][metadata]["platform-lab/create-operation-id"])
        path = "/api/v1/environments/" + environment_id
        status, deletion = self.request(path, "DELETE", authenticated=True)
        self.assertEqual(202, status)
        self.assertEqual("DELETED", self.wait(deletion["operationId"])["progressStep"])
        self.assertEqual("DELETED", self.get(path)["status"])
        history = self.get(path + "/operations")
        self.assertEqual(2, history["totalElements"])
        self.assertTrue(all(operation["status"] == "SUCCEEDED" for operation in history["items"]))
        self.assertEqual("", self.kubectl("get", "namespace", namespace, "--ignore-not-found", "-o", "name").strip())
