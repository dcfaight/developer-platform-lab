"""Opt-in, disposable ten-environment lifecycle baseline; never a load generator."""

import argparse
import base64
from collections import Counter
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from datetime import datetime, timezone
import http.client
import json
import math
import os
from pathlib import Path
import re
import select
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
from uuid import uuid4

try:
    from . import cleanup_expired as cleanup
except ImportError:
    import cleanup_expired as cleanup


COUNT = 10
SLOTS = 2
MAX_BODY = 1024 * 1024
TERMINAL = {"SUCCEEDED", "FAILED"}
OP_STATES = TERMINAL | {"PENDING", "RUNNING"}
CREATE = "CREATE_ENVIRONMENT"
DELETE = "DELETE_ENVIRONMENT"
MILESTONES = {
    "PROGRESS": {"PROVISIONING_STARTED", "NAMESPACE_CREATED", "WORKLOAD_CREATED",
                 "DELETION_STARTED", "NAMESPACE_DELETE_REQUESTED"},
    "PROVISIONED": {"PROVISIONED"},
    "SUCCEEDED": {"ENVIRONMENT_READY", "ENVIRONMENT_DELETED"},
}
WORKER_KEYS = {
    "WORKER_ENABLED": "boolean",
    "PLATFORM_WORKER_ENABLED": "boolean",
    "PLATFORM_WORKER_POLL_DELAY_MS": "integer",
    "PLATFORM_WORKER_LEASE_DURATION": "duration",
    "PLATFORM_WORKER_MAX_ATTEMPTS": "integer",
    "PLATFORM_WORKER_RETRY_BACKOFF": "duration",
    "PLATFORM_WORKER_READINESS_POLL_INTERVAL": "duration",
    "PLATFORM_WORKER_READINESS_TIMEOUT": "duration",
}


class Failure(Exception):
    def __init__(self, code):
        self.code = code


def remaining(deadline, clock=time.monotonic):
    value = deadline - clock()
    if value <= 0:
        raise Failure("DEADLINE")
    return value


def number(value, maximum):
    try:
        parsed = float(value)
        if math.isfinite(parsed) and 0.5 <= parsed <= maximum:
            return parsed
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("INVALID_NUMBER")


def identifier(value):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}", value):
        raise argparse.ArgumentTypeError("INVALID_IDENTIFIER")
    return value


def namespace(value):
    if not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", value):
        raise argparse.ArgumentTypeError("INVALID_NAMESPACE")
    return value


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Failure("INVALID_ARGUMENTS")


def arguments(argv=None, environ=None):
    environ = os.environ if environ is None else environ
    parser = Parser(description=__doc__)
    parser.add_argument("--confirm-disposable", action="store_true")
    parser.add_argument("--api-base-url", required=True, type=cleanup.base_url)
    parser.add_argument("--context", required=True, type=identifier)
    parser.add_argument("--platform-namespace", required=True, type=namespace)
    parser.add_argument("--artifacts", required=True)
    parser.add_argument("--cache-condition", required=True, choices=("cold", "warm", "unknown"))
    parser.add_argument("--worker-host", required=True, choices=("cluster", "host"))
    parser.add_argument("--worker-enabled", choices=("true", "false"))
    parser.add_argument("--worker-poll-delay-ms", type=cleanup.bounded_int(1, 60000))
    for name, default, maximum in (
        ("overall-timeout", 1800, 7200), ("lifecycle-timeout", 600, 1800),
        ("cleanup-timeout", 300, 900), ("request-timeout", 10, 30),
        ("poll-interval", 1, 10), ("sample-interval", 15, 60),
    ):
        parser.add_argument("--" + name, default=default,
                            type=lambda value, limit=maximum: number(value, limit))
    args = parser.parse_args(argv)
    if environ.get("PLATFORM_CAPACITY_BASELINE") != "true" or not args.confirm_disposable:
        raise Failure("OPT_IN_REQUIRED")
    if (args.cleanup_timeout >= args.lifecycle_timeout
            or args.cleanup_timeout >= args.overall_timeout):
        raise Failure("INVALID_BUDGETS")
    if args.worker_host == "host" and (
            args.worker_enabled is None or args.worker_poll_delay_ms is None):
        raise Failure("HOST_WORKER_CONTEXT_REQUIRED")
    root = Path(__file__).resolve().parents[1]
    directory = Path(args.artifacts)
    if not directory.is_absolute():
        raise Failure("INVALID_ARTIFACT_DIRECTORY")
    directory = directory.resolve()
    if not directory.is_dir() or directory == root or root in directory.parents:
        raise Failure("INVALID_ARTIFACT_DIRECTORY")
    args.artifacts = directory
    username = environ.get("PLATFORM_BENCHMARK_USERNAME", "")
    password = environ.get("PLATFORM_BENCHMARK_PASSWORD", "")
    if (not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._@-]{0,63}", username)
            or not password or len(password) > 1024):
        raise Failure("CREDENTIALS_REQUIRED")
    authorization = "Basic " + base64.b64encode(
        (username + ":" + password).encode()).decode("ascii")
    return args, username, authorization


def utc():
    return datetime.now(timezone.utc).isoformat()


def valid_uuid(value):
    try:
        return cleanup.uuid(value)
    except cleanup.Failure:
        raise Failure("INVALID_UUID") from None


def stamp(value):
    try:
        cleanup.timestamp(value)
        return value
    except cleanup.Failure:
        raise Failure("INVALID_TIMESTAMP") from None


def token(value, pattern=r"[A-Za-z0-9_.:/@+-]{1,256}"):
    return value if isinstance(value, str) and re.fullmatch(pattern, value) else "unknown"


def numeric(value):
    if type(value) is int:
        return value if 0 <= value <= 2**63 - 1 else None
    return value if type(value) is float and math.isfinite(value) and value >= 0 else None


class Journal:
    """Every mutation intent and accepted identity is durable before further I/O."""
    def __init__(self, directory):
        self.lock = threading.RLock()
        self.run_id = uuid4().hex
        self.directory = directory / ("capacity-" + self.run_id)
        self.directory.mkdir(mode=0o700)
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        self.data = {
            "schemaVersion": 1, "runId": self.run_id, "startedAt": utc(),
            "profile": {"planned": COUNT, "slotsIncludingCleanup": SLOTS},
            "requestMeasurementScope": "MUTATION_ATTEMPTS",
            "status": "RUNNING", "requests": [], "samples": [], "context": {},
            "workflows": [
                {"index": i + 1, "name": f"capacity-{self.run_id}-{i + 1:02d}",
                 "idempotencyKey": str(uuid4()), "phase": "PLANNED", "leftover": False,
                 "body": {"name": f"capacity-{self.run_id}-{i + 1:02d}",
                          "templateId": "sample-service", "ttlHours": 1}}
                for i in range(COUNT)
            ],
        }
        self.save()

    def save(self):
        with self.lock:
            destination = self.directory / "manifest.json"
            pending = self.directory / "manifest.pending"
            flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            fd = os.open(pending, flags, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(self.data, stream, indent=2, allow_nan=False)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(pending, destination)
            fd = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)

    def update(self, row, **values):
        with self.lock:
            row.update(values)
            self.save()

    def request(self, record):
        with self.lock:
            self.data["requests"].append(record)
            self.save()


class Client:
    def __init__(self, args, authorization, journal, clock=time.monotonic):
        self.args, self.authorization, self.journal = args, authorization, journal
        self.clock = clock

    def call(self, method, path, deadline, row=None, body=None, key=None, attempt=1):
        start = self.clock()
        budget = min(self.args.request_timeout, remaining(deadline, self.clock))
        scheme, host, port = self.args.api_base_url
        cls = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
        connection = cls(host, port, timeout=budget)
        expired = threading.Event()
        sock = None

        def interrupt():
            expired.set()
            active_socket = sock if sock is not None else connection.sock
            if active_socket is not None:
                try:
                    active_socket.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

        timer = threading.Timer(budget, interrupt)
        timer.daemon = True
        code, status = "TRANSPORT", None
        timer.start()
        try:
            if scheme == "https":
                # Publish the TLS socket before its handshake so the wall timer can
                # interrupt that handshake as well as headers and slow body streams.
                http.client.HTTPConnection.connect(connection)
                sock = connection.sock
                context = ssl.create_default_context()
                context.minimum_version = ssl.TLSVersion.TLSv1_2
                sock = context.wrap_socket(
                    sock, server_hostname=host, do_handshake_on_connect=False)
                connection.sock = sock
                sock.settimeout(min(budget, remaining(deadline, self.clock)))
                sock.do_handshake()
            else:
                connection.connect()
                sock = connection.sock
            if expired.is_set():
                raise Failure("TRANSPORT")
            headers = {"Accept": "application/json", "Authorization": self.authorization}
            payload = None
            if body is not None:
                headers["Content-Type"] = "application/json"
                payload = json.dumps(body, sort_keys=True, separators=(",", ":")).encode()
            if key is not None:
                headers["Idempotency-Key"] = key
            # Direct http.client connections deliberately ignore proxy environment variables.
            connection.request(method, "/api/v1" + path, body=payload, headers=headers)
            response = connection.getresponse()
            status = response.status
            raw = response.read(MAX_BODY + 1)
            if expired.is_set() or self.clock() - start >= budget:
                raise Failure("TRANSPORT")
            if len(raw) > MAX_BODY:
                raise Failure("RESPONSE_TOO_LARGE")
            if status != (200 if method == "GET" else 202):
                raise Failure("HTTP_SERVER" if 500 <= status <= 599 else "HTTP_REJECTED")
            try:
                data = json.loads(raw, object_pairs_hook=cleanup.unique_object)
            except (ValueError, UnicodeError, RecursionError):
                raise Failure("INVALID_JSON") from None
            if not isinstance(data, dict):
                raise Failure("INVALID_JSON")
            code = "HTTP_ACCEPTED" if method != "GET" else "OK"
            return data
        except Failure as error:
            code = error.code
            raise
        except (OSError, http.client.HTTPException):
            raise Failure("TRANSPORT") from None
        finally:
            timer.cancel()
            connection.close()
            if method != "GET":
                record = {"workflow": row["index"], "method": method, "attempt": attempt,
                          "outcome": code, "seconds": max(0, self.clock() - start),
                          "acceptanceKind": "INITIAL" if attempt == 1 else "REPLAY"}
                if status is not None:
                    record["httpStatus"] = status
                self.journal.request(record)


class Kube:
    def __init__(self, args, clock=time.monotonic):
        self.args, self.clock = args, clock

    def read(self, command, deadline, json_output=True):
        budget = min(5.0, remaining(deadline, self.clock))
        end = min(deadline, self.clock() + budget)
        argv = ["kubectl", "--context", self.args.context, "--request-timeout=4s"] + command
        if json_output:
            argv += ["-o", "json"]
        process = None
        try:
            process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            chunks, size = [], 0
            while True:
                available, _, _ = select.select([process.stdout], [], [],
                                                 remaining(end, self.clock))
                if not available:
                    raise Failure("KUBE_UNAVAILABLE")
                chunk = os.read(process.stdout.fileno(), min(65536, MAX_BODY + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
                if size > MAX_BODY:
                    raise Failure("KUBE_UNAVAILABLE")
            process.wait(timeout=remaining(end, self.clock))
            output = b"".join(chunks)
            if process.returncode:
                raise Failure("KUBE_UNAVAILABLE")
            if not json_output:
                return output.decode("ascii")
            value = json.loads(output, object_pairs_hook=cleanup.unique_object)
            if not isinstance(value, dict):
                raise Failure("KUBE_UNAVAILABLE")
            return value
        except (OSError, ValueError, UnicodeError, subprocess.SubprocessError, RecursionError):
            raise Failure("KUBE_UNAVAILABLE") from None
        finally:
            if process:
                if process.poll() is None:
                    process.kill()
                process.wait()
                process.stdout.close()

    def absent(self, ns, deadline):
        return self.read(["get", "namespace", ns, "--ignore-not-found", "-o", "name"],
                         deadline, json_output=False).strip() == ""

    def observe(self, row, deadline):
        ns = "env-" + row["environmentId"].replace("-", "")
        value = self.read(["get", "namespace", ns], deadline)
        metadata = value.get("metadata")
        status = value.get("status")
        expected = {"app.kubernetes.io/managed-by": "platform-lab",
                    "platform-lab/environment-id": row["environmentId"],
                    "platform-lab/create-operation-id": row["createOperationId"]}
        if (value.get("kind") != "Namespace" or value.get("apiVersion") != "v1"
                or not isinstance(metadata, dict) or metadata.get("name") != ns
                or metadata.get("deletionTimestamp") is not None
                or not isinstance(status, dict) or status.get("phase") != "Active"):
            raise Failure("NAMESPACE_OWNERSHIP_FAILED")
        for field in ("labels", "annotations"):
            markers = metadata.get(field)
            if not isinstance(markers, dict) or any(markers.get(k) != v for k, v in expected.items()):
                raise Failure("NAMESPACE_OWNERSHIP_FAILED")
        return valid_uuid(metadata.get("uid"))

    def top(self, ns, deadline):
        try:
            raw = self.read(["top", "pods", "-n", ns, "--no-headers"], deadline, False)
            pods = []
            for line in raw.splitlines():
                fields = line.split()
                if len(fields) != 3 or any(token(field) == "unknown" for field in fields):
                    raise Failure("KUBE_UNAVAILABLE")
                if not re.fullmatch(r"\d+(?:\.\d+)?m?", fields[1]) or not re.fullmatch(
                        r"\d+(?:\.\d+)?(?:[KMGTPE]i?)?", fields[2]):
                    raise Failure("KUBE_UNAVAILABLE")
                pods.append({"pod": fields[0], "cpu": fields[1], "memory": fields[2]})
            if not pods:
                raise Failure("KUBE_UNAVAILABLE")
            return {"namespace": ns, "status": "AVAILABLE", "pods": pods}
        except Failure:
            return {"namespace": ns, "status": "UNAVAILABLE"}


def worker_value(key, value):
    kind = WORKER_KEYS[key]
    patterns = {"boolean": r"(?:true|false)", "integer": r"[1-9]\d{0,7}",
                "duration": r"[1-9]\d{0,6}(?:ms|s|m|h)"}
    return value if isinstance(value, str) and re.fullmatch(patterns[kind], value) else "unknown"


def collect_context(args, kube, deadline):
    context = {
        "target": {"context": args.context, "platformNamespace": args.platform_namespace,
                   "apiScheme": args.api_base_url[0], "apiHost": args.api_base_url[1],
                   "apiPort": args.api_base_url[2]},
        "cacheCondition": args.cache_condition, "workerHost": args.worker_host,
        "workerSettings": {key: "unknown" for key in WORKER_KEYS},
        "template": {"id": "sample-service", "replicas": 1,
                     "image": "nginxinc/nginx-unprivileged:1.28-alpine",
                     "imagePullPolicy": "IfNotPresent",
                     "requests": {"cpu": "50m", "memory": "32Mi"},
                     "limits": {"cpu": "250m", "memory": "128Mi"}},
        "cluster": {"status": "UNAVAILABLE"}, "platformImages": {"status": "UNAVAILABLE"},
        "gitRevision": "unknown",
    }
    try:
        result = subprocess.run(["git", "-C", str(Path(__file__).resolve().parents[1]),
                                 "rev-parse", "HEAD"], capture_output=True,
                                timeout=min(3, remaining(deadline)), check=False)
        revision = result.stdout.decode("ascii").strip()
        if result.returncode == 0 and re.fullmatch(r"[0-9a-f]{40,64}", revision):
            context["gitRevision"] = revision
    except (OSError, UnicodeError, subprocess.SubprocessError, Failure):
        pass
    try:
        version = kube.read(["version"], deadline)
        nodes = kube.read(["get", "nodes"], deadline)["items"]
        context["cluster"] = {
            "status": "AVAILABLE",
            "serverVersion": token(version.get("serverVersion", {}).get("gitVersion")),
            "nodeCount": len(nodes),
            "nodes": [
                {"name": token(node.get("metadata", {}).get("name")),
                 "kubeletVersion": token(node.get("status", {}).get("nodeInfo", {}).get("kubeletVersion")),
                 "allocatable": {key: token(node.get("status", {}).get("allocatable", {}).get(key))
                                 for key in ("cpu", "memory", "pods", "ephemeral-storage")}}
                for node in nodes
            ],
        }
    except (Failure, KeyError, TypeError, AttributeError):
        pass
    try:
        deployments = kube.read(["get", "deployments", "-n", args.platform_namespace], deadline)
        images = []
        settings = []
        for deployment in deployments["items"]:
            name = token(deployment.get("metadata", {}).get("name"))
            for container in deployment["spec"]["template"]["spec"]["containers"]:
                images.append({"deployment": name, "image": token(container.get("image")),
                               "replicas": numeric(deployment["spec"].get("replicas"))})
                for entry in container.get("env", []):
                    key = entry.get("name")
                    if key in WORKER_KEYS:
                        settings.append({"deployment": name, "key": key,
                                         "value": worker_value(key, entry.get("value"))})
        context["platformImages"] = {"status": "AVAILABLE", "images": images}
        context["deploymentWorkerSettings"] = settings
    except (Failure, KeyError, TypeError, AttributeError):
        pass
    if args.worker_host == "host":
        context["workerSettings"]["PLATFORM_WORKER_ENABLED"] = args.worker_enabled
        context["workerSettings"]["PLATFORM_WORKER_POLL_DELAY_MS"] = str(args.worker_poll_delay_ms)
    return context


def metrics(value):
    """Historical aggregates are not this run's latency distribution."""
    def fields(record, counts=(), durations=()):
        if not isinstance(record, dict) or not set(counts + durations).issubset(record):
            raise Failure("INVALID_METRICS")
        result = {}
        for key in counts + durations:
            item = record[key]
            if item is None and key in durations:
                result[key] = None
            elif numeric(item) is None or (key in counts and type(item) is not int):
                raise Failure("INVALID_METRICS")
            else:
                result[key] = item
        return result

    operations, worker, failures = value.get("operations"), value.get("worker"), value.get("failureCodes")
    if (type(value.get("workerEnabled")) is not bool or not isinstance(operations, list)
            or len(operations) != 2 or not isinstance(worker, dict) or not isinstance(failures, list)):
        raise Failure("INVALID_METRICS")
    result = {"status": "AVAILABLE", "scope": "HISTORICAL_CONTEXT", "operations": []}
    result["workerEnabled"] = value["workerEnabled"]
    result["generatedAt"] = stamp(value.get("generatedAt"))
    seen = set()
    for operation in operations:
        if (not isinstance(operation, dict) or operation.get("type") not in (CREATE, DELETE)
                or operation["type"] in seen):
            raise Failure("INVALID_METRICS")
        seen.add(operation["type"])
        item = {"type": operation["type"], **fields(
            operation, ("pending", "running", "succeeded", "failed"), ("averageQueueSeconds",))}
        item["succeededDuration"] = fields(operation.get("succeededDuration"), ("count",),
                                          ("averageSeconds", "p95Seconds", "maxSeconds"))
        result["operations"].append(item)
    result["worker"] = fields(worker, ("dueOperations", "expiredLeases"))
    for key in ("oldestDueAt", "lastEventAt"):
        if key not in worker:
            raise Failure("INVALID_METRICS")
        result["worker"][key] = stamp(worker[key]) if worker[key] is not None else None
    result["failureCodes"] = []
    for failure in failures:
        item = fields(failure, ("count",))
        code = failure.get("code")
        if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code):
            raise Failure("INVALID_METRICS")
        result["failureCodes"].append({"code": code, **item})
    return result


def operation_timings(value):
    times = {key: cleanup.timestamp(stamp(value[key]))
             for key in ("createdAt", "startedAt", "completedAt") if value.get(key) is not None}
    result = {"status": value["status"], "milestones": [], "milestonesStatus": "UNAVAILABLE"}
    for label, start, end in (
        ("queueWaitSeconds", "createdAt", "startedAt"),
        ("acceptanceToTerminalSeconds", "createdAt", "completedAt"),
        ("executionSeconds", "startedAt", "completedAt"),
    ):
        if start in times and end in times:
            duration = (times[end] - times[start]).total_seconds()
            if duration < 0:
                raise Failure("INVALID_OPERATION_TIMING")
            result[label] = duration
    events, seen = value.get("events"), {}
    for event in events if isinstance(events, list) else []:
        if not isinstance(event, dict):
            continue
        kind, code = event.get("type"), event.get("code")
        if not isinstance(kind, str) or not isinstance(code, str) or code not in MILESTONES.get(kind, set()):
            continue
        try:
            timestamp = stamp(event.get("createdAt"))
            parsed = cleanup.timestamp(timestamp)
        except (Failure, cleanup.Failure):
            continue
        if code in seen or ("createdAt" in times and parsed < times["createdAt"]):
            continue
        seen[code] = parsed
        item = {"type": kind, "code": code, "createdAt": timestamp}
        if "createdAt" in times:
            item["afterAcceptanceSeconds"] = (parsed - times["createdAt"]).total_seconds()
        result["milestones"].append(item)
    if seen:
        result["milestonesStatus"] = "AVAILABLE"
    for label, start, end in (
        ("provisioningSeconds", seen.get("PROVISIONING_STARTED"), seen.get("PROVISIONED")),
        ("readinessSeconds", seen.get("PROVISIONED"), seen.get("ENVIRONMENT_READY")),
    ):
        if start is not None and end is not None and end >= start:
            result[label] = (end - start).total_seconds()
    return result


class Baseline:
    def __init__(self, args, username, client, kube, journal,
                 clock=time.monotonic, sleep=time.sleep):
        self.args, self.username, self.client = args, username, client
        self.kube, self.journal, self.clock, self.sleep = kube, journal, clock, sleep
        self.stop = threading.Event()
        self.interrupted = False
        self.start = clock()
        self.deadline = self.start + args.overall_timeout
        self.work_deadline = self.deadline - args.cleanup_timeout
        self.next_sample = self.start

    def elapsed(self):
        return max(0, self.clock() - self.start)

    def halted(self):
        return self.interrupted or self.stop.is_set()

    def pause(self, deadline):
        self.sleep(min(self.args.poll_interval, remaining(deadline, self.clock)))

    def accepted(self, row, value, deleting=False):
        field = "deleteOperationId" if deleting else "createOperationId"
        observed = {}
        for source, target in (("environmentId", "observedEnvironmentIds"),
                               ("operationId", "observedDeleteOperationIds" if deleting
                                else "observedCreateOperationIds")):
            try:
                identity = valid_uuid(value.get(source))
            except Failure:
                continue
            observed[source] = identity
            identities = list(row.get(target, []))
            if identity not in identities:
                identities.append(identity)
            self.journal.update(row, **{target: identities})
        identity = observed.get("environmentId")
        if identity is None:
            raise Failure("INVALID_UUID")
        if "environmentId" in row and row["environmentId"] != identity:
            raise Failure("ACCEPTANCE_ID_MISMATCH")
        if deleting and "environmentId" not in row:
            raise Failure("ACCEPTANCE_ID_MISMATCH")
        self.journal.update(row, environmentId=identity)
        operation = observed.get("operationId")
        if operation is None:
            raise Failure("INVALID_UUID")
        if field in row and row[field] != operation:
            raise Failure("ACCEPTANCE_ID_MISMATCH")
        self.journal.update(row, **{field: operation})
        allowed = {"DELETING", "DELETED"} if deleting else cleanup.STATES
        if value.get("environmentStatus") not in allowed or value.get("operationStatus") not in OP_STATES:
            raise Failure("INVALID_ACCEPTANCE")
        if not deleting:
            self.journal.update(row, createAccepted=True)
        return operation

    def create(self, row, deadline):
        self.journal.update(row, phase="CREATE_INTENT", leftover=True, createIntentAt=utc())
        last = None
        for attempt in range(1, 4):
            remaining(deadline, self.clock)
            if self.halted() and attempt == 1:
                raise Failure("STOPPED")
            try:
                value = self.client.call("POST", "/environments", deadline, row,
                                         row["body"], row["idempotencyKey"], attempt)
                self.accepted(row, value)
                self.journal.update(row, phase="CREATE_ACCEPTED",
                                    acceptanceObservedSeconds=self.elapsed(),
                                    acceptanceAttempt=attempt)
                return
            except Failure as error:
                last = error
                if error.code not in {"TRANSPORT", "HTTP_SERVER", "INVALID_JSON",
                                      "RESPONSE_TOO_LARGE", "INVALID_UUID", "INVALID_ACCEPTANCE"}:
                    break
                # An ambiguous mutation suspends admission even if replay later resolves it.
                self.stop.set()
        raise Failure("CREATE_UNRESOLVED") from last

    def operation(self, row, field, kind, deadline):
        value = self.client.call("GET", "/operations/" + row[field], deadline)
        if (value.get("id") != row[field]
                or value.get("environmentId") != row["environmentId"]
                or value.get("type") != kind or value.get("status") not in OP_STATES):
            raise Failure("OPERATION_BINDING_FAILED")
        self.journal.update(row, **{kind: operation_timings(value)})
        return value["status"]

    def owned(self, row, deadline):
        value = self.client.call("GET", "/environments/" + row["environmentId"], deadline)
        if (value.get("id") != row["environmentId"]
                or value.get("name") != row["name"]
                or value.get("templateId") != "sample-service"
                or value.get("namespace") != "env-" + row["environmentId"].replace("-", "")
                or value.get("createdBy") != self.username
                or value.get("status") not in cleanup.STATES):
            raise Failure("OWNERSHIP_FAILED")
        return value

    def await_create(self, row, deadline, cleaning=False):
        while True:
            remaining(deadline, self.clock)
            if not cleaning and self.halted():
                raise Failure("STOPPED")
            status = self.operation(row, "createOperationId", CREATE, deadline)
            detail = self.owned(row, deadline)
            if status in TERMINAL:
                self.journal.update(row, ownershipVerified=True)
                if cleaning:
                    return detail
                if status == "FAILED":
                    raise Failure("CREATE_FAILED")
                if detail["status"] == "READY":
                    self.journal.update(row, readyObservedSeconds=self.elapsed())
                    identity = self.kube.observe(row, deadline)
                    self.journal.update(row, namespaceUid=identity,
                                        namespaceObservedSeconds=self.elapsed())
                    sample = self.kube.top(detail["namespace"], min(deadline, self.clock() + 5))
                    self.journal.update(row, readyResourceSample={
                        "elapsedSeconds": self.elapsed(), **sample})
                    return detail
                if detail["status"] in {"FAILED", "DELETED", "DELETE_FAILED"}:
                    raise Failure("CREATE_NOT_READY")
            self.pause(deadline)

    def recover_delete(self, row, deadline):
        detail = self.owned(row, deadline)
        latest = detail.get("latestOperation")
        if (not isinstance(latest, dict) or latest.get("type") != DELETE
                or latest.get("status") not in OP_STATES
                or detail["status"] not in {"DELETING", "DELETED", "DELETE_FAILED"}):
            raise Failure("DELETE_UNRESOLVED")
        identity = valid_uuid(latest.get("id"))
        if row.get("deleteOperationId", identity) != identity:
            raise Failure("DELETE_BINDING_FAILED")
        self.journal.update(row, deleteOperationId=identity)

    def clean(self, row, deadline):
        if not row.get("createAccepted"):
            raise Failure("CREATE_UNRESOLVED")
        detail = self.await_create(row, deadline, cleaning=True)
        if detail["status"] not in {"READY", "FAILED"}:
            raise Failure("UNSAFE_DELETE_STATE")
        # Re-read both bindings immediately before the sole DELETE.
        if self.operation(row, "createOperationId", CREATE, deadline) not in TERMINAL:
            raise Failure("CREATE_NONTERMINAL")
        detail = self.owned(row, deadline)
        if detail["status"] not in {"READY", "FAILED"}:
            raise Failure("UNSAFE_DELETE_STATE")
        remaining(deadline, self.clock)
        self.journal.update(row, phase="DELETE_INTENT", deleteIntentAt=utc())
        try:
            value = self.client.call("DELETE", "/environments/" + row["environmentId"],
                                     deadline, row)
            self.accepted(row, value, deleting=True)
        except Failure:
            self.stop.set()
            self.recover_delete(row, deadline)
        while True:
            remaining(deadline, self.clock)
            state = self.operation(row, "deleteOperationId", DELETE, deadline)
            detail = self.owned(row, deadline)
            if state == "FAILED":
                raise Failure("DELETE_FAILED")
            if state == "SUCCEEDED" and detail["status"] == "DELETED":
                if "deletedObservedSeconds" not in row:
                    self.journal.update(row, deletedObservedSeconds=self.elapsed(), apiCleanupConfirmed=True)
                if self.kube.absent(detail["namespace"], deadline):
                    if not row.get("namespaceUid"):
                        raise Failure("NAMESPACE_NOT_OBSERVED")
                    self.journal.update(row, phase="CLEANED", leftover=False,
                                        namespaceAbsentSeconds=self.elapsed())
                    return
            self.pause(deadline)

    def workflow(self, row):
        began = self.clock()
        self.journal.update(row, startedObservedSeconds=self.elapsed())
        lifecycle_deadline = min(self.deadline, began + self.args.lifecycle_timeout)
        create_deadline = min(self.work_deadline, lifecycle_deadline - self.args.cleanup_timeout)
        error = None
        try:
            remaining(create_deadline, self.clock)
            if self.halted():
                raise Failure("STOPPED")
            self.create(row, create_deadline)
            self.await_create(row, create_deadline)
        except Failure as failure:
            error = failure.code
            self.stop.set()
        except Exception:
            error = "INTERNAL_ERROR"
            self.stop.set()
        finally:
            if row["leftover"] and error not in {"OWNERSHIP_FAILED", "OPERATION_BINDING_FAILED",
                                                "ACCEPTANCE_ID_MISMATCH"}:
                try:
                    cleanup_deadline = min(lifecycle_deadline,
                                           self.clock() + self.args.cleanup_timeout)
                    self.clean(row, cleanup_deadline)
                except Failure as failure:
                    self.stop.set()
                    self.journal.update(row, cleanupError=failure.code)
                except Exception:
                    self.stop.set()
                    self.journal.update(row, cleanupError="INTERNAL_ERROR")
            if error is not None:
                self.journal.update(row, error=error)
            success = error is None and row.get("phase") == "CLEANED" and bool(row.get("namespaceUid"))
            if not success:
                self.stop.set()
            self.journal.update(row, success=success, completedObservedSeconds=self.elapsed(),
                                lifecycleSeconds=max(0, self.clock() - began))
        return success

    def snapshot_metrics(self, label):
        raw = {}
        try:
            raw = self.client.call("GET", "/operations/metrics", self.deadline)
            value = metrics(raw)
        except (Failure, TypeError, AttributeError):
            value = {"status": "UNAVAILABLE", "scope": "HISTORICAL_CONTEXT"}
            if isinstance(raw, dict) and type(raw.get("workerEnabled")) is bool:
                value["workerEnabled"] = raw["workerEnabled"]
        self.journal.update(self.journal.data, **{label: value})
        return value

    def sample(self):
        if self.clock() < self.next_sample or self.clock() >= self.work_deadline:
            return
        self.next_sample = self.clock() + self.args.sample_interval
        with self.journal.lock:
            namespaces = {self.args.platform_namespace}
            namespaces.update("env-" + row["environmentId"].replace("-", "")
                              for row in self.journal.data["workflows"]
                              if row.get("namespaceUid") and row["leftover"])
        samples = [self.kube.top(ns, min(self.work_deadline, self.clock() + 5))
                   for ns in sorted(namespaces)]
        with self.journal.lock:
            self.journal.data["samples"].append({"elapsedSeconds": self.elapsed(),
                                                  "namespaces": samples})
            self.journal.save()

    def execute(self):
        before = self.snapshot_metrics("metricsBefore")
        if before.get("workerEnabled") is False or self.args.worker_enabled == "false":
            self.stop.set()
            self.journal.update(self.journal.data, error="WORKER_DISABLED")
        elif before.get("status") != "AVAILABLE" or before.get("workerEnabled") is not True:
            self.stop.set()
            self.journal.update(self.journal.data, error="METRICS_BEFORE_UNAVAILABLE")
        if not self.halted():
            self.journal.update(self.journal.data, context=collect_context(
                self.args, self.kube, min(self.work_deadline, self.clock() + 25)))
        rows = iter(self.journal.data["workflows"])
        pending = set()
        with ThreadPoolExecutor(max_workers=SLOTS) as executor:
            while True:
                if self.clock() >= self.work_deadline:
                    self.stop.set()
                while not self.halted() and len(pending) < SLOTS:
                    row = next(rows, None)
                    if row is None:
                        break
                    pending.add(executor.submit(self.workflow, row))
                if not pending:
                    break
                try:
                    self.sample()
                except Failure:
                    pass
                done, pending = wait(pending, timeout=0.1, return_when=FIRST_COMPLETED)
                for future in done:
                    try:
                        if not future.result():
                            self.stop.set()
                    except Exception:
                        self.stop.set()

    def run(self):
        try:
            self.execute()
        except Exception as error:
            self.stop.set()
            self.journal.update(self.journal.data,
                                error=error.code if isinstance(error, Failure) else "INTERNAL_ERROR")
        after = self.snapshot_metrics("metricsAfter")
        if after.get("status") != "AVAILABLE":
            self.stop.set()
            field = "supportingEvidenceError" if self.journal.data.get("error") else "error"
            self.journal.update(self.journal.data, **{field: "METRICS_AFTER_UNAVAILABLE"})
        before = self.journal.data.get("metricsBefore", {})
        evidence_complete = (before.get("status") == "AVAILABLE"
                             and before.get("workerEnabled") is True
                             and after.get("status") == "AVAILABLE")
        elapsed = self.elapsed()
        completed = sum(row.get("success", False) for row in self.journal.data["workflows"])
        summary = {"planned": COUNT, "completed": completed,
                   "successFraction": completed / COUNT, "elapsedSeconds": elapsed,
                   "completedPerElapsedMinute": completed * 60 / elapsed if elapsed else 0,
                   "leftovers": sum(row["leftover"] for row in self.journal.data["workflows"])}
        ok = completed == COUNT and not self.halted()
        self.journal.update(self.journal.data, status="SUCCEEDED" if ok else "FAILED",
                            completedAt=utc(), summary=summary, interrupted=self.interrupted,
                            supportingEvidenceComplete=evidence_complete)
        self.write_summary(summary)
        return 0 if ok else 1

    def write_summary(self, summary):
        data, rows = self.journal.data, self.journal.data["workflows"]

        def mean(values):
            values = [value for value in values if numeric(value) is not None]
            return f"{sum(values) / len(values):.3f}" if values else "unknown"

        def identities(row, field, observed=None):
            values = list(row.get(observed, [])) if observed else []
            values.append(row.get(field))
            result = set()
            for value in values:
                try:
                    result.add(valid_uuid(value))
                except Failure:
                    pass
            return ",".join(sorted(result)) or "unknown"

        errors = [data.get("error"), data.get("supportingEvidenceError")]
        errors += [row.get(field) for row in rows for field in ("error", "cleanupError")]
        errors += [request.get("outcome") for request in data["requests"]
                   if request.get("outcome") != "HTTP_ACCEPTED"]
        if self.interrupted:
            errors.append("INTERRUPTED")
        codes = Counter(value if isinstance(value, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", value)
                        else "INTERNAL_ERROR" for value in errors if value is not None)
        if data["status"] != "SUCCEEDED" and not codes:
            codes["STOPPED"] = 1
        ready = Counter(row.get("readyResourceSample", {}).get("status", "NOT_COLLECTED") for row in rows)
        periodic = Counter(item["status"] for sample in data["samples"] for item in sample["namespaces"])
        revision = data["context"].get("gitRevision", "unknown")
        if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{40,64}", revision):
            revision = "unknown"
        scheme, host, port = self.args.api_base_url
        lines = [
            f"status={data['status']} completed={summary['completed']}/{COUNT} leftovers={summary['leftovers']}",
            f"supporting_evidence_complete={str(data.get('supportingEvidenceComplete', False)).lower()}",
            f"run_id={self.journal.run_id} revision={revision}",
            f"target context={self.args.context} platform_namespace={self.args.platform_namespace} "
            f"api_scheme={scheme} api_host={host} api_port={port}",
            f"workload={COUNT} template=sample-service concurrency={SLOTS} including_cleanup=true",
            f"elapsed_seconds={summary['elapsedSeconds']:.3f} "
            f"completed_per_elapsed_minute={summary['completedPerElapsedMinute']:.3f}",
        ]
        timings = [(CREATE, key) for key in ("queueWaitSeconds", "provisioningSeconds", "readinessSeconds",
                                             "acceptanceToTerminalSeconds")]
        timings.append((DELETE, "acceptanceToTerminalSeconds"))
        lines.append("operation_mean_seconds " + " ".join(
            f"{kind}.{key}={mean(row.get(kind, {}).get(key) for row in rows)}" for kind, key in timings))
        lines.append("mutation_attempt_mean_seconds " + " ".join(
            f"{kind}={mean(request.get('seconds') for request in data['requests'] if request.get('acceptanceKind') == kind)}"
            for kind in ("INITIAL", "REPLAY")))
        lines.append(f"resources_ready available={ready['AVAILABLE']} unavailable={ready['UNAVAILABLE']} "
                     f"not_collected={ready['NOT_COLLECTED']} periodic_available={periodic['AVAILABLE']} "
                     f"periodic_unavailable={periodic['UNAVAILABLE']}")
        lines.append(f"failed_workflows={sum(row.get('success') is False for row in rows)} "
                     f"unattempted={sum('createIntentAt' not in row for row in rows)} "
                     f"failure_records={sum(codes.values())} codes="
                     + (",".join(f"{code}:{count}" for code, count in sorted(codes.items())) or "none"))
        for row in rows:
            if row["leftover"]:
                lines.append(f"leftover index={row['index']} name={token(row['name'])} "
                             f"environment_ids={identities(row, 'environmentId', 'observedEnvironmentIds')} "
                             f"create_operation_ids={identities(row, 'createOperationId', 'observedCreateOperationIds')} "
                             f"delete_operation_ids={identities(row, 'deleteOperationId', 'observedDeleteOperationIds')} "
                             f"idempotency_key={identities(row, 'idempotencyKey')}")
        fd = os.open(self.journal.directory / "summary.txt",
                     os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write("\n".join(lines) + "\n")
            stream.flush()
            os.fsync(stream.fileno())


def main(argv=None):
    journal = None
    try:
        args, username, authorization = arguments(argv)
        journal = Journal(args.artifacts)
        baseline = Baseline(args, username, Client(args, authorization, journal), Kube(args), journal)
        previous = {}
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, lambda *_: setattr(baseline, "interrupted", True))
        try:
            result = baseline.run()
        finally:
            for signum, handler in previous.items():
                signal.signal(signum, handler)
        print("capacity-baseline: " + journal.data["status"] + " run=" + journal.run_id)
        return result
    except Failure as error:
        if journal:
            journal.update(journal.data, status="FAILED", error=error.code)
        print("capacity-baseline: " + error.code)
        return 1
    except Exception:
        if journal:
            try:
                journal.update(journal.data, status="FAILED", error="INTERNAL_ERROR")
            except Exception:
                pass
        print("capacity-baseline: INTERNAL_ERROR")
        return 1


if __name__ == "__main__":
    sys.exit(main())
