"""Run relay-apply against temporary artifacts and fake service commands, never the real daemon."""
import hashlib
import os
import pathlib
import subprocess
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'relay-apply.sh'
FAKE_COMMAND = r'''#!/usr/bin/env python3
import hashlib, os, pathlib, shutil, signal, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
base = pathlib.Path(os.environ['RELAY_FIXTURE'])
root = base / 'installed'
mode = os.environ.get('RELAY_FAILURE', '')
def version():
    try: return (root / 'lib/version').read_text()
    except OSError: return 'missing'
with (base / 'commands').open('a') as log: log.write(name + ' ' + ' '.join(args) + '\n')
if name == 'systemctl':
    if args[0] == 'cat': print('fixture service'); sys.exit(0)
    if args[0] == 'stop': (base / 'active').unlink(missing_ok=True)
    if args[0] == 'start':
        if mode in ('new_start', 'rollback_start') and version() == 'new': sys.exit(1)
        if mode == 'rollback_start' and version() == 'old': sys.exit(1)
        (base / 'active').touch()
    if args[0] == 'is-active': sys.exit(0 if (base / 'active').exists() else 1)
    if args[0] == 'reload' and mode == 'reload_new' and version() == 'new': sys.exit(1)
elif name == 'caddy':
    if mode == 'validate': sys.exit(1)
elif name == 'curl':
    if mode == 'new_health' and version() == 'new': sys.exit(1)
    sys.exit(0 if (base / 'active').exists() else 1)
elif name == 'sha256sum':
    for line in pathlib.Path(args[-1]).read_text().splitlines():
        checksum, filename = line.split(None, 1)
        if hashlib.sha256(pathlib.Path(filename).read_bytes()).hexdigest() != checksum: sys.exit(1)
elif name == 'cp':
    paths = [pathlib.Path(p) for p in args if p != '-a']
    destination = paths[-1]
    if mode == 'copy_new' and any(p == base / 'stage/bin' for p in paths[:-1]): sys.exit(1)
    if mode == 'copy_hup' and any(p == base / 'stage/bin' for p in paths[:-1]):
        os.kill(os.getppid(), signal.SIGHUP)
        sys.exit(1)
    if mode == 'restore_copy':
        if any(p == base / 'stage/bin' for p in paths[:-1]): sys.exit(1)
        if destination == root and any('.deploy-backups' in p.parts for p in paths[:-1]): sys.exit(1)
    for source in paths[:-1]:
        target = destination / source.name if destination.is_dir() else destination
        if source.is_dir(): shutil.copytree(source, target, dirs_exist_ok=True)
        else: shutil.copy2(source, target)
# flock/chown/sleep are fixtures. No user ownership or service state is touched.
'''


class RelayRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='pairlet-relay-fixture-')
        self.addCleanup(self.tmp.cleanup)
        self.base = pathlib.Path(self.tmp.name)
        self.root, self.stage = self.base / 'installed', self.base / 'stage'
        for directory, version in ((self.root, 'old'), (self.stage, 'new')):
            (directory / 'bin').mkdir(parents=True)
            (directory / 'lib').mkdir()
            (directory / 'bin/cc-pocket-relay').write_text('#!/bin/sh\nexit 0\n')
            (directory / 'lib/version').write_text(version)
        self.caddy = self.base / 'Caddyfile'
        self.caddy.write_text('old config')
        (self.stage / 'Caddyfile').write_text('new config')
        (self.stage / 'dist.sha256').write_text(''.join(
            hashlib.sha256((self.stage / path).read_bytes()).hexdigest() + '  ' + path + '\n'
            for path in ('bin/cc-pocket-relay', 'lib/version', 'Caddyfile')))
        self.fake = self.base / 'fake-bin'
        self.fake.mkdir()
        script = self.fake / 'fixture-command'
        script.write_text(FAKE_COMMAND)
        script.chmod(0o755)
        for command in ('systemctl', 'caddy', 'curl', 'sha256sum', 'cp', 'flock', 'chown', 'sleep'):
            (self.fake / command).symlink_to(script)
        (self.base / 'active').touch()

    def apply(self, mode=''):
        env = dict(os.environ, RELAY_FIXTURE=str(self.base), RELAY_FAILURE=mode,
                   PATH=str(self.fake) + os.pathsep + os.environ['PATH'])
        return subprocess.run(['bash', str(SCRIPT), str(self.stage), str(self.root), str(self.caddy),
                               'fixture-only', 'http://127.0.0.1:1/healthz'],
                              env=env, capture_output=True, text=True, timeout=30)

    def test_success_keeps_intact_old_backup(self):
        result = self.apply()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'ok')
        self.assertEqual((self.root / 'lib/version').read_text(), 'new')
        self.assertEqual(next((self.root / '.deploy-backups').glob('*/lib/version')).read_text(), 'old')

    def test_preparation_failure_never_stops_old_service(self):
        result = self.apply('validate')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'failed')
        self.assertNotIn('systemctl stop', (self.base / 'commands').read_text())
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')

    def test_corrupt_upload_never_stops_old_service(self):
        (self.stage / 'lib/version').write_text('truncated upload')
        result = self.apply()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'failed')
        self.assertNotIn('systemctl stop', (self.base / 'commands').read_text())
        self.assertTrue((self.base / 'active').exists())
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')
        self.assertEqual(self.caddy.read_text(), 'old config')

    def test_incomplete_upload_never_stops_old_service(self):
        (self.stage / 'bin/cc-pocket-relay').unlink()
        result = self.apply()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'failed')
        self.assertNotIn('systemctl stop', (self.base / 'commands').read_text())
        self.assertTrue((self.base / 'active').exists())
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')

    def test_start_failure_restores_old_files_and_caddy(self):
        result = self.apply('new_start')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rolled_back')
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')
        self.assertEqual(self.caddy.read_text(), 'old config')

    def test_partial_copy_failure_recovers_without_a_second_ssh_login(self):
        result = self.apply('copy_new')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rolled_back')
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')

    def test_new_service_health_failure_restores_healthy_old_service(self):
        result = self.apply('new_health')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rolled_back')
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')
        self.assertEqual(self.caddy.read_text(), 'old config')
        self.assertTrue((self.base / 'active').exists())

    def test_hup_during_replacement_restores_old_service(self):
        # A real signal reaches the apply shell; this is not a real SSH/systemd disconnection test.
        result = self.apply('copy_hup')
        self.assertEqual(result.returncode, 129, result.stdout + result.stderr)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rolled_back')
        self.assertEqual((self.root / 'lib/version').read_text(), 'old')
        self.assertEqual(self.caddy.read_text(), 'old config')
        self.assertTrue((self.base / 'active').exists())

    def test_restore_copy_failure_preserves_backup_and_reports_failure(self):
        result = self.apply('restore_copy')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rollback_failed')
        self.assertEqual(next((self.root / '.deploy-backups').glob('*/lib/version')).read_text(), 'old')
        self.assertEqual(next((self.root / '.deploy-backups').glob('*/Caddyfile')).read_text(), 'old config')
        self.assertFalse((self.base / 'active').exists())

    def test_failed_rollback_is_not_reported_as_recovered(self):
        result = self.apply('rollback_start')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rollback_failed')

    def test_caddy_reload_failure_also_rolls_back(self):
        result = self.apply('reload_new')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.stage / 'result').read_text().strip(), 'rolled_back')


if __name__ == '__main__':
    unittest.main()
