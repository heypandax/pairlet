"""Release config must not silently omit collection or embed credentials from the environment."""
import importlib.util
from pathlib import Path
import tempfile
import zipfile
import plistlib
import unittest

spec = importlib.util.spec_from_file_location('release_config', Path(__file__).resolve().parents[1] / 'observability-release-config.py')
config = importlib.util.module_from_spec(spec)
spec.loader.exec_module(config)
DSN = 'https://' + 'a' * 32 + '@o123.ingest.us.sentry.io/456'


class ReleaseConfigTest(unittest.TestCase):
    def test_each_component_only_bundles_its_public_dsn(self):
        with tempfile.TemporaryDirectory() as folder:
            for component in ('android', 'desktop', 'daemon', 'relay'):
                path = config.stage(folder, component, 'production', DSN)
                self.assertEqual(path.read_text().splitlines(), ['environment=production', f'dsn.{component}={DSN}'])

    def test_missing_private_or_injectable_values_fail_before_writing(self):
        for dsn in ('', None, 'sntrys_private_token', DSN + '\napi.secret=private',
                    DSN.replace('@', ':password@'), DSN + '?secret=value',
                    DSN.replace('sentry.io', 'sentry.io.evil.example'), DSN.replace('https:', 'http:')):
            with self.subTest(dsn=dsn), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):
                    config.stage(folder, 'android', 'production', dsn)
                self.assertEqual(list(Path(folder).rglob('*')), [])

    def test_ios_url_survives_xcconfig_comment_syntax(self):
        text = config.render('ios', 'staging', DSN)
        self.assertNotIn('//', text)
        self.assertIn(DSN, text.replace('$()', ''))
        self.assertIn('CCPOCKET_SENTRY_ENVIRONMENT = staging', text)

    def test_existing_private_config_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            path = config.stage(folder, 'desktop', 'development', DSN)
            original = path.read_bytes()
            with self.assertRaises(FileExistsError):
                config.stage(folder, 'desktop', 'production', DSN)
            self.assertEqual(original, path.read_bytes())

    def test_artifact_gate_rejects_missing_duplicate_wrong_env_and_private_resources(self):
        for entries in [[], [(config.RESOURCE, config.render('android', 'staging', DSN))],
                        [(config.RESOURCE, config.render('daemon', 'production', DSN))],
                        [(config.RESOURCE, config.render('android', 'production', DSN)), ('ga4.properties', 'api.secret=private')],
                        [(config.RESOURCE, config.render('android', 'production', DSN)), ('nested/' + config.RESOURCE, config.render('android', 'production', DSN))]]:
            with self.subTest(entries=[name for name, _ in entries]), tempfile.TemporaryDirectory() as folder:
                apk = Path(folder) / 'app.apk'
                with zipfile.ZipFile(apk, 'w') as output:
                    for name, text in entries:
                        output.writestr(name, text)
                with self.assertRaises(ValueError):
                    config.verify(apk, 'android', 'production', DSN)

    def test_real_zip_and_processed_ios_plist_match_exact_environment(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            apk = root / 'app.apk'
            with zipfile.ZipFile(apk, 'w') as output:
                output.writestr(config.RESOURCE, config.render('android', 'production', DSN))
            config.verify(apk, 'android', 'production', DSN)
            info = root / 'Products/Applications/Pairlet.app/Info.plist'
            info.parent.mkdir(parents=True)
            info.write_bytes(plistlib.dumps({'CCPocketSentryDSN': DSN, 'CCPocketSentryEnvironment': 'production'}))
            config.verify(root, 'ios', 'production', DSN)
            with self.assertRaises(ValueError):
                config.verify(root, 'ios', 'staging', DSN)


if __name__ == '__main__':
    unittest.main()
