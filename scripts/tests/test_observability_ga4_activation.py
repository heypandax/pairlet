"""Isolated fixtures only; every GA4 call is mocked and no network request is ever made."""
import contextlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
import urllib.error
from unittest import mock

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
SENTINEL = 424242


def load_script(filename):
    spec = importlib.util.spec_from_file_location(filename.replace('-', '_'), SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def response(rows):
    return {'rows': [{'dimensionValues': [{'value': value} for value in dimensions],
                      'metricValues': [{'value': str(events)}, {'value': str(users)}]}
                     for dimensions, events, users in rows]}


EMPTY = response([])


class ActivationReportTest(unittest.TestCase):
    def setUp(self):
        self.report = load_script('observability-ga4-activation.py')
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        base = pathlib.Path(self.folder.name).resolve()
        self.root = base / 'repo'
        (self.root / 'scripts').mkdir(parents=True)
        self.report.REPO_ROOT = self.root
        self.credentials = base / 'service-account.json'
        self.credentials.write_text('{}', encoding='utf-8')

    def run_main(self, extra=(), responses=None, expected=0):
        """Drive main() with mocked auth and transport; returns (bodies, stdout, stderr)."""
        payloads = list(responses or [EMPTY] * 5)
        bodies = []

        def fake_run_report(token, property_id, body):
            bodies.append(body)
            return payloads[len(bodies) - 1]

        out, err = io.StringIO(), io.StringIO()
        argv = ['--credentials', str(self.credentials), *extra]
        with mock.patch.object(self.report, 'access_token', return_value='token'), \
                mock.patch.object(self.report, 'run_report', side_effect=fake_run_report), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(self.report.main(argv), expected)
        return bodies, out.getvalue(), err.getvalue()

    def written(self):
        folder = self.root / '_local' / 'observability'
        markdown = next(folder.glob('*.md'))
        raw = next(folder.glob('*.json'))
        return (markdown.read_text(encoding='utf-8'),
                json.loads(raw.read_text(encoding='utf-8')), markdown, raw)

    def test_a_key_inside_the_repository_is_refused_and_one_outside_is_accepted(self):
        inside = self.root / 'ga4-service-account.json'
        inside.write_text('{}', encoding='utf-8')
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as raised:
            self.report.main(['--credentials', str(inside)])
        self.assertEqual(raised.exception.code, 2)
        self.assertIn('仓库内', err.getvalue())
        self.run_main()  # The same run with the key outside the repository succeeds.

    def test_out_dir_inside_the_repository_must_live_under_local(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit):
            self.report.main(['--credentials', str(self.credentials),
                              '--out-dir', str(self.root / 'docs' / 'observability')])
        self.assertIn('_local/', err.getvalue())

    def test_funnel_keeps_report_order_and_ratios_guard_a_zero_denominator(self):
        rows = [(['value_reached'], 3, 0), (['onboarding_shown'], 90, 40),
                (['pair_started'], 30, 10), (['paired'], 0, 0), (['app_launch'], 200, 80)]
        self.run_main(responses=[response(rows), EMPTY, EMPTY, EMPTY, EMPTY])
        markdown, raw, _, _ = self.written()
        self.assertEqual([row['event'] for row in raw['report']['funnel']],
                         list(self.report.FUNNEL_EVENTS))
        self.assertEqual([item['ratio'] for item in raw['report']['ratios']], [0.25, 0.0, None])
        self.assertIn('| `pair_started` / `onboarding_shown` | 25.0% |', markdown)
        self.assertIn('| `value_reached` / `paired` | n/a |', markdown)

    def test_country_table_keeps_fifteen_rows_and_merges_the_tail_into_others(self):
        rows = []
        for index in range(18):
            rows.append(([f'Country{index:02d}', 'onboarding_shown'], 10, 100 - index))
            rows.append(([f'Country{index:02d}', 'paired'], 2, 1))
        self.run_main(responses=[EMPTY, EMPTY, EMPTY, response(rows), EMPTY])
        markdown, raw, _, _ = self.written()
        table = raw['report']['countries']
        self.assertEqual(len(table), 16)
        self.assertEqual([row['key'] for row in table][:2], ['Country00', 'Country01'])
        self.assertEqual(table[-1]['key'], '(others)')
        self.assertEqual(table[-1]['steps']['onboarding_shown'], 85 + 84 + 83)
        self.assertEqual(table[-1]['steps']['paired'], 3)
        self.assertIn('| (others) |', markdown)
        self.assertNotIn('Country17', markdown)

    def test_sample_gate_switches_on_the_threshold(self):
        rows = [(['onboarding_shown'], 20, 5)]
        _, out, _ = self.run_main(responses=[response(rows), EMPTY, EMPTY, EMPTY, EMPTY])
        markdown, raw, _, _ = self.written()
        self.assertIn('样本不足：onboarding_shown 仅 5 个安装身份，以下比例不能作为结论', markdown)
        self.assertIn('样本不足', out)
        self.assertEqual(raw['report']['sample']['min_users'], 30)
        _, out, _ = self.run_main(extra=['--min-users', '5'],
                                  responses=[response(rows), EMPTY, EMPTY, EMPTY, EMPTY])
        markdown, _, _, _ = self.written()
        self.assertIn('样本状态：样本 5', markdown)
        self.assertNotIn('样本不足', out)

    def test_both_files_land_in_local_observability_with_the_date_range_in_the_name(self):
        self.run_main(extra=['--start', '2026-09-07', '--end', '2026-09-16'])
        markdown, raw, markdown_path, raw_path = self.written()
        folder = self.root / '_local' / 'observability'
        self.assertEqual(markdown_path, folder / 'ga4-activation-2026-09-07_2026-09-16.md')
        self.assertEqual(raw_path, folder / 'ga4-activation-2026-09-07_2026-09-16.json')
        self.assertTrue(markdown.startswith('# Pairlet 激活报表（issue #342）'))
        self.assertIn('UTC+8', markdown)
        self.assertIn('安装身份', markdown)
        self.assertEqual(len(raw['requests']), 5)
        self.assertEqual(len(raw['rows']), 5)
        self.assertNotIn('token', json.dumps(raw['requests']))

    def test_every_request_carries_the_three_shared_filters(self):
        bodies, _, _ = self.run_main()
        self.assertEqual(len(bodies), 5)
        for body in bodies:
            expressions = body['dimensionFilter']['andGroup']['expressions']
            self.assertEqual(expressions[0], self.report.exact('customEvent:app_environment', 'production'))
            self.assertEqual(expressions[1], self.report.exact('customEvent:internal_traffic', '0'))
            self.assertEqual(expressions[2], self.report.in_list('customEvent:analytics_schema', ['1', 'v1']))
            self.assertNotIn(self.report.not_exact('country', 'China'), expressions)
            self.assertEqual([metric['name'] for metric in body['metrics']], ['eventCount', 'totalUsers'])

    def test_overseas_staging_and_include_internal_change_only_their_own_filter(self):
        bodies, _, _ = self.run_main(extra=['--overseas'])
        for body in bodies:
            expressions = body['dimensionFilter']['andGroup']['expressions']
            self.assertEqual(expressions[3], self.report.not_exact('country', 'China'))
        bodies, _, _ = self.run_main(extra=['--environment', 'staging'])
        for body in bodies:
            expressions = body['dimensionFilter']['andGroup']['expressions']
            self.assertEqual(expressions[0], self.report.exact('customEvent:app_environment', 'staging'))
            self.assertNotIn(self.report.exact('customEvent:app_environment', 'production'), expressions)
        bodies, _, _ = self.run_main(extra=['--include-internal'])
        for body in bodies:
            expressions = body['dimensionFilter']['andGroup']['expressions']
            self.assertNotIn(self.report.exact('customEvent:internal_traffic', '0'), expressions)

    def test_the_five_tables_ask_for_the_documented_dimensions(self):
        bodies, _, _ = self.run_main()
        self.assertEqual([[item['name'] for item in body['dimensions']] for body in bodies],
                         [['eventName'], ['customEvent:target', 'customEvent:value'],
                          ['customEvent:reason', 'customEvent:app_platform'],
                          ['country', 'eventName'], ['customEvent:app_platform', 'eventName']])
        self.assertEqual(bodies[3]['limit'], 200)

    def test_a_failed_response_leaks_neither_the_server_message_nor_its_identifiers(self):
        message = 'Field customEvent:target is not a valid dimension for project 1234567890'
        raw = json.dumps({'error': {'code': 400, 'status': 'INVALID_ARGUMENT', 'message': message}}).encode()
        failure = urllib.error.HTTPError('https://analyticsdata.googleapis.com/', 400,
                                         'Bad Request', {}, io.BytesIO(raw))
        opener = mock.Mock()
        opener.open.side_effect = failure
        with mock.patch.object(self.report.urllib.request, 'build_opener', return_value=opener), \
                self.assertRaises(self.report.ApiError) as raised:
            self.report.run_report('token', '540841272', {})
        self.assertEqual((raised.exception.code, raised.exception.status), (400, 'INVALID_ARGUMENT'))
        self.assertTrue(raised.exception.mentions_custom_event)
        self.assertNotIn('1234567890', str(raised.exception))

        out, err = io.StringIO(), io.StringIO()
        argv = ['--credentials', str(self.credentials)]
        with mock.patch.object(self.report, 'access_token', return_value='token'), \
                mock.patch.object(self.report, 'run_report', side_effect=raised.exception), \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(self.report.main(argv), 1)
        printed = out.getvalue() + err.getvalue()
        self.assertIn('HTTP 400', printed)
        self.assertIn('INVALID_ARGUMENT', printed)
        self.assertIn('customEvent:target', printed)  # From our own constants, not the response.
        self.assertNotIn('not a valid dimension', printed)
        self.assertNotIn('1234567890', printed)

    def test_stdout_reports_only_locations_counts_and_the_sample_line(self):
        funnel = response([(['onboarding_shown'], 90, 40), (['pair_started'], 30, 10)])
        cta = response([(['demo', '(not set)'], SENTINEL, 7), (['pair_now', '(not set)'], 12, 9)])
        failures = response([(['redeem', 'ios'], SENTINEL, 3)])
        _, out, err = self.run_main(responses=[funnel, cta, failures, EMPTY, EMPTY])
        self.assertNotIn(str(SENTINEL), out + err)
        self.assertIn('已写入', out)
        self.assertIn('激活漏斗总览：9 行', out)
        self.assertIn('引导页操作拆分：2 行', out)
        self.assertIn('配对失败原因：1 行', out)
        markdown, raw, _, _ = self.written()
        self.assertIn(str(SENTINEL), markdown)  # The rows themselves stay in the private file.
        self.assertEqual([row['dimensions'][0] for row in raw['report']['cta']], ['pair_now', 'demo'])

    def test_list_dimensions_prints_registered_and_missing_custom_dimensions(self):
        payload = {'dimensions': [{'apiName': 'country'}, {'apiName': 'customEvent:target'},
                                  {'apiName': 'customEvent:reason'}]}
        out = io.StringIO()
        with mock.patch.object(self.report, 'access_token', return_value='token'), \
                mock.patch.object(self.report, 'metadata', return_value=payload) as call, \
                contextlib.redirect_stdout(out):
            self.assertEqual(self.report.main(['--credentials', str(self.credentials),
                                               '--list-dimensions']), 0)
        call.assert_called_once_with('token', '540841272')
        printed = out.getvalue()
        self.assertIn('customEvent:target', printed)
        self.assertNotIn('\ncountry\n', printed)
        self.assertIn('customEvent:app_platform', printed.split('缺失的维度：')[1])
        self.assertNotIn('customEvent:reason', printed.split('缺失的维度：')[1])


if __name__ == '__main__':
    unittest.main()
