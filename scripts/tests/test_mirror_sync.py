"""deploy/mirror-sync.sh against a fake GitHub (shimmed curl) and a temp DEST — never the network or /var/www.

Covers the signed-manifest rules: unsigned releases mirror as before unless MIRROR_REQUIRE_SIGNATURE=1,
signed releases carry release-manifest.json + .sig byte for byte, half-signed or inconsistent releases are
refused, and latest.json points the manifest files at the mirror.
"""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'deploy' / 'mirror-sync.sh'
MIRROR = 'https://pocket.ark-nexus.cc/dl'

FAKE_CURL = r'''#!PYTHON
import json, os, shutil, sys
routes = json.load(open(os.environ['FAKE_ROUTES']))
args = sys.argv[1:]
url = next(a for a in args if a.startswith('http'))
out = args[args.index('-o') + 1] if '-o' in args else None
if url not in routes:
    sys.stderr.write('curl: (22) The requested URL returned error: 404\n'); sys.exit(22)
data = open(routes[url], 'rb').read()
if out: open(out, 'wb').write(data)
else: sys.stdout.buffer.write(data)
'''
# GNU `head -n -K` is not portable (BSD head on macOS); the script uses `head -1` and `head -n -K`.
FAKE_HEAD = r'''#!PYTHON
import sys
args = sys.argv[1:]
n = 10
if args and args[0] == '-n': n = int(args[1])
elif args and args[0].startswith('-'): n = int(args[0][1:])
lines = sys.stdin.buffer.readlines()
sys.stdout.buffer.writelines(lines[:n] if n >= 0 else lines[:max(0, len(lines) + n)])
'''


@unittest.skipUnless(shutil.which('jq') and shutil.which('sha256sum') and shutil.which('bash'),
                     'needs bash, jq and sha256sum')
class MirrorSyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='mirror-sync-test-'))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.dest = self.tmp / 'dl'
        self.files = self.tmp / 'github'
        self.files.mkdir()
        shims = self.tmp / 'bin'
        shims.mkdir()
        for name, body in (('curl', FAKE_CURL), ('head', FAKE_HEAD), ('flock', '#!/bin/sh\nexit 0\n')):
            path = shims / name
            path.write_text(body.replace('PYTHON', sys.executable))
            path.chmod(0o755)
        self.env = {**os.environ, 'PATH': f'{shims}:{os.environ["PATH"]}', 'MIRROR_DEST': str(self.dest),
                    'FAKE_ROUTES': str(self.tmp / 'routes.json')}
        self.env.pop('MIRROR_REQUIRE_SIGNATURE', None)
        self.daemon = 'cc-pocket-daemon-9.1.0-linux-x86_64.tar.gz'
        self.daemon_bytes = b'daemon payload\n'

    def release(self, *, manifest=None, signature=None, sums_hash=None, version='9.1.0'):
        """Publish a fake GitHub release: one mirrored daemon artifact, one GitHub-only desktop asset."""
        assets = {
            self.daemon: self.daemon_bytes,
            'cc-pocket-desktop-macos-arm64.dmg': b'dmg\n',
            'SHA256SUMS': f'{sums_hash or hashlib.sha256(self.daemon_bytes).hexdigest()}  {self.daemon}\n'.encode(),
        }
        if manifest is not None:
            assets['release-manifest.json'] = manifest
        if signature is not None:
            assets['release-manifest.json.sig'] = signature
        routes = {}
        for name, body in assets.items():
            (self.files / name).write_bytes(body)
            routes[f'https://github.test/v{version}/{name}'] = str(self.files / name)
        api = {'tag_name': f'v{version}', 'assets': [
            {'name': n, 'browser_download_url': f'https://github.test/v{version}/{n}'} for n in assets]}
        (self.files / 'api.json').write_text(json.dumps(api))
        routes['https://api.github.com/repos/heypandax/cc-pocket/releases/latest'] = str(self.files / 'api.json')
        for script in ('install.sh', 'install.ps1'):
            (self.files / script).write_text('# cc-pocket installer\n')
            routes[f'https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/{script}'] = str(self.files / script)
        (self.tmp / 'routes.json').write_text(json.dumps(routes))

    def manifest(self, daemon_hash=None, version='9.1.0') -> bytes:
        return json.dumps({'schema': 'pairlet-release-manifest/1', 'version': version,
                           'publishedAt': '2026-10-05T08:00:00Z',
                           'assets': {self.daemon: {'sha256': daemon_hash or hashlib.sha256(self.daemon_bytes).hexdigest()},
                                      'cc-pocket-desktop-macos-arm64.dmg': {'sha256': hashlib.sha256(b'dmg\n').hexdigest()}}},
                          indent=2).encode() + b'\n'

    def sync(self, **env):
        proc = subprocess.run(['bash', str(SCRIPT)], capture_output=True, text=True, env={**self.env, **env})
        return proc, proc.stdout + proc.stderr

    def latest(self) -> dict:
        return json.loads((self.dest / 'latest.json').read_text())

    def test_an_unsigned_release_is_mirrored_as_before(self):
        self.release()
        proc, out = self.sync()
        self.assertEqual(0, proc.returncode, out)
        self.assertIn('release v9.1.0 is unsigned', out)
        assets = self.latest()['assets']
        self.assertEqual(f'{MIRROR}/v9.1.0/{self.daemon}', assets[self.daemon])
        self.assertEqual('https://github.test/v9.1.0/cc-pocket-desktop-macos-arm64.dmg', assets['cc-pocket-desktop-macos-arm64.dmg'])
        self.assertNotIn('release-manifest.json', assets)
        self.assertFalse((self.dest / 'v9.1.0' / 'release-manifest.json').exists())

    def test_require_signature_refuses_an_unsigned_release(self):
        self.release()
        proc, out = self.sync(MIRROR_REQUIRE_SIGNATURE='1')
        self.assertEqual(1, proc.returncode, out)
        self.assertIn('MIRROR_REQUIRE_SIGNATURE=1 — refusing to mirror an unsigned release', out)
        self.assertFalse((self.dest / 'latest.json').exists())

    def test_a_signed_release_carries_manifest_and_signature_verbatim(self):
        manifest, signature = self.manifest(), b'c2lnbmF0dXJlLWJ5dGVz\n'
        self.release(manifest=manifest, signature=signature)
        proc, out = self.sync(MIRROR_REQUIRE_SIGNATURE='1')
        self.assertEqual(0, proc.returncode, out)
        vdir = self.dest / 'v9.1.0'
        self.assertEqual(manifest, (vdir / 'release-manifest.json').read_bytes())
        self.assertEqual(signature, (vdir / 'release-manifest.json.sig').read_bytes())
        assets = self.latest()['assets']
        for name in ('release-manifest.json', 'release-manifest.json.sig', self.daemon, 'SHA256SUMS'):
            self.assertEqual(f'{MIRROR}/v9.1.0/{name}', assets[name])

    def test_a_half_signed_release_is_refused(self):
        self.release(manifest=self.manifest())
        proc, out = self.sync()
        self.assertEqual(1, proc.returncode, out)
        self.assertIn('refusing to mirror a half-signed release', out)
        self.release(signature=b'x\n')
        self.assertIn('half-signed', self.sync()[1])
        self.assertFalse((self.dest / 'latest.json').exists())

    def test_a_manifest_that_disagrees_with_sha256sums_is_refused(self):
        self.release(manifest=self.manifest(daemon_hash='0' * 64), signature=b'sig\n')
        proc, out = self.sync()
        self.assertEqual(1, proc.returncode, out)
        self.assertIn(f'{self.daemon} is {"0" * 64} in release-manifest.json', out)
        self.assertFalse((self.dest / 'latest.json').exists())

    def test_a_manifest_for_another_version_is_refused(self):
        self.release(manifest=self.manifest(version='9.0.0'), signature=b'sig\n')
        proc, out = self.sync()
        self.assertEqual(1, proc.returncode, out)
        self.assertIn('is not a pairlet-release-manifest/1 manifest for 9.1.0', out)

    def test_an_old_signature_is_not_left_beside_an_unsigned_resync(self):
        self.release(manifest=self.manifest(), signature=b'sig\n')
        self.assertEqual(0, self.sync()[0].returncode)
        self.release()
        proc, out = self.sync()
        self.assertEqual(0, proc.returncode, out)
        self.assertFalse((self.dest / 'v9.1.0' / 'release-manifest.json').exists())
        self.assertFalse((self.dest / 'v9.1.0' / 'release-manifest.json.sig').exists())


if __name__ == '__main__':
    unittest.main()
