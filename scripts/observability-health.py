#!/usr/bin/env python3
"""One-shot, independent reachability evidence. Does not install a monitor or send notifications."""
import argparse
import datetime as dt
import json
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def probe(url, timeout=5):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("use an HTTP(S) health URL without credentials, query or fragment")
    if parsed.scheme == "http" and parsed.hostname not in ("127.0.0.1", "::1", "localhost"):
        raise ValueError("remote probes require HTTPS")
    started = time.monotonic()
    code, status = None, "unknown"
    # Do not inherit credentials/cookies, follow redirects, collect bodies, or probe ingestion with data.
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, headers={"User-Agent": "PairletHealth/1"}), timeout=timeout) as response:
            code = response.status
            status = "reachable" if code == 200 else "http_rejected"
    except urllib.error.HTTPError as error:
        code, status = error.code, "http_rejected"
    except (TimeoutError, socket.timeout):
        status = "timeout"
    except urllib.error.URLError as error:
        status = ("dns_failed" if isinstance(error.reason, socket.gaierror) else
                  "tls_failed" if isinstance(error.reason, ssl.SSLError) else
                  "timeout" if isinstance(error.reason, (TimeoutError, socket.timeout)) else "unreachable")
    return {"observed_at": dt.datetime.now(dt.timezone.utc).isoformat(), "result": status,
            "http_status": code, "elapsed_ms": round((time.monotonic() - started) * 1000),
            "scope": "http_health_only", "diagnostic_delivery": "unknown"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url")
    parser.add_argument("--timeout", type=float, default=5)
    args = parser.parse_args()
    if not 0 < args.timeout <= 30:
        parser.error("timeout must be in (0, 30]")
    try:
        result = probe(args.url, args.timeout)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(result, sort_keys=True))
    return 0 if result["result"] == "reachable" else 1


if __name__ == "__main__":
    raise SystemExit(main())
