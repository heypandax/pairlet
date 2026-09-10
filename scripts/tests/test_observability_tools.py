"""Isolated fixtures only; no Sentry, GA4, device or production service traffic."""
import importlib.util
import pathlib
import unittest

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


if __name__ == '__main__':
    unittest.main()
