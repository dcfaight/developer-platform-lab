"""Offline safety tests. No configured API, credentials, or cluster is ever used."""

from contextlib import contextmanager, redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from uuid import uuid4


AUTOMATION = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(AUTOMATION))
try:
    SPEC = importlib.util.spec_from_file_location("capacity_under_test", AUTOMATION / "capacity_baseline.py")
    capacity = importlib.util.module_from_spec(SPEC)
    SPEC.loader.exec_module(capacity)
finally:
    sys.path.pop(0)

STAMP = "2026-10-17T10:00:00Z"
USER = "baseline-operator"
CREDENTIALS = {
    "PLATFORM_CAPACITY_BASELINE": "true",
    "PLATFORM_BENCHMARK_USERNAME": USER,
    "PLATFORM_BENCHMARK_PASSWORD": "offline-test-only",
}


def options(directory):
    return SimpleNamespace(
        api_base_url=("http", "127.0.0.1", 8080), context="disposable",
        platform_namespace="platform-lab", artifacts=directory,
        cache_condition="unknown", worker_host="cluster", worker_enabled=None,
        worker_poll_delay_ms=None, overall_timeout=40, lifecycle_timeout=20,
        cleanup_timeout=5, request_timeout=1, poll_interval=0.01, sample_interval=0.02,
    )


def metric_snapshot(enabled=True):
    return {"generatedAt": STAMP, "workerEnabled": enabled,
            "operations": [
                {"type": kind, "pending": 0, "running": 0, "succeeded": 900, "failed": 0,
                 "averageQueueSeconds": 9999,
                 "succeededDuration": {"count": 900, "averageSeconds": 123,
                                       "p95Seconds": 456, "maxSeconds": 789},
                 "failureMessage": "DO_NOT_PERSIST"}
                for kind in (capacity.CREATE, capacity.DELETE)],
            "worker": {"dueOperations": 0, "expiredLeases": 0, "oldestDueAt": None, "lastEventAt": STAMP},
            "failureCodes": [], "failureMessage": "DO_NOT_PERSIST"}


class Clock:
    def __init__(self):
        self.now = 0

    def __call__(self):
        return self.now

    def sleep(self, duration):
        self.now += duration


class FakeAPI:
    def __init__(self, journal):
        self.journal = journal
        self.lock = threading.RLock()
        self.rows = {}
        self.operations = {}
        self.posts, self.deletes, self.calls = [], [], []
        self.active = self.maximum = 0
        self.create_status = "SUCCEEDED"
        self.delete_status = "SUCCEEDED"
        self.name_override = None
        self.creator_override = None
        self.template_override = None
        self.namespace_override = None
        self.operation_environment_override = None
        self.operation_type_override = None
        self.post_failures = 0
        self.partial_acceptance = False
        self.ambiguous_delete = False
        self.delete_missing_latest = False
        self.inject_create_error = False
        self.delay = 0
        self.pre_mutation = None
        self.worker_enabled = True

    def call(self, method, path, deadline, row=None, body=None, key=None, attempt=1):
        with self.lock:
            self.calls.append((method, path))
            if method == "GET" and path == "/operations/metrics":
                return metric_snapshot(self.worker_enabled)
            if method == "POST":
                if self.pre_mutation:
                    self.pre_mutation(method, row)
                self.posts.append((key, dict(body)))
                identity = next((i for i, value in self.rows.items() if value["name"] == body["name"]), None)
                if identity is None:
                    identity, operation = str(uuid4()), str(uuid4())
                    self.rows[identity] = {
                        "id": identity, "name": body["name"], "namespace": "env-" + identity.replace("-", ""),
                        "templateId": "sample-service", "createdBy": USER, "status": "READY",
                        "latestOperation": {"id": operation, "type": capacity.CREATE,
                                            "status": self.create_status},
                    }
                    self.operations[operation] = (identity, capacity.CREATE)
                    self.active += 1
                    self.maximum = max(self.maximum, self.active)
                operation = self.rows[identity]["latestOperation"]["id"]
                if self.post_failures:
                    self.post_failures -= 1
                    raise capacity.Failure("TRANSPORT")
                return {"environmentId": identity,
                        "operationId": "invalid" if self.partial_acceptance else operation,
                        "environmentStatus": "REQUESTED", "operationStatus": "PENDING"}
            if method == "DELETE":
                if self.pre_mutation:
                    self.pre_mutation(method, row)
                identity = path.split("/")[-1]
                self.deletes.append(identity)
                operation = str(uuid4())
                self.operations[operation] = (identity, capacity.DELETE)
                self.rows[identity]["status"] = "DELETED"
                self.rows[identity]["latestOperation"] = {
                    "id": operation, "type": capacity.DELETE, "status": self.delete_status}
                if self.delete_missing_latest:
                    self.rows[identity]["latestOperation"] = None
                if self.ambiguous_delete:
                    raise capacity.Failure("TRANSPORT")
                return {"environmentId": identity, "operationId": operation,
                        "environmentStatus": "DELETING", "operationStatus": "PENDING"}
            if path.startswith("/operations/"):
                identity, kind = self.operations[path.split("/")[-1]]
                if kind == capacity.CREATE and self.inject_create_error:
                    self.inject_create_error = False
                    raise capacity.Failure("TRANSPORT")
                return {"id": path.split("/")[-1],
                        "environmentId": self.operation_environment_override or identity,
                        "type": self.operation_type_override or kind,
                        "status": self.create_status if kind == capacity.CREATE else self.delete_status,
                        "createdAt": STAMP, "startedAt": "2026-10-17T10:00:02Z",
                        "completedAt": "2026-10-17T10:00:07Z",
                        "failureMessage": "DO_NOT_PERSIST", "events": [{"message": "DO_NOT_PERSIST"}]}
            value = dict(self.rows[path.split("/")[-1]])
            for field, override in (
                ("name", self.name_override), ("createdBy", self.creator_override),
                ("templateId", self.template_override), ("namespace", self.namespace_override),
            ):
                if override:
                    value[field] = override
            return value


class FakeKube:
    def __init__(self, api):
        self.api = api
        self.absences, self.samples = [], []
        self.observations, self.uids = [], {}
        self.observation_failure = None
        self.forbidden = self.still_present = False
        self.absence_delay = 0

    def absent(self, ns, deadline):
        if self.forbidden:
            raise capacity.Failure("KUBE_UNAVAILABLE")
        if self.still_present:
            return False
        if self.absence_delay:
            time.sleep(self.absence_delay)
        with self.api.lock:
            self.absences.append(ns)
            self.api.active -= 1
        return True

    def observe(self, row, deadline):
        if self.forbidden or self.observation_failure:
            raise capacity.Failure(self.observation_failure or "KUBE_UNAVAILABLE")
        identity = row["environmentId"]
        self.observations.append(identity)
        self.uids.setdefault(identity, str(uuid4()))
        return self.uids[identity]

    def top(self, ns, deadline):
        self.samples.append(ns)
        return {"namespace": ns, "status": "UNAVAILABLE"}


class BaselineTests(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory(prefix="capacity-test-", dir="/tmp")
        self.addCleanup(fixture.cleanup)
        self.directory = Path(fixture.name)
        self.journal = capacity.Journal(self.directory)
        self.args = options(self.directory)
        self.api = FakeAPI(self.journal)
        self.kube = FakeKube(self.api)
        self.baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal)

    @contextmanager
    def offline_context(self):
        with patch.object(capacity, "collect_context", return_value={"status": "OFFLINE_TEST"}):
            yield

    def test_complete_fixed_profile_including_cleanup_slots_and_measurements(self):
        self.kube.absence_delay = 0.01
        with self.offline_context():
            self.assertEqual(self.baseline.run(), 0)
        self.assertEqual(len(self.api.posts), 10)
        self.assertEqual(len(self.api.deletes), 10)
        self.assertLessEqual(self.api.maximum, 2)
        self.assertEqual(len(self.kube.absences), 10)
        self.assertEqual(len(self.kube.observations), 10)
        data = self.journal.data
        self.assertEqual(data["summary"]["successFraction"], 1)
        self.assertEqual(data["summary"]["planned"], 10)
        self.assertGreater(data["summary"]["completedPerElapsedMinute"], 0)
        for row in data["workflows"]:
            self.assertFalse(row["leftover"])
            self.assertEqual(row["namespaceUid"], self.kube.uids[row["environmentId"]])
            self.assertEqual(row["readyResourceSample"]["namespace"],
                             "env-" + row["environmentId"].replace("-", ""))
            self.assertEqual(row["readyResourceSample"]["status"], "UNAVAILABLE")
            self.assertEqual(row[capacity.CREATE]["queueWaitSeconds"], 2)
            self.assertEqual(row[capacity.CREATE]["executionSeconds"], 5)
            self.assertEqual(row[capacity.DELETE]["acceptanceToTerminalSeconds"], 7)
            self.assertLessEqual(row["readyObservedSeconds"], row["deletedObservedSeconds"])
            self.assertLessEqual(row["readyObservedSeconds"], row["namespaceObservedSeconds"])
            self.assertLessEqual(row["namespaceObservedSeconds"], row["deletedObservedSeconds"])
            self.assertLessEqual(row["deletedObservedSeconds"], row["namespaceAbsentSeconds"])
            self.assertLessEqual(row["namespaceAbsentSeconds"], row["completedObservedSeconds"])
        self.assertNotIn("DO_NOT_PERSIST", (self.journal.directory / "manifest.json").read_text())
        self.assertNotIn(USER, (self.journal.directory / "manifest.json").read_text())
        text = (self.journal.directory / "summary.txt").read_text()
        self.assertIn("context=disposable platform_namespace=platform-lab", text)
        self.assertIn("api_scheme=http api_host=127.0.0.1 api_port=8080", text)
        self.assertIn("workload=10 template=sample-service concurrency=2 including_cleanup=true", text)
        self.assertIn("resources_ready available=0 unavailable=10 not_collected=0", text)
        self.assertIn("CREATE_ENVIRONMENT.queueWaitSeconds=2.000", text)
        self.assertIn("failure_records=0 codes=none", text)
        self.assertNotIn(USER, text)
        self.assertNotIn("DO_NOT_PERSIST", text)

    def test_human_failure_summary_has_safe_context_timings_codes_and_all_leftover_ids(self):
        row = self.journal.data["workflows"][0]
        environment, other, create_op, delete_op = (str(uuid4()) for _ in range(4))
        row.update(leftover=True, success=False, createIntentAt=STAMP,
                   environmentId=environment, createOperationId=create_op, deleteOperationId=delete_op,
                   observedEnvironmentIds=[environment, other, "DO_NOT_PERSIST"],
                   error="TRANSPORT", cleanupError="DELETE_FAILED",
                   readyResourceSample={"status": "UNAVAILABLE"})
        row[capacity.CREATE] = {"queueWaitSeconds": 1, "provisioningSeconds": 2,
                                "readinessSeconds": 3, "acceptanceToTerminalSeconds": 6}
        row[capacity.DELETE] = {"acceptanceToTerminalSeconds": 7}
        data = self.journal.data
        data.update(status="FAILED", error="DEADLINE",
                    context={"gitRevision": "a" * 40, "message": "DO_NOT_PERSIST"},
                    samples=[{"namespaces": [{"status": "AVAILABLE"}]}],
                    requests=[{"outcome": "TRANSPORT", "seconds": 0.5, "acceptanceKind": "INITIAL",
                               "authorization": "DO_NOT_PERSIST"},
                              {"outcome": "HTTP_ACCEPTED", "seconds": 0.25, "acceptanceKind": "REPLAY"}])
        self.baseline.write_summary({"completed": 0, "leftovers": 1, "elapsedSeconds": 4,
                                     "completedPerElapsedMinute": 0})
        path = self.journal.directory / "summary.txt"
        text = path.read_text()
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertIn("run_id=" + self.journal.run_id + " revision=" + "a" * 40, text)
        self.assertIn("CREATE_ENVIRONMENT.provisioningSeconds=2.000", text)
        self.assertIn("CREATE_ENVIRONMENT.readinessSeconds=3.000", text)
        self.assertIn("mutation_attempt_mean_seconds INITIAL=0.500 REPLAY=0.250", text)
        self.assertIn("failed_workflows=1 unattempted=9 failure_records=4", text)
        self.assertIn("codes=DEADLINE:1,DELETE_FAILED:1,TRANSPORT:2", text)
        self.assertIn("leftover index=1 name=" + row["name"], text)
        self.assertIn("idempotency_key=" + row["idempotencyKey"], text)
        for identity in (environment, other, create_op, delete_op):
            self.assertIn(identity, text)
        self.assertNotIn("DO_NOT_PERSIST", text)
        self.assertNotIn(USER, text)

    def test_manifest_private_atomic_and_before_each_mutation(self):
        def check(method, row):
            saved = json.loads((self.journal.directory / "manifest.json").read_text())
            item = saved["workflows"][row["index"] - 1]
            self.assertTrue(item["leftover"])
            self.assertEqual(item["phase"], "CREATE_INTENT" if method == "POST" else "DELETE_INTENT")
            self.assertEqual(item["body"], row["body"])
            self.assertEqual(item["idempotencyKey"], row["idempotencyKey"])
            if method == "DELETE":
                self.assertEqual(item["namespaceUid"], self.kube.uids[row["environmentId"]])
                self.assertIn("namespaceObservedSeconds", item)
        self.api.pre_mutation = check
        self.assertTrue(self.baseline.workflow(self.journal.data["workflows"][0]))
        self.assertEqual(stat.S_IMODE(self.journal.directory.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((self.journal.directory / "manifest.json").stat().st_mode), 0o600)
        self.assertFalse((self.journal.directory / "manifest.pending").exists())
        self.assertEqual(len({row["name"] for row in self.journal.data["workflows"]}), 10)

    def test_owned_workload_sample_is_durable_at_ready_before_delete_with_bounded_budget(self):
        row = self.journal.data["workflows"][0]
        original = self.kube.top

        def sample(ns, deadline):
            self.assertIn("namespaceUid", row)
            self.assertEqual(ns, "env-" + row["environmentId"].replace("-", ""))
            self.assertLessEqual(deadline, self.baseline.work_deadline)
            self.assertLessEqual(deadline - time.monotonic(), 5)
            return original(ns, deadline)

        def before_delete(method, item):
            if method == "DELETE":
                saved = json.loads((self.journal.directory / "manifest.json").read_text())
                sample = saved["workflows"][0]["readyResourceSample"]
                self.assertEqual(sample["status"], "UNAVAILABLE")
                self.assertEqual(sample["namespace"], "env-" + item["environmentId"].replace("-", ""))
        self.api.pre_mutation = before_delete
        with patch.object(self.kube, "top", side_effect=sample):
            self.assertTrue(self.baseline.workflow(row))
        self.assertEqual(len(self.kube.samples), 1)
        self.assertLessEqual(row["namespaceObservedSeconds"], row["readyResourceSample"]["elapsedSeconds"])
        self.assertLessEqual(row["readyResourceSample"]["elapsedSeconds"], row["deletedObservedSeconds"])

    def test_ambiguous_post_reuses_body_and_key_and_stops_admission(self):
        self.api.post_failures = 1
        row = self.journal.data["workflows"][0]
        self.baseline.create(row, self.baseline.deadline)
        self.assertEqual(self.api.posts[0], self.api.posts[1])
        self.assertEqual(len(self.api.posts), 2)
        self.assertTrue(self.baseline.stop.is_set())
        self.assertTrue(row["createAccepted"])
        with self.assertRaises(capacity.Failure) as raised:
            self.baseline.clean(row, self.baseline.deadline)
        self.assertEqual(raised.exception.code, "NAMESPACE_NOT_OBSERVED")
        self.assertTrue(row["apiCleanupConfirmed"])
        self.assertTrue(row["leftover"])

    def test_unresolved_post_stops_new_work_and_retains_key(self):
        self.api.post_failures = 10
        with self.offline_context():
            self.assertEqual(self.baseline.run(), 1)
        self.assertLessEqual(len(self.api.posts), 6)
        self.assertLessEqual(len({key for key, _ in self.api.posts}), 2)
        self.assertFalse(self.api.deletes)
        leftovers = [row for row in self.journal.data["workflows"] if row["leftover"]]
        self.assertTrue(leftovers)
        self.assertTrue(all(row["name"] and row["idempotencyKey"] for row in leftovers))

    def test_partial_acceptance_preserves_environment_without_unsafe_delete(self):
        self.api.partial_acceptance = True
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertIn("environmentId", row)
        self.assertNotIn("createAccepted", row)
        self.assertTrue(row["leftover"])
        self.assertFalse(self.api.deletes)
        self.assertEqual(len(self.api.posts), 3)

    def test_partial_and_conflicting_acceptance_ids_all_preserved(self):
        row = self.journal.data["workflows"][0]
        operation = str(uuid4())
        with self.assertRaises(capacity.Failure):
            self.baseline.accepted(row, {"environmentId": "invalid", "operationId": operation})
        self.assertEqual(row["observedCreateOperationIds"], [operation])
        first, second, another_operation = str(uuid4()), str(uuid4()), str(uuid4())
        self.baseline.accepted(row, {"environmentId": first, "operationId": operation,
                                     "environmentStatus": "REQUESTED", "operationStatus": "PENDING"})
        with self.assertRaises(capacity.Failure) as raised:
            self.baseline.accepted(row, {"environmentId": second, "operationId": another_operation})
        self.assertEqual(raised.exception.code, "ACCEPTANCE_ID_MISMATCH")
        self.assertEqual(row["environmentId"], first)
        self.assertEqual(row["observedEnvironmentIds"], [first, second])
        self.assertIn(another_operation, row["observedCreateOperationIds"])
        self.assertFalse(self.api.deletes)

    def test_cleanup_after_failed_create_poll_and_no_new_admission(self):
        self.api.inject_create_error = True
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertEqual(row["error"], "TRANSPORT")
        self.assertTrue(row["leftover"])
        self.assertTrue(row["apiCleanupConfirmed"])
        self.assertEqual(row["cleanupError"], "NAMESPACE_NOT_OBSERVED")
        self.assertEqual(len(self.api.deletes), 1)
        self.assertTrue(self.baseline.stop.is_set())

    def test_failed_create_operation_can_be_cleaned_safely(self):
        self.api.create_status = "FAILED"
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertEqual(row["error"], "CREATE_FAILED")
        self.assertTrue(row["leftover"])
        self.assertTrue(row["apiCleanupConfirmed"])
        self.assertEqual(len(self.api.deletes), 1)

    def test_ownership_and_original_operation_binding_fail_closed(self):
        for field, value in (
            ("name_override", "other-name"), ("creator_override", "other-user"),
            ("template_override", "other-template"), ("namespace_override", "default"),
            ("operation_environment_override", str(uuid4())),
            ("operation_type_override", capacity.DELETE),
        ):
            with self.subTest(field=field):
                api = FakeAPI(self.journal)
                setattr(api, field, value)
                baseline = capacity.Baseline(self.args, USER, api, FakeKube(api), self.journal)
                row = dict(self.journal.data["workflows"][0])
                self.assertFalse(baseline.workflow(row))
                self.assertTrue(row["leftover"])
                self.assertFalse(api.deletes)

    def test_ambiguous_delete_recovered_without_retry(self):
        self.api.ambiguous_delete = True
        row = self.journal.data["workflows"][0]
        self.assertTrue(self.baseline.workflow(row))
        self.assertEqual(len(self.api.deletes), 1)
        self.assertFalse(row["leftover"])

    def test_unresolved_delete_never_retried(self):
        self.api.ambiguous_delete = self.api.delete_missing_latest = True
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertEqual(len(self.api.deletes), 1)
        self.assertTrue(row["leftover"])
        self.assertEqual(row["cleanupError"], "DELETE_UNRESOLVED")

    def test_failed_delete_retained_as_leftover(self):
        self.api.delete_status = "FAILED"
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertTrue(row["leftover"])
        self.assertEqual(row["cleanupError"], "DELETE_FAILED")

    def test_authorization_error_is_not_namespace_absence(self):
        self.kube.forbidden = True
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertTrue(row["leftover"])
        self.assertEqual(row["cleanupError"], "KUBE_UNAVAILABLE")
        self.assertTrue(row["apiCleanupConfirmed"])
        self.assertNotIn("namespaceAbsentSeconds", row)

    def test_empty_wrong_context_cannot_turn_api_cleanup_into_completion(self):
        self.kube.observation_failure = "KUBE_UNAVAILABLE"
        row = self.journal.data["workflows"][0]
        self.assertFalse(self.baseline.workflow(row))
        self.assertEqual(row["error"], "KUBE_UNAVAILABLE")
        self.assertEqual(row["cleanupError"], "NAMESPACE_NOT_OBSERVED")
        self.assertTrue(row["apiCleanupConfirmed"])
        self.assertTrue(row["leftover"])
        self.assertFalse(row["success"])
        self.assertNotIn("namespaceUid", row)
        self.assertEqual(len(self.api.deletes), 1)

    def test_wrong_cluster_or_namespace_markers_never_count_as_completed(self):
        self.kube.observation_failure = "NAMESPACE_OWNERSHIP_FAILED"
        with self.offline_context():
            self.assertEqual(self.baseline.run(), 1)
        self.assertEqual(self.journal.data["summary"]["completed"], 0)
        self.assertLessEqual(len(self.api.posts), 2)
        self.assertTrue(self.api.deletes)
        for row in self.journal.data["workflows"]:
            if row.get("environmentId"):
                self.assertTrue(row["leftover"])
                self.assertTrue(row["apiCleanupConfirmed"])
                self.assertNotIn("namespaceUid", row)
                self.assertEqual(row["cleanupError"], "NAMESPACE_NOT_OBSERVED")

    def test_lifecycle_and_reserved_cleanup_deadline_nonterminal_create(self):
        clock = Clock()
        self.api.create_status = "RUNNING"
        baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal,
                                     clock=clock, sleep=clock.sleep)
        row = self.journal.data["workflows"][0]
        self.assertFalse(baseline.workflow(row))
        self.assertAlmostEqual(clock(), self.args.lifecycle_timeout)
        self.assertTrue(row["leftover"])
        self.assertFalse(self.api.deletes)
        self.assertEqual(row["error"], "DEADLINE")
        self.assertEqual(row["cleanupError"], "DEADLINE")

    def test_global_deadline_and_pending_namespace_absence(self):
        clock = Clock()
        self.args.overall_timeout = 7
        self.kube.still_present = True
        baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal,
                                     clock=clock, sleep=clock.sleep)
        clock.now = 1
        row = self.journal.data["workflows"][0]
        self.assertFalse(baseline.workflow(row))
        self.assertLessEqual(clock(), 7)
        self.assertTrue(row["leftover"])
        self.assertEqual(len(self.api.deletes), 1)

    def test_global_deadline_reserves_cleanup_for_running_create(self):
        clock = Clock()
        self.args.overall_timeout = 7
        self.api.create_status = "RUNNING"
        baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal,
                                     clock=clock, sleep=clock.sleep)
        row = self.journal.data["workflows"][0]
        self.assertFalse(baseline.workflow(row))
        self.assertAlmostEqual(clock(), 7)
        self.assertTrue(row["leftover"])
        self.assertFalse(self.api.deletes)

    def test_interruption_gate_prevents_create_and_preserves_planned_manifest(self):
        self.baseline.stop.set()
        with self.offline_context():
            self.assertEqual(self.baseline.run(), 1)
        self.assertFalse(self.api.posts)
        self.assertEqual(self.journal.data["summary"]["successFraction"], 0)
        self.assertEqual(len(self.journal.data["workflows"]), 10)

    def test_disabled_worker_fails_before_any_mutation(self):
        for metrics_enabled, host_setting in ((False, None), (True, "false")):
            with self.subTest(metrics=metrics_enabled, host=host_setting):
                self.api.worker_enabled = metrics_enabled
                self.args.worker_enabled = host_setting
                baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal)
                # Each run writes a one-shot summary file.
                summary = self.journal.directory / "summary.txt"
                if summary.exists():
                    summary.unlink()
                with self.offline_context():
                    self.assertEqual(baseline.run(), 1)
                self.assertFalse(self.api.posts)
                self.assertFalse(self.api.deletes)
                self.assertEqual(self.journal.data["error"], "WORKER_DISABLED")
                self.assertEqual(self.journal.data["summary"]["completed"], 0)

    def test_disabled_worker_gate_survives_partial_metrics_and_skips_context_collection(self):
        original = self.api.call

        def partial(method, path, deadline, *args, **kwargs):
            if path == "/operations/metrics":
                return {"workerEnabled": False, "generatedAt": "DO_NOT_PERSIST"}
            return original(method, path, deadline, *args, **kwargs)
        with patch.object(self.api, "call", side_effect=partial), \
                patch.object(capacity, "collect_context") as context:
            self.assertEqual(self.baseline.run(), 1)
        context.assert_not_called()
        self.assertFalse(self.api.posts)
        self.assertEqual(self.journal.data["error"], "WORKER_DISABLED")
        self.assertNotIn("DO_NOT_PERSIST", (self.journal.directory / "manifest.json").read_text())

    def test_unavailable_or_malformed_before_metrics_never_permit_post(self):
        original = self.api.call
        malformed = metric_snapshot()
        malformed["operations"][0].pop("pending")
        for problem in (
            capacity.Failure("TRANSPORT"), capacity.Failure("HTTP_REJECTED"),
            {"workerEnabled": True, "generatedAt": STAMP}, malformed,
        ):
            with self.subTest(problem=type(problem).__name__):
                summary = self.journal.directory / "summary.txt"
                if summary.exists():
                    summary.unlink()
                calls = []

                def call(method, path, deadline, *args, **kwargs):
                    if path == "/operations/metrics":
                        calls.append(path)
                        if len(calls) == 1:
                            if isinstance(problem, Exception):
                                raise problem
                            return problem
                    return original(method, path, deadline, *args, **kwargs)
                baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal)
                with patch.object(self.api, "call", side_effect=call), \
                        patch.object(capacity, "collect_context") as context:
                    self.assertEqual(baseline.run(), 1)
                context.assert_not_called()
                self.assertFalse(self.api.posts)
                self.assertFalse(self.api.deletes)
                self.assertEqual(self.journal.data["error"], "METRICS_BEFORE_UNAVAILABLE")
                self.assertFalse(self.journal.data["supportingEvidenceComplete"])
                self.assertIn("METRICS_BEFORE_UNAVAILABLE", summary.read_text())

    def test_unavailable_after_metrics_fail_otherwise_clean_baseline(self):
        original = self.api.call
        calls = []

        def call(method, path, deadline, *args, **kwargs):
            if path == "/operations/metrics":
                calls.append(path)
                if len(calls) == 2:
                    raise capacity.Failure("TRANSPORT")
            return original(method, path, deadline, *args, **kwargs)
        with patch.object(self.api, "call", side_effect=call), self.offline_context():
            self.assertEqual(self.baseline.run(), 1)
        self.assertEqual(len(self.api.posts), 10)
        self.assertEqual(len(self.api.deletes), 10)
        self.assertEqual(self.journal.data["summary"]["completed"], 10)
        self.assertEqual(self.journal.data["summary"]["leftovers"], 0)
        self.assertEqual(self.journal.data["status"], "FAILED")
        self.assertEqual(self.journal.data["error"], "METRICS_AFTER_UNAVAILABLE")
        self.assertFalse(self.journal.data["supportingEvidenceComplete"])
        text = (self.journal.directory / "summary.txt").read_text()
        self.assertIn("supporting_evidence_complete=false", text)
        self.assertIn("METRICS_AFTER_UNAVAILABLE", text)

    def test_malformed_after_metrics_are_incomplete_even_when_worker_flag_is_true(self):
        original = self.api.call
        calls = []

        def call(method, path, deadline, *args, **kwargs):
            if path == "/operations/metrics":
                calls.append(path)
                if len(calls) == 2:
                    return {"generatedAt": STAMP, "workerEnabled": True}
            return original(method, path, deadline, *args, **kwargs)
        with patch.object(self.api, "call", side_effect=call), self.offline_context():
            self.assertEqual(self.baseline.run(), 1)
        self.assertEqual(self.journal.data["summary"]["completed"], 10)
        self.assertEqual(self.journal.data["error"], "METRICS_AFTER_UNAVAILABLE")
        self.assertFalse(self.journal.data["supportingEvidenceComplete"])

    def test_preflight_context_exception_still_writes_private_sanitized_summary(self):
        for error in (capacity.Failure("DEADLINE"), OSError("DO_NOT_PERSIST")):
            summary = self.journal.directory / "summary.txt"
            if summary.exists():
                summary.unlink()
            baseline = capacity.Baseline(self.args, USER, self.api, self.kube, self.journal)
            with patch.object(capacity, "collect_context", side_effect=error):
                self.assertEqual(baseline.run(), 1)
            self.assertTrue(summary.exists())
            self.assertEqual(stat.S_IMODE(summary.stat().st_mode), 0o600)
            self.assertIn("status=FAILED completed=0/10", summary.read_text())
            self.assertNotIn("DO_NOT_PERSIST", summary.read_text())
            self.assertNotIn("DO_NOT_PERSIST", (self.journal.directory / "manifest.json").read_text())
            self.assertFalse(self.api.posts)

    def test_interrupted_accepted_create_still_cleans(self):
        row = self.journal.data["workflows"][0]
        original = self.baseline.await_create

        def interrupted(item, deadline, cleaning=False):
            if not cleaning:
                self.baseline.stop.set()
                raise capacity.Failure("STOPPED")
            return original(item, deadline, cleaning)
        with patch.object(self.baseline, "await_create", side_effect=interrupted):
            self.assertFalse(self.baseline.workflow(row))
        self.assertTrue(row["leftover"])
        self.assertTrue(row["apiCleanupConfirmed"])
        self.assertEqual(len(self.api.deletes), 1)

    def test_sampling_only_platform_and_owned_namespaces(self):
        row = self.journal.data["workflows"][0]
        self.baseline.create(row, self.baseline.deadline)
        self.baseline.sample()
        self.assertEqual(self.kube.samples, ["platform-lab"])
        self.baseline.await_create(row, self.baseline.deadline)
        self.baseline.next_sample = 0
        self.baseline.sample()
        self.assertEqual(set(self.kube.samples), {
            "platform-lab", "env-" + row["environmentId"].replace("-", "")})

    def test_failed_manifest_persistence_prevents_mutation(self):
        with patch.object(self.journal, "save", side_effect=OSError("DO_NOT_PERSIST")):
            with self.assertRaises(OSError):
                self.baseline.create(self.journal.data["workflows"][0], self.baseline.deadline)
        self.assertFalse(self.api.posts)


class GateTests(unittest.TestCase):
    def argv(self):
        return ["--confirm-disposable", "--api-base-url", "http://127.0.0.1:8080/api/v1",
                "--context", "disposable", "--platform-namespace", "platform-lab",
                "--artifacts", str(Path.cwd().parent.resolve()),
                "--cache-condition", "unknown", "--worker-host", "cluster"]

    def test_explicit_gates_and_credentials(self):
        args, username, authorization = capacity.arguments(self.argv(), CREDENTIALS)
        self.assertEqual(username, USER)
        self.assertTrue(authorization.startswith("Basic "))
        self.assertEqual(args.lifecycle_timeout, 600)
        for env in ({}, {**CREDENTIALS, "PLATFORM_CAPACITY_BASELINE": "TRUE"},
                    {**CREDENTIALS, "PLATFORM_BENCHMARK_PASSWORD": ""}):
            with self.subTest(env=bool(env)), self.assertRaises(capacity.Failure):
                capacity.arguments(self.argv(), env)
        with self.assertRaises(capacity.Failure):
            capacity.arguments(self.argv()[1:], CREDENTIALS)

    def test_explicit_target_and_safe_budgets(self):
        for option, value in (
            ("--api-base-url", "https://example.invalid/api/v1"),
            ("--api-base-url", "******127.0.0.1:8080/api/v1"),
            ("--api-base-url", "http://127.0.0.1:8080/api/v1?x=secret"),
            ("--context", "--untrusted"), ("--platform-namespace", "x;y"),
            ("--artifacts", "."), ("--artifacts", str(Path.cwd())),
            ("--cache-condition", "arbitrary"),
        ):
            argv = self.argv()
            argv[argv.index(option) + 1] = value
            with self.subTest(option=option), self.assertRaises(capacity.Failure):
                capacity.arguments(argv, CREDENTIALS)
        for addition in (["--overall-timeout", "nan"], ["--request-timeout", "31"],
                         ["--cleanup-timeout", "600"], ["--poll-interval", "0"],
                         ["--overall-timeout", "1"]):
            with self.subTest(addition=addition), self.assertRaises(capacity.Failure):
                capacity.arguments(self.argv() + addition, CREDENTIALS)

    def test_host_worker_context_required_and_no_environment_dump(self):
        argv = self.argv()
        argv[-1] = "host"
        with self.assertRaises(capacity.Failure):
            capacity.arguments(argv, CREDENTIALS)
        args, _, _ = capacity.arguments(argv + ["--worker-enabled", "true",
                                                "--worker-poll-delay-ms", "1000"], CREDENTIALS)
        kube = SimpleNamespace(read=lambda *a: (_ for _ in ()).throw(capacity.Failure("KUBE_UNAVAILABLE")))
        with patch.object(capacity.subprocess, "run", side_effect=OSError):
            context = capacity.collect_context(args, kube, time.monotonic() + 5)
        self.assertEqual(context["workerSettings"]["PLATFORM_WORKER_ENABLED"], "true")
        self.assertEqual(context["workerSettings"]["PLATFORM_WORKER_MAX_ATTEMPTS"], "unknown")
        self.assertNotIn("PASSWORD", json.dumps(context))
        self.assertEqual(context["template"]["image"], "nginxinc/nginx-unprivileged:1.28-alpine")
        self.assertEqual(context["template"]["requests"], {"cpu": "50m", "memory": "32Mi"})

    def test_real_cli_help_and_no_opt_in_exit_without_network_or_artifacts(self):
        environment = dict(os.environ)
        for key in CREDENTIALS:
            environment.pop(key, None)
        script = str(AUTOMATION / "capacity_baseline.py")
        help_result = capacity.subprocess.run(
            [sys.executable, script, "--help"], env=environment,
            capture_output=True, text=True, timeout=5, check=False)
        self.assertEqual(help_result.returncode, 0)
        self.assertIn("--worker-host", help_result.stdout)
        self.assertIn("--cache-condition", help_result.stdout)
        self.assertEqual(help_result.stderr, "")
        refused = capacity.subprocess.run(
            [sys.executable, script] + self.argv(), env=environment,
            capture_output=True, text=True, timeout=5, check=False)
        self.assertEqual(refused.returncode, 1)
        self.assertEqual(refused.stdout.strip(), "capacity-baseline: OPT_IN_REQUIRED")
        self.assertEqual(refused.stderr, "")


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.journal = SimpleNamespace(request=self.events.append)
        self.args = options(Path("."))
        self.args.request_timeout = 0.04
        self.row = {"index": 1}

    def client(self):
        return capacity.Client(self.args, "Basic offline", self.journal)

    @contextmanager
    def local_server(self, mode):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                requests.append((self.path, self.headers.get("Authorization")))
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                payload = json.dumps({"message": "DO_NOT_PERSIST",
                                      "authorization": self.headers.get("Authorization")}).encode()
                try:
                    self.send_response(302 if mode == "redirect" else 401 if mode == "auth" else 202)
                    if mode == "redirect":
                        self.send_header("Location", "/api/v1/redirected")
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    for byte in payload:
                        self.wfile.write(bytes([byte]))
                        self.wfile.flush()
                        if mode == "slow":
                            time.sleep(0.015)
                except OSError:
                    pass

            do_GET = do_POST

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.01})
        thread.start()
        try:
            yield server.server_port, requests
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_real_http_slow_body_has_wall_deadline_without_credential_or_body_logging(self):
        self.args.request_timeout = 0.07
        captured = io.StringIO()
        with self.local_server("slow") as (port, requests):
            self.args.api_base_url = ("http", "127.0.0.1", port)
            start = time.monotonic()
            with redirect_stdout(captured), redirect_stderr(captured), self.assertRaises(capacity.Failure) as raised:
                self.client().call("POST", "/environments", start + 2, self.row, {"name": "safe"}, "key")
            elapsed = time.monotonic() - start
        self.assertEqual(raised.exception.code, "TRANSPORT")
        self.assertLess(elapsed, 0.4)
        self.assertEqual(requests, [("/api/v1/environments", "Basic offline")])
        evidence = captured.getvalue() + json.dumps(self.events)
        self.assertNotIn("Basic offline", evidence)
        self.assertNotIn("DO_NOT_PERSIST", evidence)

    def test_real_http_redirect_and_auth_fail_closed_and_never_follow_or_log(self):
        self.args.request_timeout = 1
        for mode, status in (("redirect", 302), ("auth", 401)):
            with self.subTest(mode=mode), self.local_server(mode) as (port, requests):
                self.args.api_base_url = ("http", "127.0.0.1", port)
                captured = io.StringIO()
                with redirect_stdout(captured), redirect_stderr(captured), \
                        patch.dict(os.environ, {"HTTP_PROXY": "http://127.0.0.1:1"}), \
                        self.assertRaises(capacity.Failure) as raised:
                    self.client().call("POST", "/environments", time.monotonic() + 2,
                                       self.row, {"name": "safe"}, "key")
                self.assertEqual(raised.exception.code, "HTTP_REJECTED")
                self.assertEqual(len(requests), 1)
                self.assertEqual(self.events[-1]["httpStatus"], status)
                evidence = captured.getvalue() + json.dumps(self.events)
                self.assertNotIn("Basic offline", evidence)
                self.assertNotIn("DO_NOT_PERSIST", evidence)

    def connection(self, status=202, raw=b"{}", slow=False):
        ended = threading.Event()
        sock = SimpleNamespace(shutdown=lambda *a: ended.set())
        connection = SimpleNamespace(sock=sock, connect=lambda: None, close=lambda: None)
        connection.request = lambda *a, **kw: None

        def read(size):
            if slow:
                ended.wait(1)
            return raw
        response = SimpleNamespace(status=status, read=read)

        def getresponse():
            # http.client detaches the socket for Connection: close responses.
            connection.sock = None
            return response
        connection.getresponse = getresponse
        return connection

    def test_strict_slow_body_deadline_even_after_connection_detaches_socket(self):
        connection = self.connection(slow=True)
        start = time.monotonic()
        with patch.object(capacity.http.client, "HTTPConnection", return_value=connection):
            with self.assertRaises(capacity.Failure) as raised:
                self.client().call("POST", "/environments", start + 1, self.row, {"x": 1}, "key")
        self.assertEqual(raised.exception.code, "TRANSPORT")
        self.assertLess(time.monotonic() - start, 0.3)
        self.assertEqual(self.events[0]["outcome"], "TRANSPORT")

    def test_redirects_oversize_and_malformed_json_fail_closed_without_body_logging(self):
        for status, raw, code in (
            (302, b'{"message":"DO_NOT_PERSIST"}', "HTTP_REJECTED"),
            (202, b"x" * (capacity.MAX_BODY + 1), "RESPONSE_TOO_LARGE"),
            (202, b'{"a":1,"a":2}', "INVALID_JSON"),
            (202, b'{"message":"DO_NOT_PERSIST"', "INVALID_JSON"),
        ):
            with self.subTest(code=code), patch.object(
                    capacity.http.client, "HTTPConnection", return_value=self.connection(status, raw)):
                with self.assertRaises(capacity.Failure) as raised:
                    self.client().call("POST", "/environments", time.monotonic() + 2,
                                       self.row, {"name": "safe"}, "same-key", attempt=2)
                self.assertEqual(raised.exception.code, code)
        self.assertNotIn("DO_NOT_PERSIST", json.dumps(self.events))
        self.assertTrue(all(event["acceptanceKind"] == "REPLAY" for event in self.events))
        self.assertTrue(all(event["attempt"] == 2 for event in self.events))

    def test_proxy_environment_unused_and_body_key_exact(self):
        connection = self.connection()
        with patch.object(capacity.http.client, "HTTPConnection", return_value=connection) as connect:
            with patch.dict(os.environ, {"HTTP_PROXY": "http://do-not-use.invalid:1234"}):
                client = self.client()
                for attempt in (1, 2):
                    client.call("POST", "/environments", time.monotonic() + 1, self.row,
                                {"name": "safe"}, "same-key", attempt)
        self.assertEqual(connect.call_args.args, ("127.0.0.1", 8080))
        self.assertEqual(self.events[0]["acceptanceKind"], "INITIAL")
        self.assertEqual(self.events[1]["acceptanceKind"], "REPLAY")

    def test_expired_deadline_no_network(self):
        with patch.object(capacity.http.client, "HTTPConnection") as connect:
            with self.assertRaises(capacity.Failure):
                self.client().call("GET", "/operations/metrics", time.monotonic() - 1)
        connect.assert_not_called()

    def test_tls_handshake_is_interruptible_and_requires_tls12(self):
        self.args.api_base_url = ("https", "127.0.0.1", 443)
        ended = threading.Event()
        tls = SimpleNamespace(shutdown=lambda *a: ended.set(), settimeout=lambda value: None,
                              do_handshake=lambda: ended.wait(1))
        context = SimpleNamespace(minimum_version=None, wrap_socket=lambda *a, **kw: tls)
        connection = self.connection()
        start = time.monotonic()
        with patch.object(capacity.http.client, "HTTPSConnection", return_value=connection), \
                patch.object(capacity.http.client.HTTPConnection, "connect", lambda connection: None), \
                patch.object(capacity.ssl, "create_default_context", return_value=context):
            with self.assertRaises(capacity.Failure) as raised:
                self.client().call("POST", "/environments", start + 1, self.row, {"name": "safe"}, "key")
        self.assertEqual(raised.exception.code, "TRANSPORT")
        self.assertEqual(context.minimum_version, capacity.ssl.TLSVersion.TLSv1_2)
        self.assertLess(time.monotonic() - start, 0.3)


class EvidenceTests(unittest.TestCase):
    def test_empty_top_output_is_unavailable_not_an_empty_successful_measurement(self):
        kube = capacity.Kube(options(Path(".")))
        for output in ("", "\n", " \n\t"):
            with self.subTest(output=repr(output)), patch.object(kube, "read", return_value=output):
                sample = kube.top("platform-lab", time.monotonic() + 1)
            self.assertEqual(sample, {"namespace": "platform-lab", "status": "UNAVAILABLE"})
        with patch.object(kube, "read", return_value="sample-service-abc 3m 5Mi\n"):
            sample = kube.top("platform-lab", time.monotonic() + 1)
        self.assertEqual(sample["status"], "AVAILABLE")
        self.assertEqual(sample["pods"], [{"pod": "sample-service-abc", "cpu": "3m", "memory": "5Mi"}])

    def test_namespace_observation_requires_exact_backend_markers_and_records_only_uid(self):
        args = options(Path("."))
        kube = capacity.Kube(args)
        row = {"environmentId": str(uuid4()), "createOperationId": str(uuid4())}
        ns, uid = "env-" + row["environmentId"].replace("-", ""), str(uuid4())
        markers = {"app.kubernetes.io/managed-by": "platform-lab",
                   "platform-lab/environment-id": row["environmentId"],
                   "platform-lab/create-operation-id": row["createOperationId"]}
        value = {"kind": "Namespace", "apiVersion": "v1", "status": {"phase": "Active"},
                 "metadata": {"name": ns, "uid": uid, "labels": dict(markers),
                              "annotations": {**markers, "arbitrary": "DO_NOT_PERSIST"}}}
        with patch.object(kube, "read", return_value=value) as read:
            self.assertEqual(kube.observe(row, time.monotonic() + 1), uid)
        self.assertEqual(read.call_args.args[0], ["get", "namespace", ns])
        for field in ("labels", "annotations"):
            for marker in markers:
                replaced = json.loads(json.dumps(value))
                replaced["metadata"][field][marker] = "substituted"
                with self.subTest(field=field, marker=marker), \
                        patch.object(kube, "read", return_value=replaced), \
                        self.assertRaises(capacity.Failure):
                    kube.observe(row, time.monotonic() + 1)
        for changes in (
            {"name": "default"}, {"uid": None}, {"deletionTimestamp": STAMP},
            {"labels": None}, {"annotations": None},
        ):
            replaced = json.loads(json.dumps(value))
            replaced["metadata"].update(changes)
            with self.subTest(changes=changes), patch.object(kube, "read", return_value=replaced), \
                    self.assertRaises(capacity.Failure):
                kube.observe(row, time.monotonic() + 1)
        for changes in ({"kind": "Pod"}, {"apiVersion": "v2"}, {"status": None},
                        {"status": {"phase": "Terminating"}}):
            replaced = {**value, **changes}
            with patch.object(kube, "read", return_value=replaced), self.assertRaises(capacity.Failure):
                kube.observe(row, time.monotonic() + 1)
        with patch.object(kube, "read", side_effect=capacity.Failure("KUBE_UNAVAILABLE")), \
                self.assertRaises(capacity.Failure):
            kube.observe(row, time.monotonic() + 1)

    def test_safe_milestones_distinguish_provisioning_and_readiness_without_messages(self):
        value = {
            "status": "SUCCEEDED", "createdAt": STAMP, "startedAt": "2026-10-17T10:00:02Z",
            "completedAt": "2026-10-17T10:00:07Z", "events": [
                {"type": "PROGRESS", "code": "PROVISIONING_STARTED", "createdAt": "2026-10-17T10:00:02Z",
                 "message": "DO_NOT_PERSIST", "correlationId": "DO_NOT_PERSIST"},
                {"type": "PROVISIONED", "code": "PROVISIONED", "createdAt": "2026-10-17T10:00:05Z"},
                {"type": "SUCCEEDED", "code": "ENVIRONMENT_READY", "createdAt": "2026-10-17T10:00:07Z"},
                {"type": "DO_NOT_PERSIST", "code": "DO_NOT_PERSIST", "createdAt": STAMP},
                {"type": "PROGRESS", "code": "WORKLOAD_CREATED", "createdAt": "DO_NOT_PERSIST"},
                {"type": "PROVISIONED", "code": "PROVISIONED", "createdAt": "2026-10-17T10:00:06Z"},
            ],
        }
        evidence = capacity.operation_timings(value)
        self.assertEqual(evidence["provisioningSeconds"], 3)
        self.assertEqual(evidence["readinessSeconds"], 2)
        self.assertEqual(evidence["milestonesStatus"], "AVAILABLE")
        self.assertEqual(len(evidence["milestones"]), 3)
        self.assertEqual(evidence["milestones"][1]["afterAcceptanceSeconds"], 5)
        self.assertNotIn("DO_NOT_PERSIST", json.dumps(evidence))
        value["events"] = None
        evidence = capacity.operation_timings(value)
        self.assertEqual(evidence["milestonesStatus"], "UNAVAILABLE")
        self.assertNotIn("provisioningSeconds", evidence)
        self.assertNotIn("readinessSeconds", evidence)

    def test_untrusted_metric_numbers_do_not_overflow_or_leak_messages(self):
        invalid = metric_snapshot()
        invalid["operations"][0].update(pending=10**1000, running=float("inf"))
        with self.assertRaises(capacity.Failure) as raised:
            capacity.metrics(invalid)
        self.assertEqual(raised.exception.code, "INVALID_METRICS")
        value = capacity.metrics(metric_snapshot())
        self.assertNotIn("DO_NOT_PERSIST", json.dumps(value, allow_nan=False))

    def test_cluster_whitelist_and_worker_env_allowlist(self):
        args = options(Path("."))

        class Kube:
            def read(self, command, deadline):
                if command == ["version"]:
                    return {"serverVersion": {"gitVersion": "v1.34.1", "arbitrary": "DO_NOT_PERSIST"}}
                if command == ["get", "nodes"]:
                    return {"items": [{"metadata": {"name": "node-1", "annotations": {"secret": "DO_NOT_PERSIST"}},
                                       "status": {"nodeInfo": {"kubeletVersion": "v1.34.1"},
                                                  "allocatable": {"cpu": "4", "memory": "8Gi",
                                                                  "pods": "110", "ephemeral-storage": "20Gi"}}}]}
                return {"items": [{"metadata": {"name": "backend"}, "spec": {"replicas": 3, "template": {
                    "spec": {"containers": [{"image": "registry.invalid/backend:v1",
                                              "env": [{"name": "DB_PASSWORD", "value": "DO_NOT_PERSIST"},
                                                      {"name": "PLATFORM_WORKER_ENABLED", "value": "true"},
                                                      {"name": "PLATFORM_WORKER_MAX_ATTEMPTS", "value": "5"},
                                                      {"name": "PLATFORM_WORKER_RETRY_BACKOFF",
                                                       "valueFrom": {"secretKeyRef": "DO_NOT_PERSIST"}}]}]}}}}]}
        with patch.object(capacity.subprocess, "run", side_effect=OSError):
            value = capacity.collect_context(args, Kube(), time.monotonic() + 10)
        self.assertNotIn("DO_NOT_PERSIST", json.dumps(value))
        self.assertNotIn("DB_PASSWORD", json.dumps(value))
        self.assertEqual(value["cluster"]["nodeCount"], 1)
        self.assertEqual(value["platformImages"]["images"][0]["replicas"], 3)
        self.assertEqual(value["deploymentWorkerSettings"][-1]["value"], "unknown")

    def test_kube_absence_explicit_context_read_only_and_bounded(self):
        args = options(Path("."))
        kube = capacity.Kube(args)
        with patch.object(kube, "read", return_value="") as read:
            self.assertTrue(kube.absent("env-" + "a" * 32, time.monotonic() + 1))
        self.assertIn("--ignore-not-found", read.call_args.args[0])
        with patch.object(kube, "read", return_value="namespace/env-existing"):
            self.assertFalse(kube.absent("env-existing", time.monotonic() + 1))
        with patch.object(kube, "read", side_effect=capacity.Failure("KUBE_UNAVAILABLE")):
            self.assertEqual(kube.top("platform-lab", time.monotonic() + 1)["status"], "UNAVAILABLE")
            with self.assertRaises(capacity.Failure):
                kube.absent("env-existing", time.monotonic() + 1)

    def test_kube_process_explicit_target_and_output_deadline(self):
        args = options(Path("."))
        kube = capacity.Kube(args)
        read_fd, write_fd = os.pipe()
        output = os.fdopen(read_fd, "rb")
        os.write(write_fd, b'{"items":[]}')
        os.close(write_fd)
        process = SimpleNamespace(stdout=output, returncode=0, wait=lambda **kw: 0,
                                  poll=lambda: 0, kill=lambda: None)
        with patch.object(capacity.subprocess, "Popen", return_value=process) as popen:
            self.assertEqual(kube.read(["get", "nodes"], time.monotonic() + 1), {"items": []})
        self.assertEqual(popen.call_args.args[0], [
            "kubectl", "--context", "disposable", "--request-timeout=4s", "get", "nodes", "-o", "json"])
        self.assertEqual(popen.call_args.kwargs["stderr"], capacity.subprocess.DEVNULL)

        read_fd, write_fd = os.pipe()
        output = os.fdopen(read_fd, "rb")
        running = [True]

        def kill():
            running[0] = False
        process = SimpleNamespace(stdout=output, returncode=None, wait=lambda **kw: 0,
                                  poll=lambda: None if running[0] else -9, kill=kill)
        start = time.monotonic()
        try:
            with patch.object(capacity.subprocess, "Popen", return_value=process):
                with self.assertRaises(capacity.Failure):
                    kube.read(["get", "nodes"], start + 0.04)
        finally:
            os.close(write_fd)
        self.assertFalse(running[0])
        self.assertLess(time.monotonic() - start, 0.3)


if __name__ == "__main__":
    unittest.main()
