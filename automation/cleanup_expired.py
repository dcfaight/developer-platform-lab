"""Bounded, trusted-loopback expiration cleanup through the backend API only."""

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import http.client
import ipaddress
import json
import math
import re
import socket
import ssl
import threading
import time
from urllib.parse import urlencode, urlsplit
from uuid import UUID


STATES = {"REQUESTED", "PROVISIONING", "READY", "FAILED", "DELETING",
          "DELETED", "DELETE_FAILED"}
ELIGIBLE = {"READY", "FAILED"}
MAX_BODY = 1024 * 1024


class Failure(Exception):
    def __init__(self, code, status=None, request_id=None):
        self.code = code
        self.status = status
        self.request_id = request_id

    def public(self):
        return {key: value for key, value in {
            "code": self.code, "httpStatus": self.status,
            "requestId": self.request_id,
        }.items() if value is not None}


def safe_token(value):
    if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", value):
        return value
    return None


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result


def uuid(value):
    if not isinstance(value, str):
        raise Failure("INVALID_UUID")
    try:
        parsed = str(UUID(value))
    except ValueError:
        raise Failure("INVALID_UUID") from None
    if parsed != value:
        raise Failure("INVALID_UUID")
    return value


def timestamp(value):
    if not isinstance(value, str) or not re.fullmatch(
            r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)", value):
        raise Failure("INVALID_TIMESTAMP")
    if not value.endswith("Z") and (int(value[-5:-3]) > 23 or int(value[-2:]) > 59):
        raise Failure("INVALID_TIMESTAMP")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise Failure("INVALID_TIMESTAMP") from None
    # Python stores microseconds; round sub-microsecond expiry upward so a
    # nanosecond after the cutoff cannot become eligible through truncation.
    fraction = re.search(r"\.(\d+)", value)
    if fraction and any(c != "0" for c in fraction[1][6:]):
        try:
            result += timedelta(microseconds=1)
        except OverflowError:
            raise Failure("INVALID_TIMESTAMP") from None
    try:
        return result.astimezone(timezone.utc)
    except (ValueError, OverflowError):
        raise Failure("INVALID_TIMESTAMP") from None


def environment(value, expected_id=None):
    if not isinstance(value, dict):
        raise Failure("INVALID_ENVIRONMENT")
    identity = uuid(value.get("id"))
    if expected_id is not None and identity != expected_id:
        raise Failure("ENVIRONMENT_ID_MISMATCH")
    if not isinstance(value.get("status"), str) or value["status"] not in STATES:
        raise Failure("UNKNOWN_ENVIRONMENT_STATUS")
    expires = timestamp(value.get("expiresAt"))
    if value.get("namespace") != "env-" + identity.replace("-", ""):
        raise Failure("INVALID_NAMESPACE")
    return identity, value["status"], expires


def reason(state, expires, cutoff):
    if state not in ELIGIBLE:
        return state
    if expires > cutoff:
        return "UNEXPIRED"
    return None


def base_url(value):
    try:
        parts = urlsplit(value)
        if (parts.scheme not in {"http", "https"} or parts.username is not None
                or parts.password is not None or parts.query or parts.fragment
                or parts.path not in {"/api/v1", "/api/v1/"}
                or any(ord(c) <= 32 or ord(c) >= 127 for c in value)):
            raise ValueError()
        host = parts.hostname
        if host == "localhost":
            host = "127.0.0.1"
        if "%" in host or not ipaddress.ip_address(host).is_loopback:
            raise ValueError()
        port = parts.port if parts.port is not None else (443 if parts.scheme == "https" else 80)
        if not 1 <= port <= 65535:
            raise ValueError()
        return parts.scheme, host, port
    except (ValueError, TypeError):
        raise argparse.ArgumentTypeError(
            "API URL must be http(s)://loopback:port/api/v1, without credentials, query or fragment") from None


def bounded_int(low, high):
    def parse(value):
        try:
            result = int(value)
            if low <= result <= high:
                return result
        except ValueError:
            pass
        raise argparse.ArgumentTypeError(f"must be an integer from {low} to {high}")
    return parse


def timeout(value):
    try:
        result = float(value)
        if math.isfinite(result) and 0 < result <= 60:
            return result
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("timeout must be finite, greater than 0 and at most 60 seconds")


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base-url", type=base_url,
                        default="http://127.0.0.1:8080/api/v1")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--execute", action="store_true", help="request deletion (default: dry-run)")
    mode.add_argument("--dry-run", action="store_true", help="explicitly select the default read-only mode")
    parser.add_argument("--json", action="store_true", help="print one machine-readable summary")
    parser.add_argument("--connect-timeout", type=timeout, default=3.0)
    parser.add_argument("--request-timeout", type=timeout, default=10.0)
    parser.add_argument("--page-size", type=bounded_int(1, 100), default=20)
    parser.add_argument("--max-pages", type=bounded_int(1, 1000), default=100)
    parser.add_argument("--max-scanned", type=bounded_int(1, 100000), default=1000)
    parser.add_argument("--max-actions", type=bounded_int(1, 1000), default=100)
    parser.add_argument("--get-attempts", type=bounded_int(1, 3), default=2)
    return parser.parse_args(argv)


class Client:
    def __init__(self, args):
        self.args = args

    def once(self, method, path):
        scheme, host, port = self.args.api_base_url
        connection_type = http.client.HTTPSConnection if scheme == "https" else http.client.HTTPConnection
        connection = connection_type(host, port, timeout=self.args.connect_timeout)
        timer = None
        expired = threading.Event()
        try:
            connection.connect()
            # A wall-clock request deadline also bounds slow headers/bodies.
            sock = connection.sock

            def interrupt():
                expired.set()
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            timer = threading.Timer(self.args.request_timeout, interrupt)
            timer.daemon = True
            timer.start()
            sock.settimeout(self.args.request_timeout)
            connection.request(method, "/api/v1" + path, headers={"Accept": "application/json"})
            response = connection.getresponse()
            body = response.read(MAX_BODY + 1)
            if expired.is_set():
                raise Failure("TRANSPORT_ERROR")
            if len(body) > MAX_BODY:
                raise Failure("RESPONSE_TOO_LARGE", response.status)
            request_id = safe_token(response.getheader("X-Request-Id"))
            try:
                data = json.loads(body, object_pairs_hook=unique_object)
            except (ValueError, UnicodeError, RecursionError):
                data = None
            if response.status not in ({200} if method == "GET" else {202}):
                code = data.get("code") if isinstance(data, dict) else None
                if not isinstance(code, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,63}", code):
                    code = None
                raise Failure(code or "HTTP_ERROR", response.status, request_id)
            if data is None:
                raise Failure("INVALID_JSON", response.status, request_id)
            return data
        except (OSError, http.client.HTTPException, ssl.SSLError):
            raise Failure("TRANSPORT_ERROR") from None
        finally:
            if timer:
                timer.cancel()
            connection.close()

    def get(self, path):
        for attempt in range(self.args.get_attempts):
            try:
                return self.once("GET", path)
            except Failure as error:
                if (error.code != "TRANSPORT_ERROR" and error.status not in {502, 503, 504, 500}
                        or attempt + 1 == self.args.get_attempts):
                    raise
                time.sleep(0.1 * (attempt + 1))

    def delete(self, identity, cutoff):
        # Never retry DELETE, including on transport failures or malformed 202.
        return self.once("DELETE", f"/environments/{identity}?" + urlencode({"expiredBefore": cutoff}))


def page_items(value, page, size):
    if not isinstance(value, dict):
        raise Failure("INVALID_PAGE")
    for key in ("page", "size", "totalElements", "totalPages"):
        if type(value.get(key)) is not int or value[key] < 0:
            raise Failure("INVALID_PAGE")
    if (value["page"] != page or value["size"] != size
            or value["totalPages"] != (value["totalElements"] + size - 1) // size
            or not isinstance(value.get("items"), list)
            or len(value["items"]) != min(size, max(0, value["totalElements"] - page * size))):
        raise Failure("INVALID_PAGE")
    return value["items"], value["totalPages"]


def acceptance(value, identity):
    if (not isinstance(value, dict) or value.get("environmentId") != identity
            or value.get("environmentStatus") != "DELETING"
            or not isinstance(value.get("operationStatus"), str)
            or value["operationStatus"] not in {"PENDING", "RUNNING"}):
        raise Failure("INVALID_ACCEPTANCE")
    return uuid(value.get("operationId"))


def recovered_operation(value, state):
    operation = value.get("latestOperation")
    expected = {"PENDING", "RUNNING"} if state == "DELETING" else {"SUCCEEDED"}
    if (not isinstance(operation, dict) or operation.get("type") != "DELETE_ENVIRONMENT"
            or not isinstance(operation.get("status"), str) or operation["status"] not in expected):
        raise Failure("INVALID_DELETE_OPERATION")
    return uuid(operation.get("id"))


def run(args, clock=lambda: datetime.now(timezone.utc), client=None):
    cutoff = clock()
    if not isinstance(cutoff, datetime) or cutoff.tzinfo is None or cutoff.utcoffset() is None:
        raise ValueError("clock must return a timezone-aware datetime")
    cutoff = cutoff.astimezone(timezone.utc)
    stamp = cutoff.isoformat().replace("+00:00", "Z")
    client = client or Client(args)
    result = {
        "cutoff": stamp, "mode": "execute" if args.execute else "dry-run",
        "policy": "READY/FAILED only; DELETE_FAILED requires manual investigation and retry",
        "scanComplete": False, "snapshotConsistent": False,
        "scanned": 0, "duplicates": 0, "eligible": 0, "skipped": 0,
        "requested": 0, "alreadyDeleting": 0, "alreadyDeleted": 0,
        "failed": 0, "unknown": 0, "skipReasons": {}, "selectedIds": [],
        "acceptedOperations": [], "errors": [],
    }
    reasons = Counter()
    seen = set()

    def error_record(error, identity=None):
        result["errors"].append({**error.public(), **({"environmentId": identity} if identity else {})})

    try:
        for page in range(args.max_pages):
            items, total_pages = page_items(client.get(
                f"/environments?{urlencode({'page': page, 'size': args.page_size})}"),
                page, args.page_size)
            for item in items:
                if result["scanned"] >= args.max_scanned:
                    raise Failure("SCAN_LIMIT")
                result["scanned"] += 1
                try:
                    identity, state, expires = environment(item)
                except Failure as error:
                    result["failed"] += 1
                    error_record(error)
                    continue
                if identity in seen:
                    result["duplicates"] += 1
                    continue
                seen.add(identity)
                skip = reason(state, expires, cutoff)
                if skip:
                    result["skipped"] += 1
                    reasons[skip] += 1
                else:
                    result["selectedIds"].append(identity)
            if page + 1 >= total_pages:
                result["scanComplete"] = True
                break
            if result["scanned"] >= args.max_scanned:
                raise Failure("SCAN_LIMIT")
        else:
            raise Failure("PAGE_LIMIT")
    except Failure as error:
        result["failed"] += 1
        error_record(error)

    result["eligible"] = len(result["selectedIds"])
    if result["eligible"] > args.max_actions:
        result["failed"] += 1
        error_record(Failure("ACTION_LIMIT"))

    # Discover fully before mutating. Invalid/incomplete scans or an action limit
    # abort all mutations rather than partially applying an unexpected plan.
    if args.execute and result["scanComplete"] and not result["failed"]:
        for identity in result["selectedIds"]:
            try:
                current = client.get(f"/environments/{identity}")
                _, state, expires = environment(current, identity)
                skip = reason(state, expires, cutoff)
                if skip:
                    result["skipped"] += 1
                    reasons["RECHECK_" + skip] += 1
                    continue
            except Failure as error:
                result["failed"] += 1
                error_record(error, identity)
                continue
            try:
                operation = acceptance(client.delete(identity, stamp), identity)
                result["requested"] += 1
                result["acceptedOperations"].append(
                    {"environmentId": identity, "operationId": operation, "source": "202"})
            except Failure as error:
                # A failed response is not proof that the server did not commit.
                # Observe once (bounded GET retries), but NEVER resubmit DELETE.
                try:
                    current = client.get(f"/environments/{identity}")
                    _, state, _ = environment(current, identity)
                    if state in {"DELETING", "DELETED"}:
                        operation = recovered_operation(current, state)
                        result["alreadyDeleting" if state == "DELETING" else "alreadyDeleted"] += 1
                        result["acceptedOperations"].append({
                            "environmentId": identity, "operationId": operation,
                            "source": "recovery", "observedStatus": state})
                        continue
                    ambiguous = (error.status is None or error.status < 300
                                 or error.status >= 500 or error.status == 408)
                    # A recovery read can overtake a still-running DELETE. An
                    # unchanged create state does not prove nonacceptance.
                    result["unknown" if ambiguous and state != "DELETE_FAILED" else "failed"] += 1
                    error_record(error, identity)
                except Failure as recovery_error:
                    result["unknown"] += 1
                    error_record(error, identity)
                    error_record(recovery_error, identity)

    result["skipReasons"] = dict(reasons)
    result["exitStatus"] = 1 if result["failed"] or result["unknown"] or not result["scanComplete"] else 0
    return result


def main(argv=None):
    args = arguments(argv)
    result = run(args)
    if args.json:
        print(json.dumps(result, sort_keys=True))
    else:
        print(f"{result['mode']} cutoff={result['cutoff']}")
        print(result["policy"])
        for identity in result["selectedIds"]:
            print(f"selected {identity}: request asynchronous deletion")
        for operation in result["acceptedOperations"]:
            print(f"operation {operation['environmentId']} -> {operation['operationId']} ({operation['source']})")
        print(json.dumps({key: value for key, value in result.items()
                          if key not in {"selectedIds", "acceptedOperations", "policy"}}, sort_keys=True))
    return result["exitStatus"]


if __name__ == "__main__":
    raise SystemExit(main())
