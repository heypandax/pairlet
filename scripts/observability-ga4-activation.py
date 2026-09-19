#!/usr/bin/env python3
"""Read-only GA4 activation report for issue #342.

Behavioural rows are written to _local/ only; stdout carries file paths, the sample gate and row
counts. Credentials are never printed, and a service account key living inside the repository is
rejected outright.
"""
import argparse
import datetime as dt
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request
from collections import defaultdict

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_PROPERTY = "540841272"
DEFAULT_START = "2026-09-07"
DEFAULT_MIN_USERS = 30
SCOPE = "https://www.googleapis.com/auth/analytics.readonly"
ENDPOINT = "https://analyticsdata.googleapis.com/v1beta/properties/{}{}"
TIMEOUT = 30
MAX_BYTES = 4 * 1024 * 1024
METRICS = ("eventCount", "totalUsers")
SCHEMA_VALUES = ("1", "v1")

FUNNEL_EVENTS = ("app_launch", "onboarding_shown", "onboarding_cta", "demo_entered",
                 "pair_started", "paired", "pair_failed", "connected", "value_reached")
STEP_EVENTS = ("onboarding_shown", "demo_entered", "pair_started", "paired", "value_reached")
RATIOS = (("pair_started", "onboarding_shown"), ("paired", "pair_started"),
          ("value_reached", "paired"))
# Stable onboarding_cta targets, mirrored from OnboardingScreen.kt; unknown targets are reported.
CTA_TARGETS = ("pair_now", "pair_now_docked", "enter_code", "demo", "guide", "support", "close",
               "what_is_cli", "win_cli_cta", "copy_install", "os_segment", "install_method",
               "github", "privacy")
REQUIRED_DIMENSIONS = ("customEvent:analytics_schema", "customEvent:app_environment",
                       "customEvent:app_platform", "customEvent:internal_traffic",
                       "customEvent:reason", "customEvent:target", "customEvent:value")
TOP_COUNTRIES = 15
OTHERS = "(others)"
TITLES = ("激活漏斗总览", "引导页操作拆分", "配对失败原因", "按国家的漏斗", "按平台的漏斗")


class ApiError(Exception):
    """Carries only the HTTP status code and the machine-readable error status, never server prose."""

    def __init__(self, code, status, mentions_custom_event=False):
        super().__init__("GA4 request failed")
        self.code = code
        self.status = status
        self.mentions_custom_event = mentions_custom_event


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _error_status(body):
    try:
        payload = json.loads(body)
        status = payload["error"]["status"]
    except (ValueError, TypeError, KeyError):
        return "UNKNOWN"
    return status if isinstance(status, str) and re.fullmatch(r"[A-Z_]{1,60}", status) else "UNKNOWN"


def _call(url, token, payload=None):
    data = None if payload is None else json.dumps(payload, allow_nan=False).encode()
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data, headers, method="GET" if data is None else "POST")
    try:
        with urllib.request.build_opener(_NoRedirect()).open(request, timeout=TIMEOUT) as response:
            body = response.read(MAX_BYTES + 1)
            if len(body) > MAX_BYTES:
                raise ApiError(getattr(response, "status", 200), "RESPONSE_TOO_LARGE")
            return json.loads(body)
    except urllib.error.HTTPError as error:
        try:
            body = error.read(MAX_BYTES + 1)[:MAX_BYTES]
        except (OSError, ValueError):
            body = b""
        # `from None` keeps the server's message out of any traceback we might surface.
        raise ApiError(error.code, _error_status(body), b"customEvent:" in body) from None
    except (urllib.error.URLError, OSError, ValueError, TypeError):
        raise ApiError(0, "UNAVAILABLE") from None


def run_report(token, property_id, body):
    """POST one runReport request. Mocked in tests; the process never retries."""
    return _call(ENDPOINT.format(property_id, ":runReport"), token, body)


def metadata(token, property_id):
    """GET the property metadata, used by --list-dimensions."""
    return _call(ENDPOINT.format(property_id, "/metadata"), token)


def _stdlib_transport():
    """A google.auth transport on urllib, so the token exchange needs neither `requests` nor a
    private google-auth module. Only the public Request/Response contract is implemented."""
    from google.auth import exceptions, transport

    class Response(transport.Response):
        def __init__(self, status, headers, data):
            self._status, self._headers, self._data = status, headers, data

        @property
        def status(self):
            return self._status

        @property
        def headers(self):
            return self._headers

        @property
        def data(self):
            return self._data

    class Request(transport.Request):
        def __call__(self, url, method="GET", body=None, headers=None, timeout=TIMEOUT, **kwargs):
            request = urllib.request.Request(url, body, dict(headers or {}), method=method)
            try:
                with urllib.request.build_opener(_NoRedirect()).open(request, timeout=timeout) as response:
                    return Response(response.status, dict(response.headers), response.read(MAX_BYTES))
            except urllib.error.HTTPError as error:
                return Response(error.code, dict(error.headers), error.read(MAX_BYTES))
            except (urllib.error.URLError, OSError):
                raise exceptions.TransportError("token exchange failed") from None

    return Request()


def access_token(path):
    """Mint a read-only access token from a service account key. Mocked in tests."""
    try:
        from google.oauth2 import service_account
    except ImportError:
        raise SystemExit("缺少 google-auth 依赖，安装后重试：pip install google-auth")
    credentials = service_account.Credentials.from_service_account_file(str(path), scopes=[SCOPE])
    credentials.refresh(_stdlib_transport())
    return credentials.token


def exact(field, value):
    return {"filter": {"fieldName": field, "stringFilter": {"matchType": "EXACT", "value": value}}}


def in_list(field, values):
    return {"filter": {"fieldName": field, "inListFilter": {"values": list(values)}}}


def not_exact(field, value):
    return {"notExpression": exact(field, value)}


def common_filters(environment, include_internal, overseas):
    """The three shared guards, plus the optional overseas slice. Order is asserted by the tests."""
    expressions = [exact("customEvent:app_environment", environment)]
    if not include_internal:
        expressions.append(exact("customEvent:internal_traffic", "0"))
    expressions.append(in_list("customEvent:analytics_schema", SCHEMA_VALUES))
    if overseas:
        expressions.append(not_exact("country", "China"))
    return expressions


def report_body(dimensions, expressions, start, end, limit):
    return {"dateRanges": [{"startDate": start, "endDate": end}],
            "dimensions": [{"name": name} for name in dimensions],
            "metrics": [{"name": name} for name in METRICS],
            "dimensionFilter": {"andGroup": {"expressions": list(expressions)}},
            "limit": limit, "keepEmptyRows": False}


def table_bodies(start, end, expressions):
    """The five runReport bodies, in report order."""
    return [
        report_body(["eventName"], expressions + [in_list("eventName", FUNNEL_EVENTS)], start, end, 50),
        report_body(["customEvent:target", "customEvent:value"],
                    expressions + [exact("eventName", "onboarding_cta")], start, end, 200),
        report_body(["customEvent:reason", "customEvent:app_platform"],
                    expressions + [exact("eventName", "pair_failed")], start, end, 200),
        report_body(["country", "eventName"], expressions + [in_list("eventName", STEP_EVENTS)],
                    start, end, 200),
        report_body(["customEvent:app_platform", "eventName"],
                    expressions + [in_list("eventName", STEP_EVENTS)], start, end, 200),
    ]


def parse_rows(response):
    rows = []
    for row in (response or {}).get("rows") or []:
        dimensions = [str(item.get("value", "")) for item in row.get("dimensionValues") or []]
        values = []
        for item in row.get("metricValues") or []:
            try:
                values.append(int(float(item.get("value") or 0)))
            except (TypeError, ValueError):
                values.append(0)
        values += [0] * (len(METRICS) - len(values))
        rows.append({"dimensions": dimensions, "eventCount": values[0], "totalUsers": values[1]})
    return rows


def funnel(rows):
    """Report order is the funnel order, not the row order GA4 happens to return."""
    counted = {}
    for row in rows:
        if row["dimensions"]:
            entry = counted.setdefault(row["dimensions"][0], {"eventCount": 0, "totalUsers": 0})
            entry["eventCount"] += row["eventCount"]
            entry["totalUsers"] += row["totalUsers"]
    return [{"event": event, "eventCount": counted.get(event, {}).get("eventCount", 0),
             "totalUsers": counted.get(event, {}).get("totalUsers", 0)} for event in FUNNEL_EVENTS]


def ratios(steps):
    users = {row["event"]: row["totalUsers"] for row in steps}
    result = []
    for numerator, denominator in RATIOS:
        base = users.get(denominator, 0)
        result.append({"numerator": numerator, "denominator": denominator,
                       "ratio": None if not base else users.get(numerator, 0) / base})
    return result


def sorted_rows(rows):
    return sorted(rows, key=lambda row: (-row["totalUsers"], -row["eventCount"], row["dimensions"]))


def wide_table(rows, top=TOP_COUNTRIES):
    """Collapse (key, eventName) rows into a key x step table; the tail becomes one (others) row."""
    groups = defaultdict(lambda: defaultdict(int))
    for row in rows:
        if len(row["dimensions"]) < 2:
            continue
        groups[row["dimensions"][0]][row["dimensions"][1]] += row["totalUsers"]
    ordered = sorted(groups.items(), key=lambda item: (-item[1].get(STEP_EVENTS[0], 0), item[0]))
    table = [{"key": key, "steps": {event: values.get(event, 0) for event in STEP_EVENTS}}
             for key, values in ordered[:top]]
    tail = ordered[top:]
    if tail:
        merged = {event: sum(values.get(event, 0) for _, values in tail) for event in STEP_EVENTS}
        table.append({"key": OTHERS, "steps": merged})
    return table


def sample_status(steps, minimum):
    users = next((row["totalUsers"] for row in steps if row["event"] == "onboarding_shown"), 0)
    if users < minimum:
        return users, f"样本不足：onboarding_shown 仅 {users} 个安装身份，以下比例不能作为结论"
    return users, f"样本 {users}"


def percent(ratio):
    return "n/a" if ratio is None else f"{ratio * 100:.1f}%"


def cell(value):
    text = str(value).replace("|", r"\|").replace("\n", " ").replace("\r", " ")
    return text[:60] if text else "(empty)"


def render(report):
    """Build the Markdown body. Chinese prose uses full-width punctuation by repository rule."""
    status = report["sample"]["status"]
    lines = ["# Pairlet 激活报表（issue #342）", "",
             f"- 日期范围：{report['start']} 至 {report['end']}（GA4 报表时区为 UTC+8，本机日期可能更早一天）",
             f"- 媒体资源：{report['property']}",
             "- 统一过滤：`app_environment` = {}；{}；`analytics_schema` IN (\"1\", \"v1\"){}".format(
                 report["environment"],
                 "未排除内部流量（--include-internal）" if report["include_internal"]
                 else "`internal_traffic` = 0",
                 "；`country` != China（--overseas）" if report["overseas"] else ""),
             "- 指标：`eventCount` 为事件量，`totalUsers` 为参与采集的安装身份，不是去重真人。", "",
             f"样本状态：{status}", ""]

    lines += [f"## 1. {TITLES[0]}", "",
              f"样本状态：{status}；本表 {len(report['funnel'])} 行。", "",
              "| 事件 | 事件量 | 安装身份 |", "|---|---:|---:|"]
    for row in report["funnel"]:
        lines.append(f"| `{row['event']}` | {row['eventCount']} | {row['totalUsers']} |")
    lines += ["", "相邻步骤比例（按安装身份，分母为 0 时写 n/a）：", "",
              "| 步骤 | 比例 |", "|---|---:|"]
    for item in report["ratios"]:
        lines.append(f"| `{item['numerator']}` / `{item['denominator']}` | {percent(item['ratio'])} |")

    lines += ["", f"## 2. {TITLES[1]}", "",
              f"样本状态：{status}；本表 {len(report['cta'])} 行。", "",
              "| target | value | 事件量 | 安装身份 |", "|---|---|---:|---:|"]
    for row in report["cta"]:
        target = row["dimensions"][0] if row["dimensions"] else ""
        value = row["dimensions"][1] if len(row["dimensions"]) > 1 else ""
        lines.append(f"| {cell(target)} | {cell(value)} | {row['eventCount']} | {row['totalUsers']} |")
    if report["unknown_targets"]:
        lines += ["", "未登记的 target（不在 OnboardingScreen 的常量表里，需要核对埋点）：" +
                  "、".join(cell(name) for name in report["unknown_targets"])]

    lines += ["", f"## 3. {TITLES[2]}", "",
              f"样本状态：{status}；本表 {len(report['failures'])} 行。", "",
              "| reason | 平台 | 事件量 | 安装身份 |", "|---|---|---:|---:|"]
    for row in report["failures"]:
        reason = row["dimensions"][0] if row["dimensions"] else ""
        platform = row["dimensions"][1] if len(row["dimensions"]) > 1 else ""
        lines.append(f"| {cell(reason)} | {cell(platform)} | {row['eventCount']} | {row['totalUsers']} |")

    for index, (title, key, header) in enumerate(
            ((TITLES[3], "countries", "国家"), (TITLES[4], "platforms", "平台")), start=4):
        table = report[key]
        lines += ["", f"## {index}. {title}", "",
                  f"样本状态：{status}；本表 {len(table)} 行。单元格为该步骤的安装身份数（`totalUsers`）。", "",
                  "| " + header + " | " + " | ".join(f"`{event}`" for event in STEP_EVENTS) + " |",
                  "|---|" + "---:|" * len(STEP_EVENTS)]
        for row in table:
            lines.append("| " + cell(row["key"]) + " | " +
                         " | ".join(str(row["steps"][event]) for event in STEP_EVENTS) + " |")

    lines += ["", "## 解释限制", "",
              "- `totalUsers` 是参与采集的安装身份，不是去重真人；同一个人换设备或重装会是两个身份。",
              "- demo 分支与真实配对分开看：`demo_entered` 只说明进入了演示，不证明已配对或真实使用。",
              "- `pair_started` 未取得绑定角色时 `usage_mode` 未知，不能猜成 own 或 shared。",
              "- 既有安装的首次观测不是新安装；本报表不区分安装时间。",
              "- 比例按安装身份计算，不是同一安装在观察窗内的严格序列漏斗。",
              "- `(others)` 行是把尾部国家的 `totalUsers` 相加，GA4 的用户指标不可跨行相加去重，该值是上界不是去重人数。", ""]
    return "\n".join(lines)


def resolve_credentials(parser, value):
    path = value or os.environ.get("GOOGLE_APPLICATION_CREDENTIALS")
    if not path:
        parser.error("需要服务账号 JSON：--credentials 或环境变量 GOOGLE_APPLICATION_CREDENTIALS")
    resolved = pathlib.Path(path).expanduser().resolve()
    root = REPO_ROOT.resolve()
    if resolved == root or root in resolved.parents:
        parser.error("服务账号密钥不能放在仓库内；把它移到仓库之外再运行")
    if not resolved.is_file():
        parser.error("服务账号 JSON 不存在或不是文件")
    return resolved


def resolve_out_dir(parser, value):
    root = REPO_ROOT.resolve()
    local = root / "_local"
    path = (pathlib.Path(value).expanduser().resolve() if value
            else (local / "observability").resolve())
    inside_repo = path == root or root in path.parents
    inside_local = path == local or local in path.parents
    if inside_repo and not inside_local:
        parser.error("--out-dir 位于仓库内时必须在 _local/ 之下；用户行为数据不进 Git")
    return path


def valid_date(value):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value or ""):
        raise argparse.ArgumentTypeError("日期格式必须是 YYYY-MM-DD")
    try:
        dt.date.fromisoformat(value)
    except ValueError:
        raise argparse.ArgumentTypeError("日期不存在")
    return value


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--credentials", help="服务账号 JSON 路径，默认读 GOOGLE_APPLICATION_CREDENTIALS")
    parser.add_argument("--property", default=DEFAULT_PROPERTY)
    parser.add_argument("--start", type=valid_date, default=DEFAULT_START)
    parser.add_argument("--end", type=valid_date, default=None, help="默认为本机日期的昨天")
    parser.add_argument("--environment", choices=("production", "staging"), default="production")
    parser.add_argument("--include-internal", action="store_true")
    parser.add_argument("--overseas", action="store_true")
    parser.add_argument("--min-users", type=int, default=DEFAULT_MIN_USERS)
    parser.add_argument("--out-dir")
    parser.add_argument("--list-dimensions", action="store_true")
    return parser


def report_api_error(error, dimensions):
    print(f"GA4 请求失败：HTTP {error.code} status={error.status}")
    if error.code == 400 and error.mentions_custom_event:
        print("某个自定义维度尚未在 GA4 注册；本次涉及的维度：" + ", ".join(dimensions))


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):  # Chinese output must not depend on the console codepage.
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = build_parser()
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[0-9]{1,20}", args.property):
        parser.error("--property 必须是数字媒体资源 ID")
    credentials = resolve_credentials(parser, args.credentials)
    out_dir = resolve_out_dir(parser, args.out_dir)
    end = args.end or (dt.date.today() - dt.timedelta(days=1)).isoformat()
    if end < args.start:
        parser.error("--end 早于 --start")

    token = access_token(credentials)
    if args.list_dimensions:
        try:
            payload = metadata(token, args.property)
        except ApiError as error:
            report_api_error(error, list(REQUIRED_DIMENSIONS))
            return 1
        names = sorted({str(item.get("apiName", "")) for item in (payload or {}).get("dimensions") or []
                        if str(item.get("apiName", "")).startswith("customEvent:")})
        for name in names:
            print(name)
        missing = [name for name in REQUIRED_DIMENSIONS if name not in names]
        print("缺失的维度：" + (", ".join(missing) if missing else "无"))
        return 0

    expressions = common_filters(args.environment, args.include_internal, args.overseas)
    bodies = table_bodies(args.start, end, expressions)
    responses = []
    for body in bodies:
        dimensions = [item["name"] for item in body["dimensions"]] + list(REQUIRED_DIMENSIONS)
        try:
            responses.append(parse_rows(run_report(token, args.property, body)))
        except ApiError as error:
            report_api_error(error, sorted(set(dimensions)))
            return 1

    steps = funnel(responses[0])
    users, status = sample_status(steps, args.min_users)
    cta = sorted_rows(responses[1])
    report = {
        "property": args.property, "start": args.start, "end": end, "timezone": "UTC+8",
        "environment": args.environment, "include_internal": args.include_internal,
        "overseas": args.overseas,
        "sample": {"onboarding_shown_users": users, "min_users": args.min_users, "status": status},
        "funnel": steps, "ratios": ratios(steps), "cta": cta,
        "unknown_targets": sorted({row["dimensions"][0] for row in cta
                                   if row["dimensions"] and row["dimensions"][0] not in CTA_TARGETS
                                   and row["dimensions"][0] != "(not set)"}),
        "failures": sorted_rows(responses[2]),
        "countries": wide_table(responses[3]), "platforms": wide_table(responses[4]),
    }
    out_dir.mkdir(parents=True, exist_ok=True)
    stem = f"ga4-activation-{args.start}_{end}"
    markdown = out_dir / (stem + ".md")
    raw = out_dir / (stem + ".json")
    markdown.write_text(render(report), encoding="utf-8")
    raw.write_text(json.dumps({"report": report, "requests": bodies, "rows": [
        {"table": title, "rows": rows} for title, rows in zip(TITLES, responses)]},
        ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")

    # Behavioural rows stay in the files; stdout only says where they are and how big they are.
    print(f"已写入 {markdown}")
    print(f"已写入 {raw}")
    print(f"样本状态：{status}")
    for title, count in zip(TITLES, (len(report["funnel"]), len(report["cta"]), len(report["failures"]),
                                     len(report["countries"]), len(report["platforms"]))):
        print(f"{title}：{count} 行")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
