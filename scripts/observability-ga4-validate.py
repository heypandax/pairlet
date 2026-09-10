#!/usr/bin/env python3
"""Validate an explicitly supplied GA4 MP payload; the debug endpoint does not ingest events."""
import argparse
import json
import os
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('payload', type=pathlib.Path)
    parser.add_argument('--measurement-id', default=os.environ.get('GA4_MEASUREMENT_ID'))
    args = parser.parse_args()
    secret = os.environ.get('GA4_API_SECRET')
    if not args.measurement_id or not re.fullmatch(r'G-[A-Z0-9]+', args.measurement_id) or not secret:
        parser.error('set GA4_MEASUREMENT_ID and GA4_API_SECRET; credentials are never printed')
    try:
        raw = args.payload.read_bytes()
        if len(raw) > 130_000:
            raise ValueError()
        payload = json.loads(raw)
        if not isinstance(payload, dict) or not isinstance(payload.get('events'), list):
            raise ValueError()
        if not 1 <= len(payload['events']) <= 25:
            raise ValueError()
        payload['validation_behavior'] = 'ENFORCE_RECOMMENDATIONS'
        encoded = json.dumps(payload, allow_nan=False).encode()
        if len(encoded) > 130_000:
            raise ValueError()
    except (OSError, ValueError, TypeError):
        parser.error('payload must be a bounded JSON object with 1 to 25 events')
    url = 'https://www.google-analytics.com/debug/mp/collect?' + urllib.parse.urlencode({
        'measurement_id': args.measurement_id, 'api_secret': secret})
    request = urllib.request.Request(url, encoded, {'Content-Type': 'application/json'}, method='POST')
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=10) as response:
            data = response.read(65_537)
            if len(data) > 65_536:
                raise ValueError()
            result = json.loads(data)
            messages = result.get('validationMessages')
            if not isinstance(messages, list):
                raise ValueError()
            # Do not print URLs, request bodies, server prose or identifiers echoed in descriptions.
            codes = [item.get('validationCode', 'unknown') for item in messages if isinstance(item, dict)]
            codes = [code if isinstance(code, str) and re.fullmatch(r'[A-Z_]{1,80}', code) else 'unknown'
                     for code in codes]
    except (urllib.error.URLError, OSError, ValueError, TypeError, AttributeError):
        print(json.dumps({'validation': 'unavailable', 'delivery': 'not_attempted'}))
        return 2
    print(json.dumps({'validation': 'accepted' if not messages else 'rejected',
                      'validation_codes': codes, 'delivery': 'not_attempted'}))
    return 0 if not messages else 1


if __name__ == '__main__':
    raise SystemExit(main())
