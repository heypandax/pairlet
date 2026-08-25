#!/usr/bin/env python3
"""Gate for the PUBLIC surface: exclusions, the capability contract, and asset provenance.

The public site and both READMEs make a small number of load-bearing factual claims. This script is
the thing that stops them drifting back — it is stdlib-only, needs no build step, and is safe to run
on a clean checkout.

It fails non-zero when:

  1. a prohibited promotional term appears in any public target — including page metadata, JSON-LD,
     alt text, the AI index files and the web manifest;
  2. the agent matrix is not exactly six rows x four capability columns, or any cell disagrees with
     the frozen expectation below;
  3. the v1.8.0 baseline, the source commit, the Preview label, OpenCode's Full-access statement or
     DeepSeek's Limited state drift;
  4. an official Linux DESKTOP binary is implied;
  5. the generated-asset manifest is missing, incomplete, or points at a file that is absent, empty
     or no longer matches its recorded hash.

Two exclusions are deliberate and must stay:

  * This file names the prohibited features as PATTERNS. That is what a gate is; the patterns are
    what makes it work.
  * `docs/design/**` is the internal design handoff, `marketing/**` is the generation pipeline, and
    the retained user-manual pages under `site/manual/{en,zh}/share-a-folder/` are kept for the
    users who already rely on them. None of those are public promotional surfaces, so none of them
    are scanned. The manual pages must instead be `noindex` and absent from every public index —
    scripts/check-site-seo.py enforces the sitemap half of that.

Usage: python3 scripts/check-public-content.py  ->  exit 0 all green, exit 1 otherwise
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "site" / "public-capabilities.json"
MEDIA_DIR = ROOT / "site" / "assets" / "product"
MANIFEST = MEDIA_DIR / "manifest.json"

# ── the explicit public target list ─────────────────────────────────────────────────────────────
# Everything a visitor, a crawler or an assistant can reach as MARKETING. Kept explicit on purpose:
# a glob would sweep in the internal handoff, the generator sources and this checker itself.
PUBLIC_TARGETS = [
    "README.md",
    "README.zh-CN.md",
    "site/index.html",
    "site/zh/index.html",
    "site/features.html",
    "site/privacy.html",
    "site/app.js",
    "site/styles.css",
    "site/llms.txt",
    "site/sitemap.xml",
    "site/robots.txt",
    "site/site.webmanifest",
    "site/public-capabilities.json",
    "site/assets/product/manifest.json",
    "site/guides/claude-code-mobile-remote.html",
    "site/guides/codex-mobile-remote.html",
    "site/guides/compare-remote-options.html",
    "site/guides/faq.html",
    "site/guides/security.html",
    "site/guides/self-hosting.html",
    "site/zh/claude-code-mobile-remote.html",
    "site/zh/codex-mobile-remote.html",
    "site/manual/index.html",
    "site/manual/en/index.html",
    "site/manual/zh/index.html",
    "site/manual/ai-index.json",
    "site/manual/llms-full.txt",
    "site/support/index.html",
]

# ── 1 · exclusions ──────────────────────────────────────────────────────────────────────────────
# The two features themselves, their Chinese names, and the indirect substitutes that would promote
# them without naming them. Case-insensitive.
EXCLUDED = {
    "session handoff": r"session\s*handoff",
    "handoff (any form)": r"handoff",
    "folder share / sharing": r"folder[\s-]*shar(?:e|ing)|shar(?:e|ed|ing)\s+(?:a\s+|one\s+|the\s+)?folder|shared\s+folder",
    "会话接力": r"会话接力",
    "接力": r"接力",
    "文件夹共享 / 共享文件夹": r"文件夹共享|共享文件夹",
    "collaborate / collaboration": r"collaborat",
    "协作": r"协作",
    "guest": r"\bguests?\b",
    "访客": r"访客",
}

# ── 2 · the frozen matrix ───────────────────────────────────────────────────────────────────────
CAPABILITY_COLUMNS = ["coreSession", "approvalMode", "changesDiff", "usage"]
COLUMN_HEADINGS_EN = ["Core session", "Approval & mode", "Changes & diff", "Usage"]
# (contract id, public name, English tag, Chinese tag, the four states in column order)
EXPECTED_AGENTS = [
    ("claude", "Claude Code", None, None, ["yes", "yes", "yes", "yes"]),
    ("codex", "OpenAI Codex", None, None, ["yes", "yes", "yes", "yes"]),
    ("opencode", "OpenCode", None, None, ["yes", "no", "no", "yes"]),
    ("kimi", "Kimi Code", "Preview", "Preview", ["yes", "yes", "no", "yes"]),
    ("zcode", "ZCode", None, None, ["yes", "yes", "no", "yes"]),
    ("deepseek", "DeepSeek Harness", "narrow v1", "有限 v1", ["yes", "yes", "no", "no"]),
]

BASELINE_VERSION = "1.9.2"
SOURCE_COMMIT = "387bc816"
# Files that must state the baseline and must not carry the previous public version.
BASELINE_TARGETS = ["README.md", "README.zh-CN.md", "site/index.html", "site/llms.txt"]
PREVIOUS_VERSION = r"\b1\.9\.0\b"

# Release-asset name shapes that would imply an official Linux desktop build.
LINUX_DESKTOP_ARTIFACTS = r"cc-pocket-desktop-linux|desktop-linux-(?:x86_64|amd64|arm64)|cc-pocket[-_]desktop[^\s\"'<>]*\.(?:AppImage|deb|rpm)"

# Symbol → contract state, for parsing the rendered matrices.
SYMBOL_STATE = {"✓": "yes", "△": "limited", "✕": "no"}

failures: list[str] = []
notes: list[str] = []


def fail(check: str, detail: str) -> None:
    failures.append(f"{check}: {detail}")


def read(rel: str) -> str | None:
    path = ROOT / rel
    if not path.exists():
        fail("targets", f"public target is missing: {rel}")
        return None
    return path.read_text(encoding="utf-8")


def check_exclusions(sources: dict[str, str]) -> None:
    hits = 0
    for rel, text in sources.items():
        for label, pattern in EXCLUDED.items():
            for match in re.finditer(pattern, text, re.I):
                hits += 1
                line = text.count("\n", 0, match.start()) + 1
                context = " ".join(text[max(0, match.start() - 60):match.start() + 60].split())
                fail("exclusions", f"{rel}:{line} prohibited term [{label}] — …{context}…")
    notes.append(f"exclusions: scanned {len(sources)} public targets, {hits} hit(s)")


def check_contract() -> dict | None:
    if not CONTRACT.exists():
        fail("contract", f"missing fact contract {CONTRACT.relative_to(ROOT)}")
        return None
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    baseline = contract.get("baseline", {})
    if baseline.get("version") != BASELINE_VERSION:
        fail("contract", f"baseline.version is {baseline.get('version')!r}, expected {BASELINE_VERSION!r}")
    if baseline.get("sourceCommit") != SOURCE_COMMIT:
        fail("contract", f"baseline.sourceCommit is {baseline.get('sourceCommit')!r}, expected {SOURCE_COMMIT!r}")
    if baseline.get("status") != "released":
        fail("contract", f"baseline.status is {baseline.get('status')!r}; expected 'released'")

    columns = [c.get("id") for c in contract.get("capabilityColumns", [])]
    if columns != CAPABILITY_COLUMNS:
        fail("contract", f"capabilityColumns are {columns}, expected {CAPABILITY_COLUMNS}")
    headings = [c.get("en") for c in contract.get("capabilityColumns", [])]
    if headings != COLUMN_HEADINGS_EN:
        fail("contract", f"English column headings are {headings}, expected {COLUMN_HEADINGS_EN}")

    agents = contract.get("agents", [])
    if len(agents) != len(EXPECTED_AGENTS):
        fail("contract", f"{len(agents)} agents in the contract, expected {len(EXPECTED_AGENTS)}")
    by_id = {a.get("id"): a for a in agents}
    for agent_id, name, tag, tag_zh, states in EXPECTED_AGENTS:
        agent = by_id.get(agent_id)
        if agent is None:
            fail("contract", f"agent {agent_id!r} is missing")
            continue
        if agent.get("name") != name:
            fail("contract", f"agent {agent_id!r} is named {agent.get('name')!r}, expected {name!r}")
        got_tag = (agent.get("tag") or {}).get("en") if agent.get("tag") else None
        got_tag_zh = (agent.get("tag") or {}).get("zh") if agent.get("tag") else None
        if got_tag != tag or got_tag_zh != tag_zh:
            fail("contract", f"agent {agent_id!r} tags are {got_tag!r}/{got_tag_zh!r}, expected {tag!r}/{tag_zh!r}")
        got = [(agent.get("capabilities", {}).get(c) or {}).get("state") for c in CAPABILITY_COLUMNS]
        if got != states:
            fail("contract", f"agent {agent_id!r} states are {got}, expected {states}")

    if contract.get("platforms", {}).get("desktop", {}).get("linux", {}).get("official") is not False:
        fail("contract", "platforms.desktop.linux.official must be false — there is no Linux desktop package")
    daemon = contract.get("platforms", {}).get("daemon", {}).get("targets", [])
    if len(daemon) != 5:
        fail("contract", f"platforms.daemon lists {len(daemon)} targets, expected 5")
    if contract.get("platforms", {}).get("harmony", {}).get("status") != "preview":
        fail("contract", "platforms.harmony.status must be 'preview'")

    jobs = [j.get("en") for j in contract.get("jobs", [])]
    if jobs != ["Watch", "Approve", "Continue", "Inspect"]:
        fail("contract", f"public jobs are {jobs}, expected ['Watch', 'Approve', 'Continue', 'Inspect']")

    notes.append(f"contract: v{baseline.get('version')} @ {baseline.get('sourceCommit')}, "
                 f"{len(agents)} agents x {len(columns)} columns")
    return contract


def parse_html_matrix(html: str) -> dict[str, list[str]]:
    """Read the rendered matrix back out of site/index.html via its data-* hooks."""
    rows: dict[str, list[str]] = {}
    for row in re.finditer(r'<tr data-agent="([^"]+)"(.*?)</tr>', html, re.S):
        agent_id, body = row.group(1), row.group(2)
        if agent_id.endswith("-warn"):
            continue
        cells = re.findall(r'data-cap="([^"]+)" data-state="([^"]+)"', body)
        ordered = {cap: state for cap, state in cells}
        rows[agent_id] = [ordered.get(cap, "?") for cap in CAPABILITY_COLUMNS]
    return rows


def parse_markdown_matrix(md: str) -> dict[str, list[str]]:
    """Read the matrix back out of a README table by its leading state symbol."""
    rows: dict[str, list[str]] = {}
    for line in md.splitlines():
        if not line.startswith("|") or "✓" not in line and "✕" not in line and "△" not in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) != 5:
            continue
        label = re.sub(r"`[^`]*`", "", cells[0]).strip()
        states = []
        for cell in cells[1:]:
            symbol = cell[:1]
            states.append(SYMBOL_STATE.get(symbol, f"?{cell[:12]}"))
        rows[label] = states
    return rows


def check_matrices(sources: dict[str, str]) -> None:
    html = sources.get("site/index.html")
    if html is not None:
        rows = parse_html_matrix(html)
        if len(rows) != len(EXPECTED_AGENTS):
            fail("matrix", f"site/index.html has {len(rows)} agent rows, expected {len(EXPECTED_AGENTS)}")
        for agent_id, name, tag, tag_zh, states in EXPECTED_AGENTS:
            if rows.get(agent_id) != states:
                fail("matrix", f"site/index.html row {agent_id!r} is {rows.get(agent_id)}, expected {states}")
            if name not in html:
                fail("matrix", f"site/index.html never names {name!r}")
            for label in (tag, tag_zh):     # the page carries both languages inline
                if label and label not in html:
                    fail("matrix", f"site/index.html never labels {name!r} as {label!r}")
        for heading in COLUMN_HEADINGS_EN:
            if heading.replace("&", "&amp;") not in html and heading not in html:
                fail("matrix", f"site/index.html is missing the column heading {heading!r}")
        # DeepSeek is narrow v1, NOT Preview — the two boundaries must not be conflated.
        deepseek = re.search(r'<tr data-agent="deepseek".*?</tr>', html, re.S)
        if deepseek and "Preview" in deepseek.group(0):
            fail("matrix", "site/index.html labels DeepSeek as Preview; it is narrow v1")

    expected_names = {a[1] for a in EXPECTED_AGENTS}
    for rel, tag_index in (("README.md", 2), ("README.zh-CN.md", 3)):
        md = sources.get(rel)
        if md is None:
            continue
        rows = parse_markdown_matrix(md)
        if len(rows) != len(EXPECTED_AGENTS):
            fail("matrix", f"{rel} has {len(rows)} matrix rows, expected {len(EXPECTED_AGENTS)}: {sorted(rows)}")
        for agent in EXPECTED_AGENTS:
            name, tag, states = agent[1], agent[tag_index], agent[4]
            if rows.get(name) != states:
                fail("matrix", f"{rel} row {name!r} is {rows.get(name)}, expected {states}")
            if tag and tag not in md:
                fail("matrix", f"{rel} never labels {name!r} as {tag!r}")
        missing = expected_names - set(rows)
        if missing:
            fail("matrix", f"{rel} is missing agent rows: {sorted(missing)}")


def check_facts(sources: dict[str, str]) -> None:
    for rel in BASELINE_TARGETS:
        text = sources.get(rel)
        if text is None:
            continue
        if BASELINE_VERSION not in text:
            fail("baseline", f"{rel} never states the v{BASELINE_VERSION} baseline")
        if SOURCE_COMMIT not in text:
            fail("baseline", f"{rel} never records the source commit {SOURCE_COMMIT}")
        stale = re.findall(PREVIOUS_VERSION, text)
        if stale:
            fail("baseline", f"{rel} still carries the previous public version 1.9.0 ({len(stale)}x)")

    # OpenCode's limitation must be stated where it is claimed, not only in the contract.
    for rel in ("README.md", "site/index.html", "site/llms.txt"):
        text = sources.get(rel)
        if text is None:
            continue
        if "Full access" not in text:
            fail("opencode", f"{rel} never states that OpenCode runs at Full access")
        if not re.search(r"no enforceable interactive approval", text, re.I):
            fail("opencode", f"{rel} never states that OpenCode has no enforceable interactive approval")

    # DeepSeek approvals are bridged since v1.9.0 (#291). The honest claim to pin is the timeout
    # takeover: dsh has NO timeout of its own, an unanswered request rides the daemon's approval
    # window (expired approval -> reject, expired question -> skipped). Public copy must say so.
    for rel in ("README.md", "site/index.html", "site/llms.txt"):
        text = sources.get(rel)
        if text is not None and not re.search(r"timeout of its own|没有自带超时|自身没有超时", text, re.I):
            fail("deepseek", f"{rel} never states that DeepSeek requests ride the daemon's approval window (no timeout of its own)")

    # No official Linux desktop binary, anywhere.
    for rel, text in sources.items():
        for match in re.finditer(LINUX_DESKTOP_ARTIFACTS, text, re.I):
            line = text.count("\n", 0, match.start()) + 1
            fail("linux-desktop", f"{rel}:{line} implies an official Linux desktop binary: {match.group(0)!r}")
    for rel in ("README.md", "README.zh-CN.md", "site/index.html", "site/llms.txt"):
        text = sources.get(rel)
        if text is None:
            continue
        if not re.search(r"no official linux desktop package|Linux 没有正式桌面安装包", text, re.I):
            fail("linux-desktop", f"{rel} does not say there is no official Linux desktop package")


def check_media() -> None:
    if not MANIFEST.exists():
        fail("media", f"missing generated-asset manifest {MANIFEST.relative_to(ROOT)} "
                      f"— run: bash marketing/site/generate-assets.sh")
        return
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))

    provenance = manifest.get("provenance", {})
    for key in ("source", "data", "statement", "regenerate"):
        if not provenance.get(key):
            fail("media", f"manifest provenance is missing {key!r}")
    if provenance.get("data") != "scripted demo data":
        fail("media", f"manifest provenance.data is {provenance.get('data')!r}, expected 'scripted demo data'")
    if provenance.get("source") != "real product UI":
        fail("media", f"manifest provenance.source is {provenance.get('source')!r}, expected 'real product UI'")

    assets = manifest.get("assets", [])
    required = {
        "control-loop-en.mp4", "control-loop-zh.mp4",
        "control-loop-en-poster.jpg", "control-loop-zh-poster.jpg",
        "desktop-console.png", "overview.png",
    }
    listed = {a.get("file") for a in assets}
    for missing in sorted(required - listed):
        fail("media", f"manifest does not list the required asset {missing}")

    for asset in assets:
        name = asset.get("file")
        path = MEDIA_DIR / str(name)
        if not path.exists():
            fail("media", f"manifest lists {name}, which does not exist")
            continue
        size = path.stat().st_size
        if size == 0:
            fail("media", f"{name} is empty")
            continue
        if asset.get("bytes") != size:
            fail("media", f"{name} is {size} bytes, manifest says {asset.get('bytes')} — regenerate")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if asset.get("sha256") != digest:
            fail("media", f"{name} does not match its recorded sha256 — regenerate")
        for key in ("generator", "kind", "language", "width", "height"):
            if asset.get(key) in (None, ""):
                fail("media", f"{name} is missing manifest field {key!r}")
        if asset.get("kind") == "video":
            duration = asset.get("durationSec")
            if not isinstance(duration, (int, float)) or not 8 <= duration <= 12:
                fail("media", f"{name} duration is {duration!r}s, the published claim is 8-12s")
            if asset.get("audio") != "none":
                fail("media", f"{name} must be silent; manifest says audio={asset.get('audio')!r}")

    notes.append(f"media: {len(assets)} assets verified against {MANIFEST.relative_to(ROOT)}")


def main() -> int:
    sources: dict[str, str] = {}
    for rel in PUBLIC_TARGETS:
        text = read(rel)
        if text is not None:
            sources[rel] = text

    check_exclusions(sources)
    check_contract()
    check_matrices(sources)
    check_facts(sources)
    check_media()

    for note in notes:
        print(f"  {note}")
    if failures:
        print(f"\npublic-content check FAILED with {len(failures)} problem(s):", file=sys.stderr)
        for problem in failures:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"\npublic-content check passed: {len(sources)} targets, "
          f"{len(EXPECTED_AGENTS)} agents x {len(CAPABILITY_COLUMNS)} columns, v{BASELINE_VERSION} @ {SOURCE_COMMIT}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
