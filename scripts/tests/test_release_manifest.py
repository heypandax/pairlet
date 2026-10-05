"""release-manifest.py against temp assets and per-run keys — never a release key, never the network.

Needs OpenSSL >= 1.1.1 (Ed25519); skipped with a reason when only LibreSSL is available.
"""
import base64
import hashlib
import json
import os
import pathlib
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts' / 'release-manifest.py'
FIXTURE = ROOT / 'protocol/src/jvmTest/resources/release-signing'

# RFC 8032 §7.1 TEST 2: a PUBLISHED test key. Its secret is printed in the RFC, so it can never be a
# release key (ReleaseTrustedKeysTest refuses it); it makes the cross-implementation fixture reproducible.
RFC_SEED = '4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb'
RFC_PUBLIC = '3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c'
RFC_SIGNATURE_OF_72 = ('92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da'
                       '085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00')

# The interop fixture: inputs that build protocol/src/jvmTest/resources/release-signing/*.
FIXTURE_VERSION = '2.4.0'
FIXTURE_PUBLISHED_AT = '2026-10-05T00:00:00Z'
FIXTURE_ASSETS = {
    'cc-pocket-daemon-2.4.0-linux-x86_64.tar.gz': b'pairlet release-manifest interop fixture\n',
    'cc-pocket-desktop-macos-arm64.dmg': b'second interop asset\n',
}


def capable_openssl():
    exe = os.environ.get('OPENSSL') or shutil.which('openssl')
    if not exe:
        return None
    out = subprocess.run([exe, 'version'], capture_output=True, text=True).stdout
    return exe if out.startswith(('OpenSSL 1.1.1', 'OpenSSL 3', 'OpenSSL 4')) else None


OPENSSL = capable_openssl()


def rfc_pem() -> str:
    der = bytes.fromhex('302e020100300506032b657004220420' + RFC_SEED)
    return '-----BEGIN PRIVATE KEY-----\n' + base64.b64encode(der).decode() + '\n-----END PRIVATE KEY-----\n'


@unittest.skipIf(OPENSSL is None, 'needs OpenSSL >= 1.1.1 with Ed25519 (set OPENSSL)')
class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix='release-manifest-test-'))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.assets = self.tmp / 'assets'
        self.assets.mkdir()
        for name, body in {
            'cc-pocket-daemon-9.1.0-linux-x86_64.tar.gz': b'linux daemon\n',
            'cc-pocket-daemon-9.1.0-macos-arm64.tar.gz': b'mac daemon\n',
            'cc-pocket-desktop-macos-arm64.dmg': b'desktop dmg\n',
        }.items():
            (self.assets / name).write_bytes(body)
        (self.assets / 'SHA256SUMS').write_text('legacy, not signed\n')
        self.key = self.tmp / 'keys' / 'signing.pem'
        self.public = self.keygen(self.key)

    # ── helpers ─────────────────────────────────────────────────────────────────────────────────

    def run_script(self, *args, key_file=None, key_text=None, check=True, env_extra=None):
        env = {k: v for k, v in os.environ.items() if not k.startswith('RELEASE_SIGNING_KEY')}
        env['OPENSSL'] = OPENSSL
        env.pop('GITHUB_ACTIONS', None)
        if key_file is not None:
            env['RELEASE_SIGNING_KEY_FILE'] = str(key_file)
        if key_text is not None:
            env['RELEASE_SIGNING_KEY'] = key_text
        env.update(env_extra or {})
        proc = subprocess.run([sys.executable, str(SCRIPT), *map(str, args)], capture_output=True, text=True, env=env)
        if check and proc.returncode != 0:
            self.fail(f'{args[0]} failed ({proc.returncode}):\n{proc.stdout}\n{proc.stderr}')
        return proc

    def keygen(self, out: pathlib.Path) -> str:
        proc = self.run_script('keygen', '--out', out)
        line = next(l for l in proc.stdout.splitlines() if l.strip().startswith('"'))
        return line.strip().split('"')[1]

    def ci_sign(self, out='signed', *extra, key_file='default', check=True, asset_dir=None):
        return self.run_script('ci-sign', '--version', '9.1.0', '--asset-dir', asset_dir or self.assets,
                               '--out-dir', self.tmp / out, *extra,
                               key_file=self.key if key_file == 'default' else key_file, check=check)

    def verify(self, signed='signed', *extra, check=True, public=None):
        return self.run_script('verify', '--manifest', self.tmp / signed / 'release-manifest.json',
                               '--public-key', public or self.public, '--version', '9.1.0',
                               '--asset-dir', self.assets, *extra, check=check)

    def manifest(self, signed='signed') -> dict:
        return json.loads((self.tmp / signed / 'release-manifest.json').read_text())

    # ── round trip ──────────────────────────────────────────────────────────────────────────────

    def test_build_sign_verify_round_trip(self):
        out = self.tmp / 'm' / 'release-manifest.json'
        out.parent.mkdir()
        self.run_script('build', '--version', '9.1.0', '--asset-dir', self.assets, '--out', out,
                        '--published-at', '2026-10-05T08:00:00Z')
        self.run_script('sign', '--manifest', out, key_file=self.key)
        proc = self.run_script('verify', '--manifest', out, '--public-key', self.public,
                               '--version', '9.1.0', '--asset-dir', self.assets)
        self.assertIn('OK: release-manifest.json for 9.1.0 verifies, 3 assets checked', proc.stdout)
        document = json.loads(out.read_text())
        self.assertEqual('pairlet-release-manifest/1', document['schema'])
        self.assertEqual('2026-10-05T08:00:00Z', document['publishedAt'])
        self.assertEqual(hashlib.sha256(b'mac daemon\n').hexdigest(),
                         document['assets']['cc-pocket-daemon-9.1.0-macos-arm64.tar.gz']['sha256'])
        self.assertNotIn('SHA256SUMS', document['assets'])
        signature = base64.b64decode((out.parent / 'release-manifest.json.sig').read_text().strip(), validate=True)
        self.assertEqual(64, len(signature))

    def test_ci_sign_covers_every_asset_but_sha256sums_and_verifies_itself(self):
        proc = self.ci_sign()
        self.assertIn('signed release-manifest.json for 9.1.0 with public key ' + self.public, proc.stdout)
        self.assertEqual(sorted(p.name for p in self.assets.iterdir() if p.name != 'SHA256SUMS'),
                         sorted(self.manifest()['assets']))
        self.verify()

    # ── no key ──────────────────────────────────────────────────────────────────────────────────

    def test_ci_sign_without_a_key_warns_and_signs_nothing(self):
        proc = self.ci_sign(key_file=None)
        self.assertEqual(0, proc.returncode)
        self.assertIn('RELEASE_SIGNING_KEY is not configured', proc.stderr)
        self.assertFalse((self.tmp / 'signed').exists())

    def test_ci_sign_without_a_key_uses_a_github_warning_annotation(self):
        proc = self.run_script('ci-sign', '--version', '9.1.0', '--asset-dir', self.assets,
                               '--out-dir', self.tmp / 'x', env_extra={'GITHUB_ACTIONS': 'true'})
        self.assertIn('::warning title=Release manifest NOT signed::', proc.stderr)

    def test_sign_and_public_key_without_a_key_fail_clearly(self):
        manifest = self.tmp / 'm.json'
        self.run_script('build', '--version', '9.1.0', '--asset-dir', self.assets, '--out', manifest)
        for args in (('sign', '--manifest', manifest), ('public-key',)):
            proc = self.run_script(*args, check=False)
            self.assertEqual(1, proc.returncode)
            self.assertIn('no signing key: set RELEASE_SIGNING_KEY_FILE or RELEASE_SIGNING_KEY', proc.stderr)

    # ── verify reports each kind of tampering ───────────────────────────────────────────────────

    def test_verify_reports_a_tampered_manifest(self):
        self.ci_sign()
        path = self.tmp / 'signed' / 'release-manifest.json'
        path.write_text(path.read_text().replace('9.1.0', '9.1.1', 1))
        proc = self.verify(check=False)
        self.assertEqual(1, proc.returncode)
        self.assertIn('signature does not verify against any given public key', proc.stderr)

    def test_verify_reports_a_tampered_or_malformed_signature(self):
        self.ci_sign()
        sig = self.tmp / 'signed' / 'release-manifest.json.sig'
        raw = bytearray(base64.b64decode(sig.read_text().strip()))
        raw[0] ^= 1
        sig.write_text(base64.b64encode(bytes(raw)).decode() + '\n')
        self.assertIn('signature does not verify', self.verify(check=False).stderr)
        sig.write_text('not a signature\n')
        self.assertIn('release-manifest.json.sig is malformed', self.verify(check=False).stderr)

    def test_verify_reports_a_tampered_asset(self):
        self.ci_sign()
        (self.assets / 'cc-pocket-desktop-macos-arm64.dmg').write_bytes(b'swapped\n')
        proc = self.verify(check=False)
        self.assertEqual(1, proc.returncode)
        self.assertIn('cc-pocket-desktop-macos-arm64.dmg: sha256 does not match the manifest', proc.stderr)
        (self.assets / 'extra.zip').write_bytes(b'x')
        self.assertIn('extra.zip: not listed in the manifest', self.verify(check=False).stderr)

    def test_verify_rejects_an_untrusted_key_and_accepts_any_of_several(self):
        self.ci_sign()
        other = self.keygen(self.tmp / 'keys' / 'other.pem')
        self.assertIn('signature does not verify', self.verify(public=other, check=False).stderr)
        self.run_script('verify', '--manifest', self.tmp / 'signed' / 'release-manifest.json',
                        '--public-key', other, '--public-key', self.public)

    def test_verify_rejects_the_wrong_version(self):
        self.ci_sign()
        proc = self.run_script('verify', '--manifest', self.tmp / 'signed' / 'release-manifest.json',
                               '--public-key', self.public, '--version', '9.2.0', check=False)
        self.assertIn('manifest is for version 9.1.0, expected 9.2.0', proc.stderr)

    # ── provenance: build outputs, earlier signed manifest, downloaded copies ───────────────────

    def write_records(self, entries: dict) -> pathlib.Path:
        records = self.tmp / 'records'
        records.mkdir(exist_ok=True)
        (records / 'job.sha256').write_text(''.join(f'{d} *{n}\n' for n, d in entries.items()))
        return records

    def test_build_records_are_used_and_a_mismatch_refuses_to_sign(self):
        dmg = 'cc-pocket-desktop-macos-arm64.dmg'
        good = self.write_records({dmg: hashlib.sha256(b'desktop dmg\n').hexdigest()})
        proc = self.ci_sign('ok', '--build-hashes-dir', good)
        self.assertRegex(proc.stdout, r'build\s+cc-pocket-desktop-macos-arm64\.dmg')
        self.assertIn('signed with hashes of the downloaded copies only', proc.stderr)
        bad = self.write_records({dmg: '0' * 64})
        proc = self.ci_sign('bad', '--build-hashes-dir', bad, check=False)
        self.assertEqual(1, proc.returncode)
        self.assertIn('differs from the build output', proc.stderr)
        missing = self.write_records({'cc-pocket-android.apk': '1' * 64})
        self.assertIn('build outputs missing from the release: cc-pocket-android.apk',
                      self.ci_sign('missing', '--build-hashes-dir', missing, check=False).stderr)

    def test_a_partial_rerun_keeps_unchanged_assets_and_refuses_a_silently_changed_one(self):
        self.ci_sign()
        for name in ('release-manifest.json', 'release-manifest.json.sig'):  # now part of the release
            shutil.copy(self.tmp / 'signed' / name, self.assets / name)
        dmg = 'cc-pocket-desktop-macos-arm64.dmg'
        (self.assets / dmg).write_bytes(b'rebuilt dmg\n')
        rebuilt = self.write_records({dmg: hashlib.sha256(b'rebuilt dmg\n').hexdigest()})
        proc = self.ci_sign('rerun', '--build-hashes-dir', rebuilt)
        self.assertRegex(proc.stdout, r'signed-before\s+cc-pocket-daemon-9\.1\.0-linux-x86_64\.tar\.gz')
        self.assertNotIn('downloaded copies only', proc.stderr)
        self.assertEqual(hashlib.sha256(b'rebuilt dmg\n').hexdigest(), self.manifest('rerun')['assets'][dmg]['sha256'])
        (self.assets / 'cc-pocket-daemon-9.1.0-linux-x86_64.tar.gz').write_bytes(b'swapped behind our back\n')
        proc = self.ci_sign('tampered', '--build-hashes-dir', rebuilt, check=False)
        self.assertEqual(1, proc.returncode)
        self.assertIn('changed since the last signed manifest', proc.stderr)

    def test_hotfix_replacement_files_override_the_downloaded_release(self):
        replacement = self.tmp / 'replacement'
        replacement.mkdir()
        (replacement / 'cc-pocket-daemon-9.1.0-linux-x86_64.tar.gz').write_bytes(b'hotfixed daemon\n')
        (replacement / 'SHA256SUMS').write_text('ignored\n')
        proc = self.ci_sign('hotfix', '--replacement-dir', replacement)
        self.assertRegex(proc.stdout, r'build\s+cc-pocket-daemon-9\.1\.0-linux-x86_64\.tar\.gz')
        self.assertEqual(hashlib.sha256(b'hotfixed daemon\n').hexdigest(),
                         self.manifest('hotfix')['assets']['cc-pocket-daemon-9.1.0-linux-x86_64.tar.gz']['sha256'])
        self.run_script('verify', '--manifest', self.tmp / 'hotfix' / 'release-manifest.json', '--public-key',
                        self.public, '--asset-dir', self.assets, '--replacement-dir', replacement)

    def test_a_stale_manifest_is_flagged_when_signing_is_skipped(self):
        (self.assets / 'release-manifest.json').write_text('{}')
        proc = self.ci_sign(key_file=None)
        self.assertEqual(0, proc.returncode)
        self.assertIn('already carries a release-manifest.json', proc.stderr)

    # ── input rules shared with the Kotlin client ───────────────────────────────────────────────

    def test_names_and_versions_the_client_would_reject_are_refused(self):
        (self.assets / 'bad name.zip').write_bytes(b'x')
        self.assertIn("asset name 'bad name.zip' is not allowed", self.ci_sign(check=False).stderr)
        (self.assets / 'bad name.zip').unlink()
        proc = self.run_script('ci-sign', '--version', '../9.1.0', '--asset-dir', self.assets,
                               '--out-dir', self.tmp / 'v', key_file=self.key, check=False)
        self.assertIn("invalid version '../9.1.0'", proc.stderr)

    # ── key handling ────────────────────────────────────────────────────────────────────────────

    def test_the_private_key_never_appears_in_output_or_argv(self):
        pem = self.key.read_text()
        body = ''.join(l for l in pem.splitlines() if not l.startswith('-----'))
        argv_log = self.tmp / 'openssl-argv.log'
        spy = self.tmp / 'openssl-spy'
        spy.write_text(f'#!{sys.executable}\nimport os, sys\n'
                       f'open({str(argv_log)!r}, "a").write(repr(sys.argv) + "\\n")\n'
                       f'os.execv({OPENSSL!r}, [{OPENSSL!r}] + sys.argv[1:])\n')
        spy.chmod(spy.stat().st_mode | stat.S_IXUSR)
        spied = {'OPENSSL': str(spy)}
        outputs = []
        for args in (('public-key',), ('ci-sign', '--version', '9.1.0', '--asset-dir', self.assets,
                                       '--out-dir', self.tmp / 'env-signed')):
            proc = self.run_script(*args, key_text=pem, env_extra=spied)
            outputs += [proc.stdout, proc.stderr]
        outputs.append(argv_log.read_text())
        bad = self.run_script('ci-sign', '--version', 'nope', '--asset-dir', self.assets, '--out-dir',
                              self.tmp / 'bad', key_text=pem, env_extra=spied, check=False)
        outputs += [bad.stdout, bad.stderr]
        combined = '\n'.join(outputs)
        self.assertIn('pkeyutl', combined)  # the spy really saw the signing call
        self.assertNotIn(body, combined)
        for chunk in (body[i:i + 16] for i in range(0, len(body), 16)):
            self.assertNotIn(chunk, combined)
        self.assertEqual(self.public, self.run_script('public-key', key_text=pem).stdout.strip())
        # the env-provided key went to a temp file that no longer exists
        self.assertFalse(any(pathlib.Path(tempfile.gettempdir()).glob('release-signing-*/key.pem')))

    def test_a_base64_wrapped_secret_is_accepted(self):
        wrapped = base64.b64encode(self.key.read_bytes()).decode()
        self.assertEqual(self.public, self.run_script('public-key', key_text=wrapped).stdout.strip())

    def test_keygen_writes_a_private_file_and_refuses_to_overwrite_or_write_into_the_repo(self):
        self.assertEqual(0o600, stat.S_IMODE(self.key.stat().st_mode))
        self.assertIn('already exists', self.run_script('keygen', '--out', self.key, check=False).stderr)
        inside = ROOT / 'build' / 'should-not-exist.pem'
        proc = self.run_script('keygen', '--out', inside, check=False)
        self.assertIn('inside the repository', proc.stderr)
        self.assertFalse(inside.exists())

    def test_libressl_is_refused_with_a_hint(self):
        fake = self.tmp / 'fake-openssl'
        fake.write_text('#!/bin/sh\necho "LibreSSL 3.3.6"\n')
        fake.chmod(0o755)
        proc = self.run_script('public-key', key_file=self.key, env_extra={'OPENSSL': str(fake)}, check=False)
        self.assertIn('need OpenSSL >= 1.1.1 for Ed25519', proc.stderr)

    # ── cross-implementation: the fixture the Kotlin client verifies ───────────────────────────

    def test_rfc8032_vector_2(self):
        key = self.tmp / 'rfc.pem'
        key.write_text(rfc_pem())
        message = self.tmp / 'msg'
        message.write_bytes(bytes.fromhex('72'))
        self.assertEqual(base64.b64encode(bytes.fromhex(RFC_PUBLIC)).decode(),
                         self.run_script('public-key', key_file=key).stdout.strip())
        signature = subprocess.run([OPENSSL, 'pkeyutl', '-sign', '-inkey', str(key), '-rawin', '-in', str(message)],
                                   capture_output=True, check=True).stdout
        self.assertEqual(RFC_SIGNATURE_OF_72, signature.hex())

    def test_the_committed_kotlin_interop_fixture_is_exactly_what_this_script_produces(self):
        assets = self.tmp / 'fixture-assets'
        assets.mkdir()
        for name, body in FIXTURE_ASSETS.items():
            (assets / name).write_bytes(body)
        key = self.tmp / 'rfc.pem'
        key.write_text(rfc_pem())
        manifest = self.tmp / 'fixture' / 'release-manifest.json'
        manifest.parent.mkdir()
        self.run_script('build', '--version', FIXTURE_VERSION, '--asset-dir', assets,
                        '--published-at', FIXTURE_PUBLISHED_AT, '--out', manifest)
        self.run_script('sign', '--manifest', manifest, key_file=key)
        self.assertEqual((FIXTURE / 'release-manifest.json').read_bytes(), manifest.read_bytes(),
                         'regenerate the fixture with this test\'s inputs if the manifest format changed on purpose')
        self.assertEqual((FIXTURE / 'release-manifest.json.sig').read_bytes(),
                         (manifest.parent / 'release-manifest.json.sig').read_bytes())
        self.run_script('verify', '--manifest', FIXTURE / 'release-manifest.json',
                        '--public-key', base64.b64encode(bytes.fromhex(RFC_PUBLIC)).decode(),
                        '--version', FIXTURE_VERSION, '--asset-dir', assets)


if __name__ == '__main__':
    unittest.main()
