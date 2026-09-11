#!/usr/bin/env python3
"""Stage public Sentry write configuration. Never accepts auth tokens or GA4 MP secrets."""
import argparse
import os
from pathlib import Path
import plistlib
import re
import sys
import zipfile

RESOURCE = 'cc-pocket-sentry.properties'
ANALYTICS_RESOURCE = 'cc-pocket-analytics.properties'
TARGETS = {
    'android': Path('mobile/composeApp/src/androidMain/resources') / RESOURCE,
    'desktop': Path('mobile/composeApp/src/desktopMain/resources') / RESOURCE,
    'daemon': Path('daemon/src/main/resources') / RESOURCE,
    'relay': Path('relay/src/main/resources') / RESOURCE,
    'ios': Path('iosApp/Observability.generated.xcconfig'),
}
# Only the desktop app talks to the analytics ingress (docs/observability/DESKTOP-GA4-INGRESS.md §8).
ANALYTICS_TARGETS = {
    'desktop': Path('mobile/composeApp/src/desktopMain/resources') / ANALYTICS_RESOURCE,
}
# scheme + host (+ optional port) only: a path, query, fragment or trailing slash would change
# which endpoint the client builds, and the restricted alphabet also blocks properties-file
# interpolation. This is a public ingress origin, never a Measurement Protocol API secret.
ANALYTICS_ENDPOINT = re.compile(
    r'https://[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+(?::[0-9]{1,5})?'
)


def render(component, environment, dsn):
    if component not in TARGETS or environment not in ('production', 'staging', 'development'):
        raise ValueError('unsupported component or environment')
    # Restricted alphabet also prevents properties/xcconfig/newline interpolation. Only a public
    # SaaS DSN is allowed in an official build, with no password, query, or custom ingestion host.
    if not isinstance(dsn, str) or len(dsn) > 512 or not re.fullmatch(
        r'https://[a-fA-F0-9]{16,64}@o[0-9]+\.ingest(?:\.[a-z]+)?\.sentry\.io/[0-9]+', dsn
    ):
        raise ValueError('missing or invalid public Sentry DSN')
    if component == 'ios':
        # xcconfig treats // as a comment even inside quotes. The empty expansion separates it.
        escaped = dsn.replace('https://', 'https:/$()/')
        return f'CCPOCKET_SENTRY_DSN_IOS = {escaped}\nCCPOCKET_SENTRY_ENVIRONMENT = {environment}\n'
    return f'environment={environment}\ndsn.{component}={dsn}\n'


def render_analytics(endpoint):
    # Same posture as the DSN: an official desktop build must not silently ship without analytics.
    if not isinstance(endpoint, str) or len(endpoint) > 256 or not ANALYTICS_ENDPOINT.fullmatch(endpoint):
        raise ValueError('missing or invalid analytics ingress endpoint')
    return f'endpoint={endpoint}\n'


def stage(root, component, environment, dsn, endpoint=None):
    content = render(component, environment, dsn)
    # Render both before writing either: a missing endpoint must fail before anything lands on disk.
    analytics = render_analytics(endpoint) if component in ANALYTICS_TARGETS else None
    destination = Path(root) / TARGETS[component]
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Never replace a developer's local config accidentally. CI has a fresh checkout; callers that
    # want a different config must explicitly remove their own generated file first.
    with destination.open('x', encoding='utf-8', newline='\n') as out:
        out.write(content)
    destination.chmod(0o600)
    if analytics is not None:
        ingress = Path(root) / ANALYTICS_TARGETS[component]
        ingress.parent.mkdir(parents=True, exist_ok=True)
        with ingress.open('x', encoding='utf-8', newline='\n') as out:
            out.write(analytics)
        ingress.chmod(0o600)
    return destination


def verify(artifact, component, environment, dsn, endpoint=None):
    expected = render(component, environment, dsn)
    expected_analytics = render_analytics(endpoint) if component in ANALYTICS_TARGETS else None
    artifact = Path(artifact)
    if component == 'ios':
        plists = list(artifact.glob('Products/Applications/*.app/Info.plist'))
        if len(plists) != 1:
            raise ValueError('archive must contain exactly one app')
        info = plistlib.loads(plists[0].read_bytes())
        if info.get('CCPocketSentryDSN') != dsn or info.get('CCPocketSentryEnvironment') != environment:
            raise ValueError('archive configuration mismatch')
        return
    archives = sorted(artifact.rglob('*.jar')) if artifact.is_dir() else [artifact]
    found = []
    analytics = []
    for archive in archives:
        with zipfile.ZipFile(archive) as contents:
            # Official desktop analytics still needs a server-side MP credential boundary. An
            # accidentally copied local credential resource must never enter a public package.
            if any(n.rsplit('/', 1)[-1] == 'ga4.properties' for n in contents.namelist()):
                raise ValueError('private GA4 configuration in public artifact')
            for entry in contents.infolist():
                name = entry.filename.rsplit('/', 1)[-1]
                if name in (RESOURCE, ANALYTICS_RESOURCE):
                    if entry.file_size > 2048:
                        raise ValueError('unexpected configuration size')
                    (found if name == RESOURCE else analytics).append(contents.read(entry).decode('utf-8'))
    if found != [expected]:
        raise ValueError('artifact must contain exactly the expected component configuration')
    if expected_analytics is None:
        if analytics:
            raise ValueError('analytics configuration in non-desktop artifact')
    elif analytics != [expected_analytics]:
        raise ValueError('artifact must contain exactly the expected analytics ingress configuration')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('component', choices=TARGETS)
    parser.add_argument('--environment', choices=('production', 'staging', 'development'), default='production')
    parser.add_argument('--verify', metavar='ARTIFACT', help='check an APK, packaged app directory, or iOS archive')
    args = parser.parse_args()
    try:
        root = Path(__file__).resolve().parents[1]
        dsn = os.environ.get('PAIRLET_SENTRY_DSN', '')
        endpoint = os.environ.get('PAIRLET_ANALYTICS_ENDPOINT', '')
        if args.verify:
            verify(args.verify, args.component, args.environment, dsn, endpoint)
        else:
            stage(root, args.component, args.environment, dsn, endpoint)
    except (ValueError, OSError, zipfile.BadZipFile, plistlib.InvalidFileException):
        # Do not print env, config contents, or exception text (it can echo an input/path).
        print('Sentry config check failed: verify public DSN, analytics endpoint, target, environment, and packaged resources', file=sys.stderr)
        return 1
    action = 'artifact verified' if args.verify else 'config staged'
    ingress = f"; analytics ingress {'verified' if args.verify else 'staged'}" if args.component in ANALYTICS_TARGETS else ''
    print(f'Sentry public {action}: {args.component}/{args.environment}{ingress}; user consent is unchanged')
    return 0


if __name__ == '__main__':
    sys.exit(main())
