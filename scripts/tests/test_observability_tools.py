"""Isolated fixtures only; no Sentry, GA4, device or production service traffic."""
import contextlib
import importlib.util
import io
import os
import pathlib
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]


def load_script(filename):
    spec = importlib.util.spec_from_file_location(filename.replace('-', '_'), SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class VisibilityReceiptsTest(unittest.TestCase):
    def setUp(self):
        self.report = load_script('observability-latency.py')

    @staticmethod
    def row(index, visible='2026-09-10T12:00:30+00:00', eligible='true'):
        return dict(component='ios', kind='log', event_id=f'{index + 1:032x}',
                    occurred_at='2026-09-10T12:00:00+00:00', visible_at=visible, eligible=eligible)

    def test_missing_receipts_stay_in_denominator_even_when_p95_is_fast(self):
        rows = [self.row(i) for i in range(20)]
        rows[-1]['visible_at'] = ''
        result = next(r for r in self.report.summarize(rows) if r['component'] == 'ios' and r['kind'] == 'log')
        self.assertEqual(result['eligible'], 20)
        self.assertEqual(result['received'], 19)
        self.assertEqual(result['p95_seconds'], 30)
        self.assertEqual(result['status'], 'missing_receipts')

    def test_too_few_and_excluded_samples_cannot_pass(self):
        rows = [self.row(i, eligible='false' if i else 'true') for i in range(20)]
        result = next(r for r in self.report.summarize(rows) if r['component'] == 'ios' and r['kind'] == 'log')
        self.assertEqual(result['eligible'], 1)
        self.assertEqual(result['excluded'], 19)
        self.assertEqual(result['status'], 'insufficient_samples')

    def test_duplicates_broken_cells_and_clock_skew_are_rejected(self):
        for rows in ([self.row(0), self.row(0)], [self.row(0, None)],
                     [self.row(0, '2026-09-10T11:59:59Z')], [self.row(0, '2026-09-10T12:00:30')]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                self.report.summarize(rows)

    def test_all_components_and_kinds_require_their_own_twenty_receipts(self):
        rows = []
        for component in self.report.COMPONENTS:
            for kind in ('error', 'log'):
                rows += [dict(self.row(i), component=component, kind=kind) for i in range(20)]
        report = self.report.summarize(rows)
        self.assertEqual(len(report), 10)
        self.assertTrue(all(r['status'] == 'pass' for r in report))


class DesktopAnalyticsIngressConfigTest(unittest.TestCase):
    """The desktop gate must stage/verify the public ingress origin and never an MP API secret."""

    # Syntactically valid placeholders only; no real project, host, or credential is referenced.
    DSN = 'https://' + '0123456789abcdef' * 2 + '@o1.ingest.sentry.io/1'
    ENDPOINT = 'https://pocket.ark-nexus.cc'

    def setUp(self):
        self.config = load_script('observability-release-config.py')

    def jar(self, folder, entries, name='lib/app.jar'):
        archive = pathlib.Path(folder) / name
        archive.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive, 'w') as output:
            for entry, text in entries:
                output.writestr(entry, text)
        return archive

    def desktop_entries(self):
        return [(self.config.RESOURCE, self.config.render('desktop', 'production', self.DSN)),
                (self.config.ANALYTICS_RESOURCE, self.config.render_analytics(self.ENDPOINT))]

    def test_desktop_stage_writes_both_public_resources_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as folder:
            root = pathlib.Path(folder)
            self.config.stage(root, 'desktop', 'production', self.DSN, self.ENDPOINT)
            sentry = root / self.config.TARGETS['desktop']
            ingress = root / self.config.ANALYTICS_TARGETS['desktop']
            self.assertEqual(sentry.read_text(), f'environment=production\ndsn.desktop={self.DSN}\n')
            self.assertEqual(ingress.read_text(), f'endpoint={self.ENDPOINT}\n')
            with self.assertRaises(FileExistsError):
                self.config.stage(root, 'desktop', 'production', self.DSN, self.ENDPOINT)

    def test_desktop_stage_without_a_valid_endpoint_writes_nothing(self):
        for endpoint in ('', None, 'https://pocket.ark-nexus.cc/v1/analytics'):
            with self.subTest(endpoint=endpoint), tempfile.TemporaryDirectory() as folder:
                with self.assertRaises(ValueError):
                    self.config.stage(folder, 'desktop', 'production', self.DSN, endpoint)
                self.assertEqual(list(pathlib.Path(folder).rglob('*')), [])

    def test_verify_passes_with_exactly_one_sentry_and_one_analytics_resource(self):
        with tempfile.TemporaryDirectory() as folder:
            self.jar(folder, self.desktop_entries())
            self.config.verify(folder, 'desktop', 'production', self.DSN, self.ENDPOINT)

    def test_verify_rejects_missing_duplicate_altered_or_private_analytics_config(self):
        sentry = (self.config.RESOURCE, self.config.render('desktop', 'production', self.DSN))
        ingress = (self.config.ANALYTICS_RESOURCE, self.config.render_analytics(self.ENDPOINT))
        other = (self.config.ANALYTICS_RESOURCE, 'endpoint=https://other.example.com\n')
        for entries in ([sentry],
                        [sentry, ingress, ('nested/' + self.config.ANALYTICS_RESOURCE, ingress[1])],
                        [sentry, other],
                        [sentry, ingress, ('ga4.properties', 'api.secret=private')]):
            with self.subTest(entries=[name for name, _ in entries]), tempfile.TemporaryDirectory() as folder:
                self.jar(folder, entries)
                with self.assertRaises(ValueError):
                    self.config.verify(folder, 'desktop', 'production', self.DSN, self.ENDPOINT)

    def test_non_desktop_artifact_must_not_carry_the_ingress_resource(self):
        with tempfile.TemporaryDirectory() as folder:
            self.jar(folder, [(self.config.RESOURCE, self.config.render('daemon', 'production', self.DSN)),
                              (self.config.ANALYTICS_RESOURCE, self.config.render_analytics(self.ENDPOINT))])
            with self.assertRaises(ValueError):
                self.config.verify(folder, 'daemon', 'production', self.DSN)

    def test_endpoint_must_be_a_bare_https_origin(self):
        for endpoint in ('http://pocket.ark-nexus.cc', 'https://pocket.ark-nexus.cc/v1', 'https://pocket.ark-nexus.cc/',
                         'https://pocket.ark-nexus.cc?id=G-1', 'https://pocket.ark-nexus.cc#x', 'https://Pocket.Ark-Nexus.cc',
                         'https://localhost', 'https://', '', None, 'https://a.b\napi.secret=private', 'https://a.b' + 'c' * 256):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                self.config.render_analytics(endpoint)
        for endpoint in ('https://pocket.ark-nexus.cc', 'https://relay.pairlet.org:8443'):
            with self.subTest(endpoint=endpoint):
                self.assertEqual(self.config.render_analytics(endpoint), f'endpoint={endpoint}\n')

    def test_cli_reads_both_public_values_from_the_environment(self):
        with tempfile.TemporaryDirectory() as folder:
            self.jar(folder, self.desktop_entries())
            argv = ['observability-release-config.py', 'desktop', '--verify', folder]
            for endpoint, expected in ((self.ENDPOINT, 0), ('', 1), ('https://pocket.ark-nexus.cc/v1', 1)):
                environment = {'PAIRLET_SENTRY_DSN': self.DSN, 'PAIRLET_ANALYTICS_ENDPOINT': endpoint}
                out, err = io.StringIO(), io.StringIO()
                with self.subTest(endpoint=endpoint), mock.patch.dict(os.environ, environment), \
                        mock.patch.object(sys, 'argv', argv), \
                        contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    self.assertEqual(self.config.main(), expected)
                # Diagnostics must never echo the configured values back into a public build log.
                self.assertNotIn(self.DSN, out.getvalue() + err.getvalue())
                self.assertNotIn('ark-nexus', out.getvalue() + err.getvalue())


if __name__ == '__main__':
    unittest.main()
