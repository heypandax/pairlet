#!/usr/bin/env python3
"""Signed release manifest for Pairlet self-update (docs/RELEASE.md「更新包签名」).

A release publishes ``release-manifest.json`` (version, publish time, sha256 of every asset) and
``release-manifest.json.sig`` (base64 of an Ed25519 signature over the manifest's raw bytes). Daemon and
desktop clients that embed a trusted public key (protocol ReleaseTrustedKeys.kt) install an update only
if this signature verifies — the mirror and GitHub are just byte carriers.

Subcommands
  build       write release-manifest.json for the assets in a directory
  sign        sign a manifest; the private key comes from the environment only (see below)
  verify      check a manifest + signature against public key(s), optionally against the asset files
  public-key  print the base64 public key of the configured private key
  ci-sign     the release-workflow step: build + sign + self-verify, or skip with a warning when no key
  keygen      create a NEW key pair — for the project owner only, never in CI

Private key: ``RELEASE_SIGNING_KEY_FILE`` (path to a PKCS#8 PEM file) or ``RELEASE_SIGNING_KEY`` (the PEM
text itself, e.g. a GitHub Actions secret). It is never accepted as a command-line argument and never
printed; openssl only ever receives the path of a private 0600 temp file.

Ed25519 is done by the system ``openssl`` (OpenSSL >= 1.1.1; ``pkeyutl -rawin``) because the repository's
Python tooling has no third-party dependencies. Set ``OPENSSL`` to pick a binary (macOS ships LibreSSL as
/usr/bin/openssl, which is refused: ``brew install openssl@3``).
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Iterator

MANIFEST = 'release-manifest.json'
SIGNATURE = MANIFEST + '.sig'
SCHEMA = 'pairlet-release-manifest/1'
# release assets that are not covered by the manifest: the legacy checksum file and the manifest itself
NOT_SIGNED = {'SHA256SUMS', MANIFEST, SIGNATURE}

KEY_ENV = 'RELEASE_SIGNING_KEY'
KEY_FILE_ENV = 'RELEASE_SIGNING_KEY_FILE'

# Kept identical to protocol ReleaseSignature.kt — a manifest the client would reject is never produced.
VERSION_RE = re.compile(r'^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$')
ASSET_RE = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._+-]*$')
SHA256_RE = re.compile(r'^[0-9a-f]{64}$')

ED25519_SPKI_PREFIX = bytes.fromhex('302a300506032b6570032100')
REPO = pathlib.Path(__file__).resolve().parents[1]
TRUSTED_KEYS_KT = REPO / 'protocol/src/jvmMain/kotlin/dev/ccpocket/protocol/update/ReleaseTrustedKeys.kt'


class ManifestError(Exception):
    """A refusal with a message meant for the release log."""


class NoSigningKey(ManifestError):
    pass


def warn(message: str, title: str | None = None) -> None:
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        print(f'::warning{" title=" + title if title else ""}::{message}', file=sys.stderr)
    else:
        print(f'warning: {message}', file=sys.stderr)


# ── openssl ────────────────────────────────────────────────────────────────────────────────────────

def openssl() -> str:
    exe = os.environ.get('OPENSSL') or shutil.which('openssl')
    if not exe:
        raise ManifestError('openssl not found; install OpenSSL >= 1.1.1 or set OPENSSL')
    version = subprocess.run([exe, 'version'], capture_output=True, text=True, stdin=subprocess.DEVNULL).stdout.strip()
    match = re.match(r'OpenSSL (\d+)\.(\d+)\.(\d+)', version)
    if not match or tuple(int(g) for g in match.groups()) < (1, 1, 1):
        raise ManifestError(
            f'need OpenSSL >= 1.1.1 for Ed25519, found {version or exe!r} '
            '(macOS: brew install openssl@3, then OPENSSL="$(brew --prefix openssl@3)/bin/openssl")')
    return exe


def run_openssl(*args: str) -> bytes:
    proc = subprocess.run([openssl(), *args], capture_output=True, stdin=subprocess.DEVNULL)
    if proc.returncode != 0:
        detail = proc.stderr.decode('utf-8', 'replace').strip().splitlines()
        raise ManifestError(f'openssl {args[0]} failed: {detail[-1] if detail else "exit " + str(proc.returncode)}')
    return proc.stdout


# ── keys ───────────────────────────────────────────────────────────────────────────────────────────

def signing_key_configured() -> bool:
    return bool(os.environ.get(KEY_FILE_ENV) or os.environ.get(KEY_ENV, '').strip())


@contextlib.contextmanager
def private_key_file() -> Iterator[str]:
    """Yield a path openssl can read the private key from. An env-provided PEM goes to a 0600 file in a
    0700 temp dir that is removed afterwards; the key text itself never reaches argv or the output."""
    path = os.environ.get(KEY_FILE_ENV)
    if path:
        if not os.path.isfile(path):
            raise NoSigningKey(f'{KEY_FILE_ENV} does not point to a file')
        yield path
        return
    text = os.environ.get(KEY_ENV, '').strip()
    if not text:
        raise NoSigningKey(f'no signing key: set {KEY_FILE_ENV} or {KEY_ENV}')
    if '-----BEGIN' not in text:  # tolerate a secret stored as base64 of the PEM file
        try:
            text = base64.b64decode(text, validate=True).decode('ascii').strip()
        except Exception:
            raise NoSigningKey(f'{KEY_ENV} is neither a PEM private key nor base64 of one') from None
    with tempfile.TemporaryDirectory(prefix='release-signing-') as tmp:
        key_path = os.path.join(tmp, 'key.pem')
        fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as handle:
            handle.write(text + '\n')
        yield key_path


def public_key_of(key_path: str) -> str:
    der = run_openssl('pkey', '-in', key_path, '-pubout', '-outform', 'DER')
    if len(der) != 44 or not der.startswith(ED25519_SPKI_PREFIX):
        raise ManifestError('the signing key is not an Ed25519 key')
    return base64.b64encode(der[len(ED25519_SPKI_PREFIX):]).decode()


def raw_public_key(b64: str) -> bytes:
    try:
        raw = base64.b64decode(b64.strip(), validate=True)
    except Exception:
        raise ManifestError(f'public key is not base64: {b64!r}') from None
    if len(raw) != 32:
        raise ManifestError(f'an Ed25519 public key is 32 bytes, got {len(raw)}')
    return raw


def public_pem(b64: str) -> str:
    der = ED25519_SPKI_PREFIX + raw_public_key(b64)
    return '-----BEGIN PUBLIC KEY-----\n' + base64.b64encode(der).decode() + '\n-----END PUBLIC KEY-----\n'


def sign_bytes(key_path: str, message_path: pathlib.Path) -> bytes:
    if message_path.stat().st_size == 0:
        raise ManifestError('refusing to sign an empty file')
    signature = run_openssl('pkeyutl', '-sign', '-inkey', key_path, '-rawin', '-in', str(message_path))
    if len(signature) != 64:
        raise ManifestError(f'unexpected Ed25519 signature length {len(signature)}')
    return signature


def decode_signature_file(data: bytes) -> bytes:
    try:
        raw = base64.b64decode(data.strip(), validate=True)
    except Exception:
        raise ManifestError(f'{SIGNATURE} is malformed: not base64') from None
    if len(raw) != 64:
        raise ManifestError(f'{SIGNATURE} is malformed: {len(raw)} bytes, expected 64')
    return raw


def signature_verifies(message_path: pathlib.Path, signature: bytes, public_keys: list[str]) -> bool:
    with tempfile.TemporaryDirectory(prefix='release-verify-') as tmp:
        sig_path = pathlib.Path(tmp, 'sig.bin')
        sig_path.write_bytes(signature)
        for index, key in enumerate(public_keys):
            pem_path = pathlib.Path(tmp, f'pub{index}.pem')
            pem_path.write_text(public_pem(key))
            proc = subprocess.run(
                [openssl(), 'pkeyutl', '-verify', '-pubin', '-inkey', str(pem_path), '-rawin',
                 '-in', str(message_path), '-sigfile', str(sig_path)],
                capture_output=True, stdin=subprocess.DEVNULL)
            if proc.returncode == 0:
                return True
    return False


def trusted_keys_in_source(path: pathlib.Path = TRUSTED_KEYS_KT) -> list[str]:
    """The base64 literals inside ReleaseTrustedKeys.KEYS (comments are ignored)."""
    text = path.read_text()
    body = text[text.index('listOf(') + len('listOf('):]
    body = body[:body.index(')')]
    body = re.sub(r'//[^\n]*', '', body)
    return re.findall(r'"([A-Za-z0-9+/]{43}=)"', body)


# ── manifest ───────────────────────────────────────────────────────────────────────────────────────

def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def utc_now() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def check_version(version: str) -> str:
    if not VERSION_RE.match(version):
        raise ManifestError(f'invalid version {version!r} (expected x.y.z with an optional suffix)')
    return version


def check_published_at(value: str) -> str:
    if not re.match(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$', value):
        raise ManifestError(f'publishedAt must be UTC like 2026-10-05T08:00:00Z, got {value!r}')
    datetime.datetime.strptime(value, '%Y-%m-%dT%H:%M:%SZ')
    return value


def collect_assets(asset_dir: pathlib.Path, replacement_dir: pathlib.Path | None = None) -> dict[str, pathlib.Path]:
    """Every release asset the manifest must cover: the files in [asset_dir] (a full `gh release download`)
    minus SHA256SUMS and the manifest files, with same-named files from [replacement_dir] taking over."""
    assets: dict[str, pathlib.Path] = {}
    for directory in (asset_dir, replacement_dir):
        if directory is None:
            continue
        for path in sorted(directory.iterdir()):
            if path.is_file() and path.name not in NOT_SIGNED and not path.name.startswith('.'):
                if not ASSET_RE.match(path.name):
                    raise ManifestError(f'asset name {path.name!r} is not allowed in a signed manifest')
                assets[path.name] = path
    if not assets:
        raise ManifestError(f'no release assets found in {asset_dir}')
    return assets


def read_hash_records(directory: pathlib.Path | None) -> dict[str, str]:
    """Build-time `sha256sum` lines (`<hex>  <name>` / `<hex> *<name>`) from every file in [directory]."""
    records: dict[str, str] = {}
    if directory is None or not directory.is_dir():
        return records
    for path in sorted(directory.rglob('*')):
        if not path.is_file():
            continue
        for line in path.read_text().splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) != 2:
                continue
            digest, name = parts[0].lower(), parts[1].lstrip('*').strip()
            if not SHA256_RE.match(digest) or not ASSET_RE.match(name):
                raise ManifestError(f'unreadable build hash record in {path.name}: {line!r}')
            if records.get(name, digest) != digest:
                raise ManifestError(f'conflicting build hash records for {name}')
            records[name] = digest
    return records


def render_manifest(version: str, published_at: str, hashes: dict[str, str]) -> bytes:
    document = {
        'schema': SCHEMA,
        'version': check_version(version),
        'publishedAt': check_published_at(published_at),
        'assets': {name: {'sha256': hashes[name]} for name in sorted(hashes)},
    }
    return (json.dumps(document, indent=2, sort_keys=True) + '\n').encode()


def parse_manifest(data: bytes) -> dict:
    """Strict parse with the client's rules (ReleaseSignature.parseManifest)."""
    try:
        document = json.loads(data.decode('utf-8'))
    except Exception:
        raise ManifestError(f'{MANIFEST} is not valid JSON') from None
    if not isinstance(document, dict) or document.get('schema') != SCHEMA:
        raise ManifestError(f'{MANIFEST} schema is not {SCHEMA}')
    check_version(str(document.get('version', '')))
    check_published_at(str(document.get('publishedAt', '')))
    assets = document.get('assets')
    if not isinstance(assets, dict) or not assets:
        raise ManifestError(f'{MANIFEST} lists no assets')
    for name, entry in assets.items():
        if not ASSET_RE.match(name) or not isinstance(entry, dict) or not SHA256_RE.match(str(entry.get('sha256', ''))):
            raise ManifestError(f'{MANIFEST} has an invalid entry for {name!r}')
    return document


def previous_hashes(asset_dir: pathlib.Path, version: str, public_key: str | None) -> dict[str, str] | None:
    """The asset hashes of the manifest a previous run already signed for this release (it is part of a
    full `gh release download`), if it verifies with [public_key] and is for [version]; else None."""
    manifest, signature = asset_dir / MANIFEST, asset_dir / SIGNATURE
    if not manifest.is_file() or not signature.is_file():
        return None
    if public_key is None:
        warn(f'{MANIFEST} from an earlier run is present but no public key was given to check it — ignored')
        return None
    try:
        if not signature_verifies(manifest, decode_signature_file(signature.read_bytes()), [public_key]):
            raise ManifestError('signature does not verify with the current signing key')
        document = parse_manifest(manifest.read_bytes())
    except ManifestError as error:
        warn(f'ignoring the {MANIFEST} from an earlier run ({error}); carried-over assets are hashed as downloaded')
        return None
    if document['version'] != version:
        warn(f'ignoring the {MANIFEST} from an earlier run: it is for {document["version"]}, not {version}')
        return None
    return {name: entry['sha256'] for name, entry in document['assets'].items()}


def build(version: str, asset_dir: pathlib.Path, replacement_dir: pathlib.Path | None,
          build_hash_dir: pathlib.Path | None, public_key: str | None, published_at: str) -> tuple[bytes, dict[str, str]]:
    """Returns (manifest bytes, asset -> provenance). Provenance of each hash, strongest first:
    `build` (recorded by the job that produced the file, or a hotfix replacement file itself),
    `signed-before` (unchanged since a manifest this key signed earlier for this release),
    `downloaded` (only the downloaded copy vouches for it — warned)."""
    check_version(version)
    assets = collect_assets(asset_dir, replacement_dir)
    recorded = read_hash_records(build_hash_dir)
    replaced = set(p.name for p in replacement_dir.iterdir() if p.is_file()) if replacement_dir else set()
    before = previous_hashes(asset_dir, version, public_key)
    hashes: dict[str, str] = {}
    provenance: dict[str, str] = {}
    for name, path in assets.items():
        actual = sha256_file(path)
        if name in recorded:
            if recorded[name] != actual:
                raise ManifestError(
                    f'{name}: the release copy ({actual}) differs from the build output ({recorded[name]}) — '
                    'replaced after upload, or a concurrent run; refusing to sign')
            provenance[name] = 'build'
        elif name in replaced:
            provenance[name] = 'build'
        elif before is not None and name in before:
            if before[name] != actual:
                raise ManifestError(
                    f'{name} changed since the last signed manifest of this release but was not rebuilt by this '
                    'run — refusing to sign it (verify the file and sign locally if the change is intended)')
            provenance[name] = 'signed-before'
        else:
            provenance[name] = 'downloaded'
        hashes[name] = actual
    missing = sorted(set(recorded) - set(assets))
    if missing:
        raise ManifestError(f'build outputs missing from the release: {", ".join(missing)}')
    unvouched = sorted(n for n, p in provenance.items() if p == 'downloaded')
    if unvouched:
        warn(f'signed with hashes of the downloaded copies only (no build record or earlier signed manifest): '
             f'{", ".join(unvouched)}', title='Release manifest provenance')
    return render_manifest(version, published_at, hashes), provenance


def verify(manifest_path: pathlib.Path, signature_path: pathlib.Path, public_keys: list[str],
           version: str | None, asset_dir: pathlib.Path | None, replacement_dir: pathlib.Path | None) -> dict:
    if not public_keys:
        raise ManifestError('at least one --public-key is required')
    for key in public_keys:
        raw_public_key(key)
    if not manifest_path.is_file():
        raise ManifestError(f'{MANIFEST} is missing')
    if not signature_path.is_file():
        raise ManifestError(f'{SIGNATURE} is missing')
    signature = decode_signature_file(signature_path.read_bytes())
    if not signature_verifies(manifest_path, signature, public_keys):
        raise ManifestError('signature does not verify against any given public key '
                            '(manifest or signature altered, or signed by another key)')
    document = parse_manifest(manifest_path.read_bytes())
    if version is not None and document['version'] != version:
        raise ManifestError(f'manifest is for version {document["version"]}, expected {version}')
    if asset_dir is not None:
        assets = collect_assets(asset_dir, replacement_dir)
        listed = {name: entry['sha256'] for name, entry in document['assets'].items()}
        problems = [f'{name}: not listed in the manifest' for name in sorted(set(assets) - set(listed))]
        problems += [f'{name}: listed but not present' for name in sorted(set(listed) - set(assets))]
        problems += [f'{name}: sha256 does not match the manifest' for name in sorted(set(assets) & set(listed))
                     if sha256_file(assets[name]) != listed[name]]
        if problems:
            raise ManifestError('assets do not match the signed manifest:\n  ' + '\n  '.join(problems))
    return document


# ── commands ───────────────────────────────────────────────────────────────────────────────────────

def cmd_build(args: argparse.Namespace) -> None:
    manifest, _ = build(args.version, args.asset_dir, args.replacement_dir, args.build_hashes_dir,
                        args.public_key, args.published_at or utc_now())
    args.out.write_bytes(manifest)
    print(f'wrote {args.out}')


def cmd_sign(args: argparse.Namespace) -> None:
    parse_manifest(args.manifest.read_bytes())  # never sign something the client would reject
    out = args.out or args.manifest.with_name(args.manifest.name + '.sig')
    with private_key_file() as key_path:
        signature = sign_bytes(key_path, args.manifest)
        public_key = public_key_of(key_path)
    out.write_text(base64.b64encode(signature).decode() + '\n')
    print(f'wrote {out} (public key {public_key})')


def cmd_verify(args: argparse.Namespace) -> None:
    signature = args.signature or args.manifest.with_name(args.manifest.name + '.sig')
    document = verify(args.manifest, signature, args.public_key, args.version, args.asset_dir, args.replacement_dir)
    scope = f', {len(document["assets"])} assets checked' if args.asset_dir else ''
    print(f'OK: {MANIFEST} for {document["version"]} verifies{scope}')


def cmd_public_key(_args: argparse.Namespace) -> None:
    with private_key_file() as key_path:
        print(public_key_of(key_path))


def cmd_ci_sign(args: argparse.Namespace) -> None:
    if not signing_key_configured():
        warn(f'{KEY_ENV} is not configured: this release is published WITHOUT {MANIFEST} / {SIGNATURE}. '
             'Clients with a built-in release key will refuse it (see docs/RELEASE.md「更新包签名」).',
             title='Release manifest NOT signed')
        if (args.asset_dir / MANIFEST).is_file():
            warn(f'the release already carries a {MANIFEST} from an earlier run; it does not cover what this '
                 'run uploaded, so signature-enforcing clients will refuse the changed assets',
                 title='Stale release manifest')
        return
    args.out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path, signature_path = args.out_dir / MANIFEST, args.out_dir / SIGNATURE
    with private_key_file() as key_path:
        public_key = public_key_of(key_path)
        manifest, provenance = build(args.version, args.asset_dir, args.replacement_dir, args.build_hashes_dir,
                                     public_key, args.published_at or utc_now())
        manifest_path.write_bytes(manifest)
        signature_path.write_text(base64.b64encode(sign_bytes(key_path, manifest_path)).decode() + '\n')
    verify(manifest_path, signature_path, [public_key], args.version, args.asset_dir, args.replacement_dir)
    try:
        trusted = trusted_keys_in_source(args.trusted_keys_source)
    except (OSError, ValueError) as error:
        warn(f'could not read the trusted key list from {args.trusted_keys_source}: {error}')
        trusted = None
    if trusted is None:
        pass
    elif not trusted:
        print('note: ReleaseTrustedKeys.kt is empty — clients built from this tree do not enforce signatures yet')
    elif public_key not in trusted:
        warn(f'the signing key {public_key} is not in ReleaseTrustedKeys.kt — clients built from this tree '
             'will refuse releases signed with it', title='Signing key not trusted by this tree')
    print(f'signed {MANIFEST} for {args.version} with public key {public_key}')
    for name in sorted(provenance):
        print(f'  {provenance[name]:<13} {name}')


def cmd_keygen(args: argparse.Namespace) -> None:
    out = args.out.expanduser().resolve()
    if out.exists():
        raise ManifestError(f'{out} already exists — refusing to overwrite a key')
    if out.is_relative_to(REPO):
        raise ManifestError('refusing to write a private key inside the repository; choose a path outside it')
    out.parent.mkdir(parents=True, exist_ok=True)
    previous = os.umask(0o077)
    try:
        run_openssl('genpkey', '-algorithm', 'ed25519', '-out', str(out))
    finally:
        os.umask(previous)
    os.chmod(out, 0o600)
    public_key = public_key_of(str(out))
    print(f'''New Ed25519 release signing key written to {out} (mode 0600).

Public key (add to ReleaseTrustedKeys.kt, see docs/RELEASE.md「更新包签名」):
    "{public_key}", // release key {datetime.date.today():%Y-%m}

Keep the private key file safe:
  - Store it offline / in your password manager; anyone holding it can ship code to every client.
  - Never commit it, paste it into chat, or pass it on a command line.
Configure the release workflows (reads the file from stdin, nothing on the command line):
    gh secret set {KEY_ENV} --repo heypandax/cc-pocket < {out}
Check what the secret signs with at any time:
    {KEY_FILE_ENV}={out} python3 scripts/release-manifest.py public-key''')


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    sub = parser.add_subparsers(dest='command', required=True)
    path = pathlib.Path

    p = sub.add_parser('build', help='write release-manifest.json for a set of assets')
    p.add_argument('--version', required=True)
    p.add_argument('--asset-dir', type=path, required=True)
    p.add_argument('--replacement-dir', type=path)
    p.add_argument('--build-hashes-dir', type=path)
    p.add_argument('--public-key', help='checks a manifest an earlier run left in --asset-dir')
    p.add_argument('--published-at')
    p.add_argument('--out', type=path, required=True)
    p.set_defaults(func=cmd_build)

    p = sub.add_parser('sign', help=f'sign a manifest (key from {KEY_FILE_ENV} or {KEY_ENV})')
    p.add_argument('--manifest', type=path, required=True)
    p.add_argument('--out', type=path)
    p.set_defaults(func=cmd_sign)

    p = sub.add_parser('verify', help='verify a manifest + signature (and optionally the assets)')
    p.add_argument('--manifest', type=path, required=True)
    p.add_argument('--signature', type=path)
    p.add_argument('--public-key', action='append', default=[], required=True)
    p.add_argument('--version')
    p.add_argument('--asset-dir', type=path)
    p.add_argument('--replacement-dir', type=path)
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser('public-key', help='print the public key of the configured private key')
    p.set_defaults(func=cmd_public_key)

    p = sub.add_parser('ci-sign', help='release workflow: build + sign + verify, or warn and skip without a key')
    p.add_argument('--version', required=True)
    p.add_argument('--asset-dir', type=path, required=True)
    p.add_argument('--replacement-dir', type=path)
    p.add_argument('--build-hashes-dir', type=path)
    p.add_argument('--published-at')
    p.add_argument('--out-dir', type=path, required=True)
    p.add_argument('--trusted-keys-source', type=path, default=TRUSTED_KEYS_KT)
    p.set_defaults(func=cmd_ci_sign)

    p = sub.add_parser('keygen', help='create a NEW key pair (project owner only)')
    p.add_argument('--out', type=path, required=True, help='private key file to create (outside the repository)')
    p.set_defaults(func=cmd_keygen)

    args = parser.parse_args(argv)
    try:
        args.func(args)
    except ManifestError as error:
        print(f'error: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
