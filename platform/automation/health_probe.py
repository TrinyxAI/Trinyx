#!/usr/bin/env python3
"""Bounded strict-TLS HTTP smoke runner for non-secret environment inventory."""

from __future__ import annotations

import argparse
import json
import re
import ssl
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse


def fail(message: str) -> None:
    raise SystemExit("HEALTH_PROBE_FAILED=" + message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):  # type: ignore[no-untyped-def]
        return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    args = parser.parse_args()
    try:
        document = json.loads(args.config.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        fail("invalid_config")
    if not isinstance(document, dict) or set(document) != {"schemaVersion", "checks"} or document["schemaVersion"] != 1:
        fail("schema")
    checks = document["checks"]
    if not isinstance(checks, list) or not checks:
        fail("empty")
    for check in checks:
        if not isinstance(check, dict) or set(check) != {"name", "url", "method", "expectedStatuses", "timeoutSeconds", "caFile"}:
            fail("check_schema")
        name = str(check["name"])
        if re.fullmatch(r"[a-z][a-z0-9-]{0,63}", name) is None:
            fail("name")
        parsed = urlparse(str(check["url"]))
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
            fail(name + "_url")
        method = check["method"]
        if method not in {"GET", "POST"}:
            fail(name + "_method")
        ca_file = Path(str(check["caFile"]))
        if not ca_file.is_file() or ca_file.is_symlink():
            fail(name + "_trust")
        expected = check["expectedStatuses"]
        if not isinstance(expected, list) or not expected or not all(isinstance(x, int) and 100 <= x <= 599 for x in expected):
            fail(name + "_statuses")
        timeout = check["timeoutSeconds"]
        if not isinstance(timeout, int) or not 1 <= timeout <= 30:
            fail(name + "_timeout")
        context = ssl.create_default_context(cafile=str(ca_file))
        data = b"{}" if method == "POST" else None
        request = urllib.request.Request(
            str(check["url"]), data=data, method=method,
            headers={"User-Agent": "trinyx-staging-health/1", "Content-Type": "application/json"},
        )
        opener = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context), NoRedirect())
        try:
            with opener.open(request, timeout=timeout) as response:
                status = response.status
        except urllib.error.HTTPError as exc:
            status = exc.code
        except (urllib.error.URLError, TimeoutError, ssl.SSLError):
            fail(name + "_transport")
        if status not in expected:
            fail(name + "_status")
        print(f"HEALTH_CHECK_OK name={name} status={status}")


if __name__ == "__main__":
    main()
