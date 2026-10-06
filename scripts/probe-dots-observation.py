#!/usr/bin/env python3
"""Read-only probe of the local data sources behind Dot / Codex session observation.

Answers the P0 questions of docs/design/DOTS-SESSION-OBSERVABILITY.md without launching anything:

  * which Codex home / rollout tree / SQLite state this machine has, and which CLI + ChatGPT.app versions;
  * which rollout header fields exist (originator / source / thread_source / parent_thread_id ...), so a Dot
    attribution claim can be checked against real field names instead of guessed;
  * which native lifecycle events each rollout carries (task_started / task_complete / turn_aborted /
    request_user_input ...), i.e. the evidence vocabulary the daemon's progress reducer may rely on;
  * whether any local record is attributable to a Dot at all.  There is NO documented Dot field in a rollout;
    the probe prints "unverified" rather than inferring one from titles, cwd or timing.

Default output is field names, counts and redacted categories only: no prompt text, no account ids, no tokens,
no browser data.  `--fixture` writes a REDACTED copy of one rollout (text replaced by length placeholders,
ids kept) for tests; `--show-paths` prints cwd paths instead of their hashes.

Usage:
  python3 scripts/probe-dots-observation.py                 # summary of $CODEX_HOME (default ~/.codex)
  python3 scripts/probe-dots-observation.py --session <id>  # one thread: per-turn lifecycle evidence
  python3 scripts/probe-dots-observation.py --fixture <id> --out daemon/src/test/resources/codex/x.jsonl
  python3 scripts/probe-dots-observation.py --json           # machine-readable summary
"""
from __future__ import annotations

import argparse
import collections
import glob
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

LIFECYCLE_EVENTS = (
    "task_started", "task_complete", "turn_aborted", "request_user_input", "exec_approval_request",
    "apply_patch_approval_request", "error", "stream_error", "turn_diff", "item_started", "item_completed",
)
HEADER_FIELDS_OF_INTEREST = (
    "id", "session_id", "cwd", "runtime_workspace_roots", "originator", "source", "thread_source", "cli_version",
    "parent_thread_id", "agent_nickname", "agent_path", "agent_role", "forked_from_id", "creator_user_id",
    "creator_account_id", "history_mode", "git",
)
REDACT_KEYS = {"text", "content", "input", "output", "arguments", "stdout", "stderr", "aggregated_output",
               "formatted_output", "summary_text", "raw_content", "last_agent_message", "instructions",
               "base_instructions", "query", "results", "changes", "title", "first_user_message", "preview"}
ID_KEYS = {"creator_user_id", "creator_account_id", "account_id", "user_id", "auth", "token", "api_key"}


def codex_home() -> Path:
    return Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex")


def h(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()[:12]


def run(cmd: list[str]) -> str:
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        return ""


def environment() -> dict:
    env = {"codexHome": str(codex_home()), "codexCli": run(["codex", "--version"]) or "not on PATH"}
    plist = Path("/Applications/ChatGPT.app/Contents/Info.plist")
    if plist.exists():
        env["chatgptApp"] = run(["defaults", "read", str(plist), "CFBundleShortVersionString"]) or "unknown"
    ps = run(["ps", "-axo", "pid=,command="])
    procs = collections.Counter()
    for line in ps.splitlines():
        if "codex" not in line.lower():
            continue
        m = re.search(r"codex(?:\.app/Contents/MacOS/codex)?\s+(app-server|exec-server[^\s]*(?:\s--remote)?|exec|mcp)", line)
        if m:
            procs[m.group(1).strip()] += 1
        elif "ChatGPT.app" in line and "codex" in line.lower():
            procs["chatgpt-embedded-codex"] += 1
    env["codexProcesses"] = dict(procs)
    return env


def read_header(path: Path) -> dict | None:
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            first = fh.readline()
        obj = json.loads(first)
    except Exception:
        return None
    if obj.get("type") != "session_meta":
        return None
    return obj.get("payload") or {}


def summarize_rollouts(root: Path, show_paths: bool) -> dict:
    files = sorted(glob.glob(str(root / "sessions" / "**" / "*.jsonl"), recursive=True))
    header_keys = collections.Counter()
    origin = collections.Counter()
    sources = collections.Counter()
    cwds = collections.Counter()
    bad = 0
    for f in files:
        hdr = read_header(Path(f))
        if hdr is None:
            bad += 1
            continue
        for k in hdr:
            header_keys[k] += 1
        src = hdr.get("source")
        if isinstance(src, dict):
            src = "subagent:" + next(iter(src.get("subagent", {"?": 0}).keys()), "?")
        origin[f"originator={hdr.get('originator')!r} source={src!r} thread_source={hdr.get('thread_source')!r}"] += 1
        sources[str(hdr.get("thread_source"))] += 1
        cwd = hdr.get("cwd") or ""
        cwds[cwd if show_paths else ("cwd#" + h(cwd))] += 1
    return {
        "rolloutFiles": len(files),
        "headerlessFiles": bad,
        "headerFields": {k: header_keys.get(k, 0) for k in HEADER_FIELDS_OF_INTEREST},
        "otherHeaderFields": sorted(k for k in header_keys if k not in HEADER_FIELDS_OF_INTEREST),
        "originatorSourceCombos": dict(origin.most_common()),
        "cwds": dict(cwds.most_common(20)),
    }


def summarize_state_db(root: Path) -> dict:
    db = root / "state_5.sqlite"
    if not db.exists():
        return {"present": False}
    tmp = Path(tempfile.mkdtemp(prefix="probe-dots-"))
    try:
        for suffix in ("", "-wal", "-shm"):
            src = Path(str(db) + suffix)
            if src.exists():
                shutil.copy2(src, tmp / src.name)
        con = sqlite3.connect(tmp / db.name)
        cols = [r[1] for r in con.execute("pragma table_info(threads)")]
        combos = con.execute(
            "select count(*), originator, thread_source, substr(source,1,24) from threads group by 2,3,4 order by 1 desc"
        ).fetchall()
        tables = [r[0] for r in con.execute("select name from sqlite_master where type='table'")]
        enroll = con.execute("select count(*) from remote_control_enrollments").fetchone()[0] if "remote_control_enrollments" in tables else None
        edges = con.execute("select count(*) from thread_spawn_edges").fetchone()[0] if "thread_spawn_edges" in tables else None
        con.close()
        return {
            "present": True,
            "tables": tables,
            "threadColumns": cols,
            "dotColumnsPresent": [c for c in cols if "dot" in c.lower()],
            "originatorCombos": [{"count": c, "originator": o, "threadSource": t, "source": s} for c, o, t, s in combos],
            "remoteControlEnrollments": enroll,
            "threadSpawnEdges": edges,
        }
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def find_rollouts(root: Path, session_id: str) -> list[Path]:
    out = []
    for f in glob.glob(str(root / "sessions" / "**" / "*.jsonl"), recursive=True):
        name = Path(f).name
        if name.endswith(f"-{session_id}.jsonl") or (f"-{session_id}_" in name and name.endswith(".jsonl")):
            out.append(Path(f))
    return sorted(out, key=lambda p: p.stat().st_mtime)


def lifecycle(path: Path) -> dict:
    counts = collections.Counter()
    turns: dict[str, dict] = collections.OrderedDict()
    malformed = 0
    with open(path, encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            raw = raw.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw)
            except Exception:
                malformed += 1
                continue
            t = obj.get("type")
            p = obj.get("payload") or {}
            pt = p.get("type")
            if t == "event_msg":
                counts[f"event_msg/{pt}"] += 1
                tid = p.get("turn_id")
                if pt == "task_started" and tid:
                    turns.setdefault(tid, {})["started"] = obj.get("timestamp")
                elif pt in ("task_complete", "turn_aborted") and tid:
                    turns.setdefault(tid, {})["ended"] = pt + (f":{p.get('reason')}" if p.get("reason") else "")
                elif pt == "item_completed":
                    item = p.get("item") or {}
                    counts[f"item_completed/{item.get('type')}"] += 1
                    if tid:
                        turns.setdefault(tid, {}).setdefault("items", collections.Counter())[item.get("type")] += 1
                elif pt in ("request_user_input", "exec_approval_request", "apply_patch_approval_request"):
                    if tid:
                        turns.setdefault(tid, {}).setdefault("asks", []).append(pt)
            elif t == "response_item":
                counts[f"response_item/{pt}" + (f"/{p.get('role')}" if p.get("role") else "")] += 1
            else:
                counts[str(t)] += 1
    for tdata in turns.values():
        if "items" in tdata:
            tdata["items"] = dict(tdata["items"])
        tdata["verdict"] = (
            "ended:" + tdata["ended"] if "ended" in tdata else
            ("waiting_input (unresolved ask)" if tdata.get("asks") else "running (no terminal event)")
        )
    return {
        "file": path.name,
        "bytes": path.stat().st_size,
        "malformedLines": malformed,
        "recordCounts": dict(sorted(counts.items())),
        "lifecycleEventsPresent": [e for e in LIFECYCLE_EVENTS if counts.get(f"event_msg/{e}")],
        "turns": turns,
    }


def redact(value, key: str | None = None):
    if isinstance(value, dict):
        # dict KEYS can be paths too (a FileChange's `changes` is keyed by absolute file path)
        return {("<path>" if ("/" in k and len(k) > 1) else k): redact(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, key) for v in value]
    if isinstance(value, str):
        if key in ID_KEYS:
            return "<redacted>"
        if key in REDACT_KEYS or len(value) > 120:
            return f"<text:{len(value)}>"
        if "/Users/" in value or value.startswith("~") or value.startswith("file://"):
            return "<path>"
    return value


def write_fixture(path: Path, out: Path, cwd_replacement: str) -> int:
    n = 0
    with open(path, encoding="utf-8", errors="replace") as src, open(out, "w", encoding="utf-8") as dst:
        for raw in src:
            raw = raw.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw)
            except Exception:
                dst.write(raw + "\n")  # keep a malformed line malformed: the parser must survive it
                n += 1
                continue
            obj = redact(obj)
            p = obj.get("payload")
            if isinstance(p, dict):
                for k in ("cwd",):
                    if k in p:
                        p[k] = cwd_replacement
                if "runtime_workspace_roots" in p:
                    p["runtime_workspace_roots"] = [cwd_replacement]
                if "workspace_roots" in p:
                    p["workspace_roots"] = [cwd_replacement]
                if obj.get("type") == "session_meta":
                    for k in ("base_instructions", "git"):
                        p.pop(k, None)
            dst.write(json.dumps(obj, ensure_ascii=False) + "\n")
            n += 1
    return n


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--codex-home", type=Path, default=None)
    ap.add_argument("--session", help="one logical thread id: per-turn lifecycle evidence")
    ap.add_argument("--fixture", help="thread id whose newest rollout is written REDACTED to --out")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--fixture-cwd", default="/tmp/pairlet-fixture-project")
    ap.add_argument("--show-paths", action="store_true", help="print cwd paths instead of hashes")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    root = args.codex_home or codex_home()

    if args.fixture:
        files = find_rollouts(root, args.fixture)
        if not files:
            print(f"no rollout for {args.fixture} under {root}", file=sys.stderr)
            return 2
        if not args.out:
            print("--fixture needs --out", file=sys.stderr)
            return 2
        n = write_fixture(files[-1], args.out, args.fixture_cwd)
        print(f"wrote {n} redacted lines from {files[-1].name} to {args.out}")
        return 0

    report = {"environment": environment()}
    if args.session:
        files = find_rollouts(root, args.session)
        report["session"] = {
            "id": args.session,
            "rolloutFiles": [f.name for f in files],
            "resumeFileSplit": len(files) > 1,
            "lifecycle": [lifecycle(f) for f in files],
        }
    else:
        report["rollouts"] = summarize_rollouts(root, args.show_paths)
        report["stateDb"] = summarize_state_db(root)
    report["dotAttribution"] = {
        "documentedDotField": "none found (rollout session_meta / state_5.threads carry no Dot marker)",
        "verdict": "unverified — a Dot parent task can only be user-assigned in Pairlet metadata",
    }
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    env = report["environment"]
    print(f"codex home      : {env['codexHome']}")
    print(f"codex cli       : {env['codexCli']}")
    if "chatgptApp" in env:
        print(f"ChatGPT.app     : {env['chatgptApp']}")
    print(f"codex processes : {env['codexProcesses'] or 'none'}")
    if "session" in report:
        s = report["session"]
        print(f"\nthread {s['id']}: {len(s['rolloutFiles'])} rollout file(s){' (resume split)' if s['resumeFileSplit'] else ''}")
        for lc in s["lifecycle"]:
            print(f"  {lc['file']} ({lc['bytes']} B, malformed={lc['malformedLines']})")
            print(f"    lifecycle events: {', '.join(lc['lifecycleEventsPresent']) or 'none'}")
            for tid, t in lc["turns"].items():
                print(f"    turn {tid[:8]}…: {t['verdict']}  items={t.get('items', {})}")
    else:
        r = report["rollouts"]
        print(f"\nrollouts        : {r['rolloutFiles']} files ({r['headerlessFiles']} without session_meta)")
        print("header fields   : " + ", ".join(f"{k}={v}" for k, v in r["headerFields"].items()))
        if r["otherHeaderFields"]:
            print("other fields    : " + ", ".join(r["otherHeaderFields"]))
        print("origin combos   :")
        for combo, n in r["originatorSourceCombos"].items():
            print(f"  {n:4d}  {combo}")
        db = report["stateDb"]
        if db.get("present"):
            print(f"state_5.sqlite  : tables={len(db['tables'])} remoteControlEnrollments={db['remoteControlEnrollments']} spawnEdges={db['threadSpawnEdges']}")
            print(f"  dot columns   : {db['dotColumnsPresent'] or 'none'}")
        else:
            print("state_5.sqlite  : absent")
    print(f"\nDot attribution : {report['dotAttribution']['verdict']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
