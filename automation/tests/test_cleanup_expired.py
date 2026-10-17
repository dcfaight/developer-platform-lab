"""Safety contracts for the dependency-free expiration cleanup command."""

from collections import deque
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import threading
import time
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit


SCRIPT = Path(__file__).resolve().parents[1] / "cleanup_expired.py"
SPEC = importlib.util.spec_from_file_location("cleanup_expired_under_test", SCRIPT)
cleanup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cleanup)

CUTOFF = datetime(2026, 10, 16, 12, 30, tzinfo=timezone.utc)
STAMP = "2026-10-16T12:30:00Z"
IDENTITY = "11111111-1111-4111-8111-111111111111"
OTHER = "22222222-2222-4222-8222-222222222222"
THIRD = "33333333-3333-4333-8333-333333333333"
OPERATION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
SECRET = "sensitive-response-body-do-not-print"
CLEANUP_USER = "cleanup-bot"
CLEANUP_PASSWORD = "test-only-cleanup-password"
CREDENTIALS = {
    "PLATFORM_CLEANUP_USERNAME": CLEANUP_USER,
    "PLATFORM_CLEANUP_PASSWORD": CLEANUP_PASSWORD,
}
EXPECTED_AUTHORIZATION = "Basic " + base64.b64encode(
    f"{CLEANUP_USER}:{CLEANUP_PASSWORD}".encode()
).decode()


def item(identity=IDENTITY, state="READY", expires=STAMP, **changes):
    value = {
        "id": identity,
        "status": state,
        "expiresAt": expires,
        "namespace": "env-" + identity.replace("-", ""),
    }
    value.update(changes)
    return value


def page(items, number=0, size=20, total=None):
    total = len(items) if total is None else total
    return {
        "items": items,
        "page": number,
        "size": size,
        "totalElements": total,
        "totalPages": (total + size - 1) // size,
    }


def accepted(identity=IDENTITY, operation=OPERATION, status="PENDING"):
    return {
        "environmentId": identity,
        "environmentStatus": "DELETING",
        "operationId": operation,
        "operationStatus": status,
    }


def recovering(state="DELETING", operation=OPERATION, **changes):
    return item(
        state=state,
        latestOperation={
            "id": operation,
            "type": "DELETE_ENVIRONMENT",
            "status": "SUCCEEDED" if state == "DELETED" else "RUNNING",
            **changes,
        },
    )


class FakeClient:
    def __init__(self, gets, deletes=()):
        self.gets = deque(gets)
        self.deletes = deque(deletes)
        self.calls = []

    def take(self, responses):
        if not responses:
            raise AssertionError("unexpected request: " + repr(self.calls))
        response = responses.popleft()
        if isinstance(response, Exception):
            raise response
        return response

    def get(self, path):
        self.calls.append(("GET", path))
        return self.take(self.gets)

    def delete(self, identity, cutoff):
        self.calls.append(("DELETE", identity, cutoff))
        return self.take(self.deletes)


@contextmanager
def http_server(responder):
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_request(self):
            requests.append((self.command, self.path, dict(self.headers)))
            reply = responder(self, len(requests))
            if reply is None:
                return
            status, data, headers = reply
            body = data if isinstance(data, bytes) else json.dumps(data).encode()
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                for key, value in headers.items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

        do_GET = handle_request
        do_DELETE = handle_request

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(
        target=lambda: server.serve_forever(poll_interval=0.01), daemon=True
    )
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/api/v1", requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class CleanupTests(unittest.TestCase):
    def args(self, *flags):
        return cleanup.arguments(list(flags))

    def run_cleanup(self, client, *flags, clock=None):
        return cleanup.run(
            self.args(*flags), clock=clock or (lambda: CUTOFF), client=client
        )

    def codes(self, result):
        return [error["code"] for error in result["errors"]]

    def assert_no_delete(self, client):
        self.assertFalse(any(call[0] == "DELETE" for call in client.calls))

    def test_default_is_dry_run_even_with_eligible_records(self):
        client = FakeClient([page([item(), item(OTHER, "FAILED")])])
        result = self.run_cleanup(client)
        self.assertEqual(result["mode"], "dry-run")
        self.assertEqual(result["selectedIds"], [IDENTITY, OTHER])
        self.assertEqual(result["eligible"], 2)
        self.assertEqual(result["requested"], 0)
        self.assertEqual(result["exitStatus"], 0)
        self.assertTrue(result["scanComplete"])
        self.assertFalse(result["snapshotConsistent"])
        self.assertIn("DELETE_FAILED", result["policy"])
        self.assert_no_delete(client)
        self.assertEqual(len(client.calls), 1)

    def test_explicit_dry_run_is_read_only(self):
        client = FakeClient([page([item()])])
        result = self.run_cleanup(client, "--dry-run")
        self.assertEqual(result["mode"], "dry-run")
        self.assert_no_delete(client)

    def test_clock_is_called_once_and_normalized_to_utc(self):
        calls = []

        def clock():
            calls.append(True)
            return CUTOFF.astimezone(timezone(timedelta(hours=5, minutes=30)))

        result = self.run_cleanup(FakeClient([page([item()])]), clock=clock)
        self.assertEqual(calls, [True])
        self.assertEqual(result["cutoff"], STAMP)
        self.assertEqual(result["selectedIds"], [IDENTITY])

    def test_invalid_clocks_fail_before_network(self):
        for value in (None, STAMP, datetime(2026, 1, 1)):
            with self.subTest(value=value):
                client = FakeClient([])
                with self.assertRaises(ValueError):
                    self.run_cleanup(client, clock=lambda: value)
                self.assertEqual(client.calls, [])

    def test_inclusive_expiry_offset_and_nanosecond_boundary(self):
        for expires, eligible in (
            ("2026-10-16T12:29:59.999999999Z", True),
            (STAMP, True),
            ("2026-10-16T14:30:00+02:00", True),
            ("2026-10-16T07:00:00-05:30", True),
            ("2026-10-16T12:30:00.000000000Z", True),
            ("2026-10-16T12:30:00.000000001Z", False),
            ("2026-10-16T14:30:00.000000001+02:00", False),
            ("2026-10-16T12:30:00.000001Z", False),
        ):
            with self.subTest(expires=expires):
                result = self.run_cleanup(FakeClient([page([item(expires=expires)])]))
                self.assertEqual(result["eligible"], int(eligible))
                self.assertEqual(result["skipped"], int(not eligible))
                self.assertEqual(result["exitStatus"], 0)
                if not eligible:
                    self.assertEqual(result["skipReasons"], {"UNEXPIRED": 1})

    def test_fractional_cutoff_does_not_round_future_expiry_down(self):
        clock = lambda: CUTOFF.replace(microsecond=123456)
        for fraction, eligible in (
            ("123455999", True),
            ("123456000", True),
            ("123456001", False),
        ):
            with self.subTest(fraction=fraction):
                result = self.run_cleanup(
                    FakeClient([page([item(expires=f"2026-10-16T12:30:00.{fraction}Z")])]),
                    clock=clock,
                )
                self.assertEqual(result["eligible"], int(eligible))

    def test_every_lifecycle_state(self):
        for state in sorted(cleanup.STATES):
            with self.subTest(state=state):
                client = FakeClient([page([item(state=state)])])
                result = self.run_cleanup(client)
                eligible = state in {"READY", "FAILED"}
                self.assertEqual(result["eligible"], int(eligible))
                self.assertEqual(result["skipReasons"], {} if eligible else {state: 1})
                self.assertEqual(result["exitStatus"], 0)
                self.assert_no_delete(client)

    def test_multiple_pages_deduplicate_and_keep_order(self):
        client = FakeClient([
            page([item(), item(OTHER)], size=2, total=4),
            page([item(), item(THIRD, "FAILED")], number=1, size=2, total=4),
        ])
        result = self.run_cleanup(client, "--page-size", "2")
        self.assertEqual(result["selectedIds"], [IDENTITY, OTHER, THIRD])
        self.assertEqual(result["scanned"], 4)
        self.assertEqual(result["duplicates"], 1)
        self.assertTrue(result["scanComplete"])
        self.assertEqual(client.calls, [
            ("GET", "/environments?page=0&size=2"),
            ("GET", "/environments?page=1&size=2"),
        ])

    def test_duplicate_ids_are_deleted_only_once(self):
        client = FakeClient(
            [page([item(), item()]), item()], [accepted()]
        )
        result = self.run_cleanup(client, "--execute")
        self.assertEqual(result["duplicates"], 1)
        self.assertEqual(result["requested"], 1)
        self.assertEqual(
            [call for call in client.calls if call[0] == "DELETE"],
            [("DELETE", IDENTITY, STAMP)],
        )

    def test_empty_scan_succeeds_without_detail_requests(self):
        client = FakeClient([page([])])
        result = self.run_cleanup(client, "--execute")
        self.assertEqual(result["scanned"], 0)
        self.assertEqual(result["exitStatus"], 0)
        self.assertTrue(result["scanComplete"])
        self.assertEqual(len(client.calls), 1)

    def test_invalid_page_metadata_aborts_all_mutation(self):
        good = page([item()])
        malformed = [
            None, [], {}, {**good, "page": True}, {**good, "size": True},
            {**good, "totalElements": True}, {**good, "totalPages": True},
            {**good, "page": -1}, {**good, "page": 1},
            {**good, "size": 0}, {**good, "size": 19},
            {**good, "totalElements": -1}, {**good, "totalPages": -1},
            {**good, "totalPages": 2}, {**good, "totalElements": 2},
            {**good, "items": None}, {**good, "items": {}},
            {**good, "items": []},
        ]
        for value in malformed:
            with self.subTest(value=value):
                client = FakeClient([value])
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(self.codes(result), ["INVALID_PAGE"])
                self.assertFalse(result["scanComplete"])
                self.assertEqual(result["exitStatus"], 1)
                self.assert_no_delete(client)

    def test_malformed_later_page_prevents_deleting_earlier_candidates(self):
        client = FakeClient([
            page([item()], size=1, total=2),
            page([item(OTHER)], number=0, size=1, total=2),
        ])
        result = self.run_cleanup(client, "--execute", "--page-size", "1")
        self.assertEqual(result["selectedIds"], [IDENTITY])
        self.assertEqual(self.codes(result), ["INVALID_PAGE"])
        self.assert_no_delete(client)

    def test_invalid_environment_records_abort_mutation_but_scan_continues(self):
        malformed = [
            (None, "INVALID_ENVIRONMENT"),
            ([], "INVALID_ENVIRONMENT"),
            ({}, "INVALID_UUID"),
            (item(id=None), "INVALID_UUID"),
            (item(id="not-a-uuid"), "INVALID_UUID"),
            (item(id=IDENTITY.replace("-", "")), "INVALID_UUID"),
            (item(id=OPERATION.upper()), "INVALID_UUID"),
            (item(namespace="default"), "INVALID_NAMESPACE"),
            (item(namespace="env-" + IDENTITY), "INVALID_NAMESPACE"),
            (item(namespace=None), "INVALID_NAMESPACE"),
            (item(state="UNKNOWN"), "UNKNOWN_ENVIRONMENT_STATUS"),
            (item(state=None), "UNKNOWN_ENVIRONMENT_STATUS"),
            (item(state=[]), "UNKNOWN_ENVIRONMENT_STATUS"),
            (item(state={}), "UNKNOWN_ENVIRONMENT_STATUS"),
        ]
        for value, code in malformed:
            with self.subTest(value=value):
                client = FakeClient([page([value, item(OTHER)])])
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(self.codes(result), [code])
                self.assertEqual(result["selectedIds"], [OTHER])
                self.assertEqual(result["scanned"], 2)
                self.assertTrue(result["scanComplete"])
                self.assertEqual(result["exitStatus"], 1)
                self.assert_no_delete(client)

    def test_invalid_timestamps_fail_closed(self):
        for expires in (
            None, 42, "", "2026-10-16", "2026-10-16T12:30:00",
            "2026-10-16 12:30:00Z", "2026-10-16T12:30:00z",
            "2026-10-16T12:30:00+0000", "2026-10-16T12:30:00.1234567890Z",
            "2026-02-30T12:30:00Z", "2026-10-16T24:00:00Z",
            "2026-10-16T12:30:60Z", "2026-10-16T12:30:00+24:00",
            "2026-10-16T12:30:00+00:60", "2026-10-16T12:30:00-01:99",
            "9999-12-31T23:59:59.999999999Z",
        ):
            with self.subTest(expires=expires):
                client = FakeClient([page([item(expires=expires)]), item()], [accepted()])
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(self.codes(result), ["INVALID_TIMESTAMP"])
                self.assertEqual(result["selectedIds"], [])
                self.assert_no_delete(client)

    def test_max_pages_bounds_discovery_and_prevents_mutation(self):
        client = FakeClient([page([item()], size=1, total=2)])
        result = self.run_cleanup(
            client, "--execute", "--page-size", "1", "--max-pages", "1"
        )
        self.assertEqual(self.codes(result), ["PAGE_LIMIT"])
        self.assertEqual(len(client.calls), 1)
        self.assertFalse(result["scanComplete"])
        self.assert_no_delete(client)

    def test_max_scanned_bounds_within_page(self):
        client = FakeClient([page([item(), item(OTHER)])])
        result = self.run_cleanup(client, "--execute", "--max-scanned", "1")
        self.assertEqual(result["scanned"], 1)
        self.assertEqual(self.codes(result), ["SCAN_LIMIT"])
        self.assert_no_delete(client)

    def test_max_scanned_bounds_between_pages_and_counts_duplicates(self):
        client = FakeClient([page([item(), item()], size=2, total=3)])
        result = self.run_cleanup(
            client, "--execute", "--page-size", "2", "--max-scanned", "2"
        )
        self.assertEqual(result["scanned"], 2)
        self.assertEqual(result["duplicates"], 1)
        self.assertEqual(self.codes(result), ["SCAN_LIMIT"])
        self.assertEqual(len(client.calls), 1)
        self.assert_no_delete(client)

    def test_scan_at_exact_limits_is_complete(self):
        result = self.run_cleanup(
            FakeClient([page([item()])]), "--max-scanned", "1", "--max-pages", "1"
        )
        self.assertTrue(result["scanComplete"])
        self.assertEqual(result["exitStatus"], 0)

    def test_max_actions_aborts_whole_plan_in_both_modes(self):
        for mode in ("--execute", "--dry-run"):
            with self.subTest(mode=mode):
                client = FakeClient([page([item(), item(OTHER)])])
                result = self.run_cleanup(client, mode, "--max-actions", "1")
                self.assertEqual(result["eligible"], 2)
                self.assertEqual(self.codes(result), ["ACTION_LIMIT"])
                self.assertTrue(result["scanComplete"])
                self.assert_no_delete(client)

    def test_action_at_exact_limit_is_accepted(self):
        client = FakeClient([page([item()]), item()], [accepted(status="RUNNING")])
        result = self.run_cleanup(client, "--execute", "--max-actions", "1")
        self.assertEqual(result["requested"], 1)
        self.assertEqual(result["exitStatus"], 0)
        self.assertEqual(result["acceptedOperations"], [{
            "environmentId": IDENTITY, "operationId": OPERATION, "source": "202",
        }])

    def test_recheck_every_noneligible_state_and_extension_before_delete(self):
        for current, skip in [
            *[(item(state=state), state) for state in sorted(cleanup.STATES - cleanup.ELIGIBLE)],
            (item(expires="2026-10-16T12:30:00.000000001Z"), "UNEXPIRED"),
        ]:
            with self.subTest(skip=skip):
                client = FakeClient([page([item()]), current])
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(result["skipReasons"], {"RECHECK_" + skip: 1})
                self.assertEqual(result["requested"], 0)
                self.assertEqual(result["exitStatus"], 0)
                self.assert_no_delete(client)

    def test_recheck_permits_transition_between_eligible_states(self):
        client = FakeClient([page([item()]), item(state="FAILED")], [accepted()])
        result = self.run_cleanup(client, "--execute")
        self.assertEqual(result["requested"], 1)
        self.assertEqual(client.calls[1], ("GET", "/environments/" + IDENTITY))
        self.assertEqual(client.calls[2], ("DELETE", IDENTITY, STAMP))

    def test_recheck_invalid_or_missing_record_does_not_delete(self):
        for current, code in (
            (item(OTHER), "ENVIRONMENT_ID_MISMATCH"),
            (item(namespace="kube-system"), "INVALID_NAMESPACE"),
            (item(state="UNKNOWN"), "UNKNOWN_ENVIRONMENT_STATUS"),
            (cleanup.Failure("NOT_FOUND", 404), "NOT_FOUND"),
            (cleanup.Failure("TRANSPORT_ERROR"), "TRANSPORT_ERROR"),
        ):
            with self.subTest(code=code):
                client = FakeClient([page([item()]), current])
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(self.codes(result), [code])
                self.assertEqual(result["errors"][0]["environmentId"], IDENTITY)
                self.assertEqual(result["exitStatus"], 1)
                self.assert_no_delete(client)

    def test_failure_of_one_recheck_does_not_prevent_other_candidate(self):
        client = FakeClient([
            page([item(), item(OTHER)]),
            cleanup.Failure("NOT_FOUND", 404),
            item(OTHER),
        ], [accepted(OTHER)])
        result = self.run_cleanup(client, "--execute")
        self.assertEqual(result["failed"], 1)
        self.assertEqual(result["requested"], 1)
        self.assertEqual(result["acceptedOperations"][0]["environmentId"], OTHER)

    def test_recheck_and_recovery_use_per_id_detail_not_full_list_scans(self):
        client = FakeClient([
            page([item()], size=1),
            item(),
            recovering(),
        ], [cleanup.Failure("TRANSPORT_ERROR")])
        result = self.run_cleanup(client, "--execute", "--page-size", "1")
        self.assertEqual(result["alreadyDeleting"], 1)
        self.assertEqual(result["exitStatus"], 0)
        self.assertEqual([call for call in client.calls if call[0] == "GET"], [
            ("GET", "/environments?page=0&size=1"),
            ("GET", "/environments/" + IDENTITY),
            ("GET", "/environments/" + IDENTITY),
        ])

    def test_ambiguous_delete_recovers_operation_and_never_resubmits(self):
        for failure in (
            cleanup.Failure("NOT_FOUND", 404),
            cleanup.Failure("CONFLICT", 409),
            cleanup.Failure("UNAVAILABLE", 503),
            cleanup.Failure("TRANSPORT_ERROR"),
            cleanup.Failure("INVALID_ACCEPTANCE"),
        ):
            for state in ("DELETING", "DELETED"):
                with self.subTest(code=failure.code, state=state):
                    client = FakeClient(
                        [page([item()]), item(), recovering(state)], [failure]
                    )
                    result = self.run_cleanup(client, "--execute")
                    self.assertEqual(result["exitStatus"], 0)
                    self.assertEqual(result["requested"], 0)
                    self.assertEqual(result["alreadyDeleting"], int(state == "DELETING"))
                    self.assertEqual(result["alreadyDeleted"], int(state == "DELETED"))
                    self.assertEqual(result["acceptedOperations"], [{
                        "environmentId": IDENTITY, "operationId": OPERATION,
                        "source": "recovery", "observedStatus": state,
                    }])
                    self.assertEqual(result["errors"], [])
                    self.assertEqual(
                        sum(call[0] == "DELETE" for call in client.calls), 1
                    )
                    self.assertFalse(client.gets)

    def test_malformed_acceptances_recover_without_trusting_response_ids(self):
        good = accepted()
        for response in (
            None, [], {}, {**good, "environmentId": OTHER},
            {**good, "environmentId": []},
            {**good, "environmentStatus": "DELETED"},
            {**good, "environmentStatus": []},
            {**good, "operationStatus": "SUCCEEDED"},
            {**good, "operationStatus": []},
            {**good, "operationStatus": {}},
            {**good, "operationId": "not-a-uuid"},
            {**good, "operationId": OPERATION.upper()},
            {**good, "operationId": []},
        ):
            with self.subTest(response=response):
                client = FakeClient(
                    [page([item()]), item(), recovering()], [response]
                )
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(result["exitStatus"], 0)
                self.assertEqual(result["acceptedOperations"][0]["operationId"], OPERATION)
                self.assertEqual(result["acceptedOperations"][0]["environmentId"], IDENTITY)
                self.assertEqual(result["acceptedOperations"][0]["source"], "recovery")
                self.assertEqual(sum(call[0] == "DELETE" for call in client.calls), 1)

    def test_uncommitted_delete_failure_stays_failed_and_never_retries(self):
        for status, code in ((404, "NOT_FOUND"), (409, "CONFLICT")):
            with self.subTest(status=status):
                client = FakeClient(
                    [page([item()]), item(), item()],
                    [cleanup.Failure(code, status, "request-123")],
                )
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(result["failed"], 1)
                self.assertEqual(result["unknown"], 0)
                self.assertEqual(result["exitStatus"], 1)
                self.assertEqual(result["errors"], [{
                    "code": code, "httpStatus": status,
                    "requestId": "request-123", "environmentId": IDENTITY,
                }])
                self.assertEqual(sum(call[0] == "DELETE" for call in client.calls), 1)

    def test_ambiguous_delete_with_unchanged_recovery_is_unknown_not_retried(self):
        for failure in (
            cleanup.Failure("TRANSPORT_ERROR"),
            cleanup.Failure("INVALID_ACCEPTANCE"),
            cleanup.Failure("INVALID_JSON", 202),
            cleanup.Failure("HTTP_ERROR", 408),
            cleanup.Failure("HTTP_ERROR", 500),
            cleanup.Failure("HTTP_ERROR", 503),
        ):
            with self.subTest(code=failure.code, status=failure.status):
                client = FakeClient(
                    [page([item()]), item(), item()], [failure]
                )
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(result["unknown"], 1)
                self.assertEqual(result["failed"], 0)
                self.assertEqual(result["exitStatus"], 1)
                self.assertEqual(result["acceptedOperations"], [])
                self.assertEqual(result["errors"][0]["environmentId"], IDENTITY)
                self.assertEqual(sum(call[0] == "DELETE" for call in client.calls), 1)

    def test_terminal_delete_failed_is_not_retried_even_after_ambiguous_delete(self):
        client = FakeClient(
            [page([item()]), item(), item(state="DELETE_FAILED")],
            [cleanup.Failure("TRANSPORT_ERROR")],
        )
        result = self.run_cleanup(client, "--execute")
        self.assertEqual(result["failed"], 1)
        self.assertEqual(result["acceptedOperations"], [])
        self.assertEqual(sum(call[0] == "DELETE" for call in client.calls), 1)
        terminal = FakeClient([page([item(state="DELETE_FAILED")])])
        result = self.run_cleanup(terminal, "--execute")
        self.assertEqual(result["skipReasons"], {"DELETE_FAILED": 1})
        self.assert_no_delete(terminal)

    def test_unverifiable_recovery_is_unknown_preserving_original_identity(self):
        for recovery in (
            cleanup.Failure("NOT_FOUND", 404),
            cleanup.Failure("TRANSPORT_ERROR"),
            item(OTHER, "DELETING"),
            item(state="DELETING"),
            item(state="DELETING", latestOperation=[]),
            item(state="DELETING", latestOperation=None),
            recovering(operation="bad"),
            recovering(type="CREATE_ENVIRONMENT"),
            recovering(status="SUCCEEDED"),
            recovering(status=[]),
            recovering(status={}),
            recovering("DELETED", status="RUNNING"),
        ):
            with self.subTest(recovery=recovery):
                client = FakeClient(
                    [page([item()]), item(), recovery],
                    [cleanup.Failure("TRANSPORT_ERROR")],
                )
                result = self.run_cleanup(client, "--execute")
                self.assertEqual(result["unknown"], 1)
                self.assertEqual(result["exitStatus"], 1)
                self.assertEqual(result["acceptedOperations"], [])
                self.assertEqual(result["errors"][0]["code"], "TRANSPORT_ERROR")
                self.assertTrue(all(
                    error["environmentId"] == IDENTITY for error in result["errors"]
                ))
                self.assertEqual(sum(call[0] == "DELETE" for call in client.calls), 1)


class ArgumentTests(unittest.TestCase):
    def test_default_url_and_safe_loopback_variants(self):
        self.assertEqual(
            cleanup.arguments([]).api_base_url, ("http", "127.0.0.1", 8080)
        )
        for value, expected in (
            ("http://localhost:8080/api/v1", ("http", "127.0.0.1", 8080)),
            ("http://127.0.0.2:1234/api/v1/", ("http", "127.0.0.2", 1234)),
            ("https://[::1]/api/v1", ("https", "::1", 443)),
        ):
            with self.subTest(value=value):
                self.assertEqual(cleanup.base_url(value), expected)

    def test_cli_rejects_invalid_arguments_before_any_network(self):
        invalid = [
            ["--api-base-url", value] for value in (
                "https://example.com/api/v1", "http://0.0.0.0/api/v1",
                "http://192.168.1.2/api/v1", "http://[::]/api/v1",
                "http://[::1%25lo]/api/v1",
                "http://localhost.example.com/api/v1",
                "http://user@127.0.0.1/api/v1",
                "http://:password@127.0.0.1/api/v1",
                "http://127.0.0.1/api/v1?x=1", "http://127.0.0.1/api/v1#x",
                "http://127.0.0.1", "http://127.0.0.1/wrong",
                "http://127.0.0.1:0/api/v1", "http://127.0.0.1:65536/api/v1",
                "http://127.0.0.1:abc/api/v1", "ftp://127.0.0.1/api/v1",
                "http://127.0.0.1/api/v1\n", "http://127.0.0.1/api/v1%2f",
            )
        ]
        invalid.extend([
            ["--execute", "--dry-run"],
            ["--unknown-option"],
        ])
        for flag in ("--connect-timeout", "--request-timeout"):
            invalid.extend([flag, value] for value in ("0", "-1", "nan", "inf", "61", "bad"))
        for flag, upper in (
            ("--page-size", 100), ("--max-pages", 1000),
            ("--max-scanned", 100000), ("--max-actions", 1000),
            ("--get-attempts", 3),
        ):
            invalid.extend([flag, value] for value in ("0", "-1", str(upper + 1), "1.5", "bad"))
        with patch.object(cleanup.Client, "__init__", side_effect=AssertionError("network")):
            for flags in invalid:
                with self.subTest(flags=flags), patch("sys.stderr"):
                    with self.assertRaises(SystemExit) as raised:
                        cleanup.main(flags)
                    self.assertEqual(raised.exception.code, 2)

    def test_valid_numeric_bounds(self):
        args = cleanup.arguments([
            "--page-size", "100", "--max-pages", "1000", "--max-scanned", "100000",
            "--max-actions", "1000", "--get-attempts", "3",
            "--connect-timeout", "0.001", "--request-timeout", "60",
        ])
        self.assertEqual(args.page_size, 100)
        self.assertEqual(args.max_pages, 1000)
        self.assertEqual(args.max_scanned, 100000)
        self.assertEqual(args.max_actions, 1000)
        self.assertEqual(args.get_attempts, 3)
        self.assertEqual(args.connect_timeout, 0.001)
        self.assertEqual(args.request_timeout, 60)


class CredentialTests(unittest.TestCase):
    def test_basic_authorization_uses_environment_values(self):
        self.assertEqual(cleanup.basic_authorization(CREDENTIALS), EXPECTED_AUTHORIZATION)

    def test_invalid_credentials_are_rejected_without_echoing_values(self):
        for environ in (
            {}, {"PLATFORM_CLEANUP_USERNAME": CLEANUP_USER},
            {**CREDENTIALS, "PLATFORM_CLEANUP_PASSWORD": ""},
            {**CREDENTIALS, "PLATFORM_CLEANUP_PASSWORD": "x" * 1025},
            {**CREDENTIALS, "PLATFORM_CLEANUP_USERNAME": "-leading"},
            {**CREDENTIALS, "PLATFORM_CLEANUP_USERNAME": "a:b"},
        ):
            with self.subTest(environ=sorted(environ)):
                with self.assertRaises(ValueError) as raised:
                    cleanup.basic_authorization(environ)
                self.assertNotIn(CLEANUP_PASSWORD, str(raised.exception))

    def test_both_modes_require_credentials_before_any_network(self):
        for flags in ([], ["--dry-run"], ["--execute"]):
            with self.subTest(flags=flags):
                with patch.object(cleanup.Client, "__init__", side_effect=AssertionError("network")):
                    with patch("sys.stderr"):
                        self.assertEqual(cleanup.main(flags, environ={}), 2)

    def test_list_detail_and_delete_carry_authorization(self):
        client = cleanup.Client(cleanup.arguments(["--execute"]), EXPECTED_AUTHORIZATION)
        for method, path, expected in (
            ("GET", "/environments",
             {"Accept": "application/json", "Authorization": EXPECTED_AUTHORIZATION}),
            ("GET", "/environments/" + IDENTITY,
             {"Accept": "application/json", "Authorization": EXPECTED_AUTHORIZATION}),
            ("DELETE", "/environments/" + IDENTITY,
             {"Accept": "application/json", "Authorization": EXPECTED_AUTHORIZATION}),
        ):
            with self.subTest(method=method):
                with patch.object(cleanup.http.client, "HTTPConnection") as connection_type:
                    connection = connection_type.return_value
                    connection.getresponse.return_value.status = 200 if method == "GET" else 202
                    connection.getresponse.return_value.read.return_value = b"{}"
                    connection.getresponse.return_value.getheader.return_value = None
                    client.once(method, path)
                connection.request.assert_called_once_with(method, "/api/v1" + path, headers=expected)


class ClientTests(unittest.TestCase):
    def make_client(self, attempts=3):
        return cleanup.Client(cleanup.arguments(["--get-attempts", str(attempts)]))

    def test_get_retries_only_transient_statuses_and_transport_failures(self):
        for failure in (
            cleanup.Failure("TRANSPORT_ERROR"),
            *[cleanup.Failure("HTTP_ERROR", status) for status in (500, 502, 503, 504)],
        ):
            with self.subTest(code=failure.code, status=failure.status):
                client = self.make_client()
                with patch.object(client, "once", side_effect=[failure, {"ok": True}]) as once:
                    with patch.object(cleanup.time, "sleep"):
                        self.assertEqual(client.get("/environments"), {"ok": True})
                self.assertEqual(once.call_count, 2)

    def test_get_retry_budget_is_bounded(self):
        client = self.make_client()
        failure = cleanup.Failure("TRANSPORT_ERROR")
        with patch.object(client, "once", side_effect=failure) as once:
            with patch.object(cleanup.time, "sleep") as sleep:
                with self.assertRaises(cleanup.Failure):
                    client.get("/environments")
        self.assertEqual(once.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_connection_and_request_timeouts_are_distinct_and_socket_is_closed(self):
        args = cleanup.arguments(["--connect-timeout", "0.12", "--request-timeout", "0.34"])
        client = cleanup.Client(args)
        with patch.object(cleanup.http.client, "HTTPConnection") as connection_type:
            connection = connection_type.return_value
            connection.getresponse.return_value.status = 200
            connection.getresponse.return_value.read.return_value = b"{}"
            connection.getresponse.return_value.getheader.return_value = None
            self.assertEqual(client.once("GET", "/environments"), {})
        connection_type.assert_called_once_with("127.0.0.1", 8080, timeout=0.12)
        connection.connect.assert_called_once_with()
        connection.sock.settimeout.assert_called_once_with(0.34)
        connection.request.assert_called_once_with(
            "GET", "/api/v1/environments", headers={"Accept": "application/json"}
        )
        connection.close.assert_called_once_with()

    def test_connect_timeout_is_sanitized_and_connection_closed(self):
        client = self.make_client()
        with patch.object(cleanup.http.client, "HTTPConnection") as connection_type:
            connection = connection_type.return_value
            connection.connect.side_effect = TimeoutError(SECRET)
            with self.assertRaises(cleanup.Failure) as raised:
                client.once("GET", "/environments")
        self.assertEqual(raised.exception.public(), {"code": "TRANSPORT_ERROR"})
        connection.close.assert_called_once_with()

    def test_incomplete_http_response_is_sanitized_and_connection_closed(self):
        client = self.make_client()
        with patch.object(cleanup.http.client, "HTTPConnection") as connection_type:
            connection = connection_type.return_value
            connection.getresponse.side_effect = http.client.IncompleteRead(SECRET.encode())
            with self.assertRaises(cleanup.Failure) as raised:
                client.once("GET", "/environments")
        self.assertEqual(raised.exception.public(), {"code": "TRANSPORT_ERROR"})
        connection.close.assert_called_once_with()

    def test_nontransient_errors_and_redirects_are_never_retried(self):
        for status in (200, 301, 302, 303, 307, 308, 400, 401, 403, 404, 409, 429):
            with self.subTest(status=status):
                client = self.make_client()
                with patch.object(
                    client, "once", side_effect=cleanup.Failure("HTTP_ERROR", status)
                ) as once:
                    with self.assertRaises(cleanup.Failure):
                        client.get("/environments")
                self.assertEqual(once.call_count, 1)

    def test_delete_is_single_attempt_and_encodes_expiry_guard(self):
        client = self.make_client()
        for failure in (
            cleanup.Failure("TRANSPORT_ERROR"), cleanup.Failure("HTTP_ERROR", 503)
        ):
            with self.subTest(code=failure.code):
                with patch.object(client, "once", side_effect=failure) as once:
                    with self.assertRaises(cleanup.Failure):
                        client.delete(IDENTITY, STAMP)
                once.assert_called_once()
                method, path = once.call_args.args
                self.assertEqual(method, "DELETE")
                parts = urlsplit(path)
                self.assertEqual(parts.path, "/environments/" + IDENTITY)
                self.assertEqual(parse_qs(parts.query), {"expiredBefore": [STAMP]})

    def test_safe_error_tokens_do_not_expose_arbitrary_messages(self):
        for unsafe in (None, 1, "", SECRET + "\n", "<secret>", "x" * 129):
            with self.subTest(unsafe=unsafe):
                self.assertIsNone(cleanup.safe_token(unsafe))
        self.assertEqual(cleanup.safe_token("REQUEST_CONFLICT-1:abc.def"), "REQUEST_CONFLICT-1:abc.def")
        self.assertEqual(cleanup.Failure("HTTP_ERROR", 409, "request-1").public(), {
            "code": "HTTP_ERROR", "httpStatus": 409, "requestId": "request-1",
        })


class HttpCliTests(unittest.TestCase):
    def cli(self, url, *flags, timeout=6, credentials=CREDENTIALS):
        environment = {
            key: value for key, value in os.environ.items()
            if key not in CREDENTIALS
        }
        environment.update(credentials)
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--api-base-url", url, "--json",
             "--connect-timeout", "0.3", "--request-timeout", "0.3", *flags],
            capture_output=True, text=True, timeout=timeout, check=False, env=environment,
        )

    def summary(self, process, expected=0):
        self.assertEqual(process.returncode, expected, process.stdout + process.stderr)
        self.assertEqual(process.stderr, "")
        self.assertNotIn("Traceback", process.stdout)
        self.assertNotIn(SECRET, process.stdout)
        self.assertEqual(len(process.stdout.splitlines()), 1)
        return json.loads(process.stdout)

    def test_subprocess_default_and_explicit_dry_run_never_delete(self):
        for flags in ((), ("--dry-run",)):
            with self.subTest(flags=flags):
                with http_server(lambda handler, n: (
                    200, page([item(expires="2000-01-01T00:00:00Z")]), {}
                )) as (url, requests):
                    result = self.summary(self.cli(url, *flags))
                self.assertEqual(result["mode"], "dry-run")
                self.assertEqual(result["selectedIds"], [IDENTITY])
                self.assertEqual([(method, path) for method, path, _ in requests], [
                    ("GET", "/api/v1/environments?page=0&size=20"),
                ])
                self.assertEqual(requests[0][2]["Accept"], "application/json")
                self.assertEqual(requests[0][2]["Authorization"], EXPECTED_AUTHORIZATION)

    def test_subprocess_execute_rechecks_and_sends_atomic_expiry_query(self):
        def responder(handler, number):
            if handler.headers.get("Authorization") != EXPECTED_AUTHORIZATION:
                return 401, {"code": "UNAUTHENTICATED"}, {}
            if handler.command == "GET" and urlsplit(handler.path).path not in {
                    "/api/v1/environments", "/api/v1/environments/" + IDENTITY}:
                return 403, {"code": "FORBIDDEN"}, {}
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if handler.command == "GET":
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            return 202, accepted(), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"))
        self.assertEqual(result["requested"], 1)
        self.assertEqual([method for method, _, _ in requests], ["GET", "GET", "DELETE"])
        self.assertEqual(requests[1][1], "/api/v1/environments/" + IDENTITY)
        path = urlsplit(requests[2][1])
        self.assertEqual(path.path, "/api/v1/environments/" + IDENTITY)
        self.assertEqual(parse_qs(path.query), {"expiredBefore": [result["cutoff"]]})
        self.assertEqual(result["acceptedOperations"], [{
            "environmentId": IDENTITY, "operationId": OPERATION, "source": "202",
        }])
        self.assertEqual(requests[0][2]["Authorization"], EXPECTED_AUTHORIZATION)
        self.assertEqual(requests[1][2]["Authorization"], EXPECTED_AUTHORIZATION)
        self.assertEqual(requests[2][2]["Authorization"], EXPECTED_AUTHORIZATION)
        self.assertNotIn(CLEANUP_PASSWORD, json.dumps(result))

    def test_subprocess_execute_without_credentials_exits_before_network(self):
        for credentials in (
            {},
            {"PLATFORM_CLEANUP_USERNAME": CLEANUP_USER},
            {"PLATFORM_CLEANUP_PASSWORD": CLEANUP_PASSWORD},
            {**CREDENTIALS, "PLATFORM_CLEANUP_PASSWORD": ""},
            {**CREDENTIALS, "PLATFORM_CLEANUP_USERNAME": "user:with-colon"},
        ):
            with self.subTest(credentials=sorted(credentials)):
                with http_server(lambda handler, n: (200, page([]), {})) as (url, requests):
                    process = self.cli(url, "--execute", credentials=credentials)
                self.assertEqual(process.returncode, 2)
                self.assertEqual(process.stdout, "")
                self.assertIn("PLATFORM_CLEANUP_USERNAME", process.stderr)
                self.assertNotIn(CLEANUP_PASSWORD, process.stderr)
                self.assertNotIn("Traceback", process.stderr)
                self.assertEqual(requests, [])

    def test_subprocess_dry_run_requires_credentials_and_auth_errors_are_not_retried(self):
        with http_server(lambda handler, n: (200, page([]), {})) as (url, requests):
            process = self.cli(url, "--dry-run", credentials={})
        self.assertEqual(process.returncode, 2)
        self.assertEqual(requests, [])
        for status, code in ((401, "UNAUTHENTICATED"), (403, "FORBIDDEN")):
            with self.subTest(status=status):
                with http_server(lambda handler, n: (status, {"code": code}, {})) as (url, requests):
                    result = self.summary(self.cli(url, "--dry-run"), 1)
                self.assertEqual(len(requests), 1)
                self.assertEqual(result["errors"], [{"code": code, "httpStatus": status}])

    def test_subprocess_rejected_credentials_fail_without_retrying_delete(self):
        for status, code in ((401, "UNAUTHENTICATED"), (403, "FORBIDDEN")):
            with self.subTest(status=status):
                def responder(handler, number):
                    if handler.command == "DELETE":
                        return status, {"code": code, "message": SECRET}, {"X-Request-Id": "auth-1"}
                    if number == 1:
                        return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
                    return 200, item(expires="2000-01-01T00:00:00Z"), {}

                with http_server(responder) as (url, requests):
                    result = self.summary(self.cli(url, "--execute"), 1)
                self.assertEqual(result["failed"], 1)
                self.assertEqual(result["unknown"], 0)
                self.assertEqual(result["requested"], 0)
                self.assertEqual(result["errors"], [{
                    "code": code, "httpStatus": status, "requestId": "auth-1",
                    "environmentId": IDENTITY,
                }])
                self.assertEqual(
                    [method for method, _, _ in requests], ["GET", "GET", "DELETE", "GET"]
                )

    def test_subprocess_rejects_invalid_arguments_without_contacting_server(self):
        with http_server(lambda handler, n: (200, page([]), {})) as (url, requests):
            for flags in (
                ("--request-timeout", "nan"), ("--connect-timeout", "0"),
                ("--max-pages", "0"), ("--get-attempts", "4"),
                ("--execute", "--dry-run"),
                ("--api-base-url", url + "?secret=yes"),
                ("--api-base-url", url + "#secret"),
                ("--api-base-url", url.replace("/api/v1", "")),
            ):
                with self.subTest(flags=flags):
                    process = self.cli(url, *flags)
                    self.assertEqual(process.returncode, 2)
                    self.assertEqual(process.stdout, "")
                    self.assertNotIn("Traceback", process.stderr)
        self.assertEqual(requests, [])

    def test_subprocess_get_retries_transient_http_response(self):
        with http_server(lambda handler, n: (
            (503, {"code": "UNAVAILABLE", "message": SECRET}, {})
            if n == 1 else (200, page([]), {})
        )) as (url, requests):
            result = self.summary(self.cli(url))
        self.assertTrue(result["scanComplete"])
        self.assertEqual(len(requests), 2)
        self.assertTrue(all(method == "GET" for method, _, _ in requests))

    def test_subprocess_multiple_pages_preserve_unique_candidate_order(self):
        def responder(handler, number):
            if number == 1:
                return 200, page([
                    item(expires="2000-01-01T00:00:00Z"),
                    item(OTHER, expires="2000-01-01T00:00:00Z"),
                ], size=2, total=3), {}
            return 200, page([
                item(expires="2000-01-01T00:00:00Z"),
            ], number=1, size=2, total=3), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--page-size", "2"))
        self.assertEqual(result["selectedIds"], [IDENTITY, OTHER])
        self.assertEqual(result["duplicates"], 1)
        self.assertEqual(result["scanned"], 3)
        self.assertEqual([path for _, path, _ in requests], [
            "/api/v1/environments?page=0&size=2",
            "/api/v1/environments?page=1&size=2",
        ])

    def test_subprocess_get_attempt_budget_and_safe_http_error(self):
        with http_server(lambda handler, n: (
            503, {"code": "UNAVAILABLE", "message": SECRET}, {"X-Request-Id": "request-123"}
        )) as (url, requests):
            result = self.summary(self.cli(url, "--get-attempts", "3"), 1)
        self.assertEqual(len(requests), 3)
        self.assertEqual(result["errors"], [{
            "code": "UNAVAILABLE", "httpStatus": 503, "requestId": "request-123",
        }])

    def test_subprocess_transport_disconnect_get_is_retried(self):
        def responder(handler, number):
            if number == 1:
                handler.connection.shutdown(socket.SHUT_RDWR)
                handler.connection.close()
                return None
            return 200, page([]), {}

        with http_server(responder) as (url, requests):
            self.summary(self.cli(url))
        self.assertEqual(len(requests), 2)

    def test_header_stall_has_wall_clock_deadline_and_bounded_retries(self):
        def responder(handler, number):
            try:
                handler.connection.sendall(b"HTTP/1.1 200 OK\r\nX-Slow: ")
                for _ in range(20):
                    time.sleep(0.02)
                    handler.connection.sendall(b"x")
            except (BrokenPipeError, ConnectionResetError):
                pass
            return None

        with http_server(responder) as (url, requests):
            start = time.monotonic()
            result = self.summary(
                self.cli(url, "--request-timeout", "0.08", "--get-attempts", "2"), 1
            )
            elapsed = time.monotonic() - start
        self.assertLess(elapsed, 2)
        self.assertEqual(len(requests), 2)
        self.assertEqual(result["errors"], [{"code": "TRANSPORT_ERROR"}])

    def test_body_stall_has_wall_clock_deadline(self):
        def responder(handler, number):
            try:
                handler.connection.sendall(
                    b"HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\n"
                )
                for _ in range(20):
                    time.sleep(0.02)
                    handler.connection.sendall(b" ")
            except (BrokenPipeError, ConnectionResetError):
                pass
            return None

        with http_server(responder) as (url, requests):
            start = time.monotonic()
            result = self.summary(
                self.cli(url, "--request-timeout", "0.08", "--get-attempts", "1"), 1
            )
            elapsed = time.monotonic() - start
        self.assertLess(elapsed, 2)
        self.assertEqual(len(requests), 1)
        self.assertEqual(result["errors"], [{"code": "TRANSPORT_ERROR"}])

    def test_get_redirects_are_never_followed_same_or_other_origin(self):
        with http_server(lambda handler, n: (200, page([]), {})) as (target, targets):
            for status in (301, 302, 303, 307, 308):
                for other_origin in (False, True):
                    with self.subTest(status=status, other_origin=other_origin):
                        def responder(handler, number):
                            location = target + "/environments" if other_origin else "/api/v1/redirected"
                            return status, {"message": SECRET}, {"Location": location}

                        with http_server(responder) as (url, requests):
                            result = self.summary(self.cli(url), 1)
                        self.assertEqual(len(requests), 1)
                        self.assertEqual(result["errors"], [{
                            "code": "HTTP_ERROR", "httpStatus": status,
                        }])
            self.assertEqual(targets, [])

    def test_delete_redirect_is_not_followed_and_recovers_by_get(self):
        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if number == 2:
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            if handler.command == "DELETE":
                return 307, {"message": SECRET}, {"Location": "/api/v1/redirected"}
            return 200, recovering(), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"))
        self.assertEqual([method for method, _, _ in requests], ["GET", "GET", "DELETE", "GET"])
        self.assertEqual(result["alreadyDeleting"], 1)
        self.assertFalse(any("redirected" in path for _, path, _ in requests))

    def test_delete_disconnect_recovers_without_second_delete(self):
        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if number == 2:
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            if handler.command == "DELETE":
                handler.connection.shutdown(socket.SHUT_RDWR)
                handler.connection.close()
                return None
            return 200, recovering("DELETED"), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"))
        self.assertEqual(result["alreadyDeleted"], 1)
        self.assertEqual(sum(method == "DELETE" for method, _, _ in requests), 1)
        self.assertEqual(result["acceptedOperations"][0]["operationId"], OPERATION)

    def test_delete_http_failure_recovers_without_repeating_delete(self):
        for status in (404, 409, 503):
            with self.subTest(status=status):
                def responder(handler, number):
                    if number == 1:
                        return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
                    if number == 2:
                        return 200, item(expires="2000-01-01T00:00:00Z"), {}
                    if handler.command == "DELETE":
                        return status, {"code": "DELETE_CONFLICT", "message": SECRET}, {}
                    return 200, recovering(), {}

                with http_server(responder) as (url, requests):
                    result = self.summary(self.cli(url, "--execute"))
                self.assertEqual(result["alreadyDeleting"], 1)
                self.assertEqual(result["acceptedOperations"][0]["operationId"], OPERATION)
                self.assertEqual(
                    [method for method, _, _ in requests], ["GET", "GET", "DELETE", "GET"]
                )

    def test_delete_failed_observed_after_http_failure_is_never_retried(self):
        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if number == 2:
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            if handler.command == "DELETE":
                return 503, {"code": "UNAVAILABLE", "message": SECRET}, {}
            return 200, item(state="DELETE_FAILED"), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"), 1)
        self.assertEqual(result["failed"], 1)
        self.assertEqual(result["acceptedOperations"], [])
        self.assertEqual(sum(method == "DELETE" for method, _, _ in requests), 1)

    def test_missing_recheck_record_never_submits_delete(self):
        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            return 404, {"code": "NOT_FOUND", "message": SECRET}, {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"), 1)
        self.assertEqual(result["errors"], [{
            "code": "NOT_FOUND", "httpStatus": 404, "environmentId": IDENTITY,
        }])
        self.assertEqual([method for method, _, _ in requests], ["GET", "GET"])

    def test_malformed_202_body_recovers_real_operation_id(self):
        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if number == 2:
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            if handler.command == "DELETE":
                return 202, b"not JSON " + SECRET.encode(), {}
            return 200, recovering(), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"))
        self.assertEqual(result["alreadyDeleting"], 1)
        self.assertEqual(result["acceptedOperations"][0]["operationId"], OPERATION)
        self.assertEqual(sum(method == "DELETE" for method, _, _ in requests), 1)

    def test_invalid_json_and_unsafe_errors_never_print_body_or_trace(self):
        for status, data, headers, expected in (
            (200, b"<html>" + SECRET.encode(), {}, {"code": "INVALID_JSON", "httpStatus": 200}),
            (400, {"code": SECRET + "\n", "message": SECRET}, {"X-Request-Id": "<secret>"},
             {"code": "HTTP_ERROR", "httpStatus": 400}),
            (404, b"<html>" + SECRET.encode(), {}, {"code": "HTTP_ERROR", "httpStatus": 404}),
            (200, b"\xff\xfe", {}, {"code": "INVALID_JSON", "httpStatus": 200}),
        ):
            with self.subTest(status=status, data=data):
                with http_server(lambda handler, n: (status, data, headers)) as (url, requests):
                    result = self.summary(self.cli(url), 1)
                self.assertEqual(result["errors"], [expected])
                self.assertEqual(len(requests), 1)

    def test_duplicate_page_and_nested_environment_fields_fail_closed(self):
        clean = json.dumps(page([item(expires="2000-01-01T00:00:00Z")]))
        for body in (
            clean[:-1] + ', "page": 0}',
            clean.replace('"status": "READY"', '"status": "READY", "status": "FAILED"'),
            clean.replace(
                '"expiresAt": "2000-01-01T00:00:00Z"',
                '"expiresAt": "2099-01-01T00:00:00Z", "expiresAt": "2000-01-01T00:00:00Z"',
            ),
        ):
            with self.subTest(body=body):
                with http_server(lambda handler, n: (
                    200, body.encode(), {}
                )) as (url, requests):
                    result = self.summary(self.cli(url, "--execute"), 1)
                self.assertEqual(result["errors"], [{"code": "INVALID_JSON", "httpStatus": 200}])
                self.assertFalse(result["scanComplete"])
                self.assertEqual(result["selectedIds"], [])
                self.assertEqual(len(requests), 1)

    def test_duplicate_acceptance_fields_trigger_recovery_without_repeated_delete(self):
        body = (json.dumps(accepted())[:-1] + ', "operationId": "' + OTHER + '"}').encode()

        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if number == 2:
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            if handler.command == "DELETE":
                return 202, body, {}
            return 200, recovering(), {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"))
        self.assertEqual(result["alreadyDeleting"], 1)
        self.assertEqual(result["requested"], 0)
        self.assertEqual(result["acceptedOperations"], [{
            "environmentId": IDENTITY, "operationId": OPERATION,
            "source": "recovery", "observedStatus": "DELETING",
        }])
        self.assertEqual(sum(method == "DELETE" for method, _, _ in requests), 1)

    def test_duplicate_recovery_operation_fields_leave_outcome_unknown(self):
        body = json.dumps(recovering()).replace(
            '"type": "DELETE_ENVIRONMENT"',
            '"type": "CREATE_ENVIRONMENT", "type": "DELETE_ENVIRONMENT"',
        ).encode()

        def responder(handler, number):
            if number == 1:
                return 200, page([item(expires="2000-01-01T00:00:00Z")]), {}
            if number == 2:
                return 200, item(expires="2000-01-01T00:00:00Z"), {}
            if handler.command == "DELETE":
                return 503, {"code": "UNAVAILABLE"}, {}
            return 200, body, {}

        with http_server(responder) as (url, requests):
            result = self.summary(self.cli(url, "--execute"), 1)
        self.assertEqual(result["unknown"], 1)
        self.assertEqual(result["acceptedOperations"], [])
        self.assertEqual(result["errors"], [
            {"code": "UNAVAILABLE", "httpStatus": 503, "environmentId": IDENTITY},
            {"code": "INVALID_JSON", "httpStatus": 200, "environmentId": IDENTITY},
        ])
        self.assertEqual([method for method, _, _ in requests], ["GET", "GET", "DELETE", "GET"])

    def test_untrusted_json_types_are_reported_without_traceback(self):
        for value, code in (
            (item(state=[]), "UNKNOWN_ENVIRONMENT_STATUS"),
            (item(state={}), "UNKNOWN_ENVIRONMENT_STATUS"),
            (item(id=[]), "INVALID_UUID"),
            (item(namespace=[]), "INVALID_NAMESPACE"),
            (item(expires={}), "INVALID_TIMESTAMP"),
        ):
            with self.subTest(value=value):
                with http_server(lambda handler, n: (
                    200, page([value]), {}
                )) as (url, requests):
                    result = self.summary(self.cli(url, "--execute"), 1)
                self.assertEqual(result["errors"], [{"code": code}])
                self.assertEqual(len(requests), 1)

    def test_oversized_response_is_bounded_and_not_printed(self):
        body = (SECRET + "x" * cleanup.MAX_BODY).encode()
        with http_server(lambda handler, n: (200, body, {})) as (url, requests):
            result = self.summary(self.cli(url), 1)
        self.assertEqual(result["errors"], [{"code": "RESPONSE_TOO_LARGE", "httpStatus": 200}])
        self.assertEqual(len(requests), 1)

    def test_subprocess_human_output_shows_plan_and_policy_without_mutation(self):
        with http_server(lambda handler, n: (
            200, page([item(expires="2000-01-01T00:00:00Z")]), {}
        )) as (url, requests):
            process = subprocess.run(
                [sys.executable, str(SCRIPT), "--api-base-url", url],
                capture_output=True, text=True, timeout=6, check=False,
                env={**os.environ, **CREDENTIALS},
            )
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(process.stderr, "")
        self.assertIn("dry-run cutoff=", process.stdout)
        self.assertIn("DELETE_FAILED requires manual investigation", process.stdout)
        self.assertIn("selected " + IDENTITY, process.stdout)
        self.assertEqual(len(requests), 1)


if __name__ == "__main__":
    unittest.main()
