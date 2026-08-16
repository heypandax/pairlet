#!/usr/bin/env python3
"""Gate for the archived Claude Design prototype.

Exits non-zero unless every contract below holds:
  * excluded-feature patterns (and indirect substitutes) are zero
  * capability matrix is exactly 4 promoted columns x 6 agents
  * public jobs AND control-loop nodes are Watch/Approve/Continue/Inspect
  * Kimi Code and ZCode Usage are Yes (new in v1.8.0)
  * DeepSeek: Core=Yes, Approval=Limited, Changes=No, Usage=No
  * public baseline is v1.8.0 (no v1.7.7, no fabricated release date)
  * export is self-contained (no external resources, no editor instrumentation)

Usage: python3 verify.py   ->  exit 0 all green, exit 1 otherwise
"""
import re
import sys

PATH = "Site 2.0 - Agent Control Plane v1.dc.html"
s = open(PATH, encoding="utf-8").read()
print("file: %s | bytes: %d" % (PATH, len(s.encode())))

# ---------------------------------------------------------------- 1. exclusion
print("\n=== 1. EXCLUDED FEATURES + INDIRECT SUBSTITUTES (case-insensitive) ===")
EXCLUDE = {
    "session handoff": r"session\s*handoff",
    "handoff (any)": r"handoff",
    "folder share": r"folder\s*share",
    "hui-hua-jie-li": r"会话接力",
    "wenjianjia-gongxiang": r"文件夹共享",
    "jieli": r"接力",
    "collaborat*": r"collaborat",
    "xiezuo": r"协作",
    "guest": r"guest",
}
banned_total = 0
for name, pat in EXCLUDE.items():
    hits = list(re.finditer(pat, s, re.I))
    banned_total += len(hits)
    print("  %-22s %d" % (name, len(hits)))
    for m in hits[:3]:
        print("      ctx:", s[max(0, m.start() - 90):m.start() + 70].replace("\n", " ")[-160:])
print("  TOTAL:", banned_total)

# ------------------------------------------------------------------ 2. columns
print("\n=== 2. MATRIX COLUMNS — exactly 4 ===")
mc = re.search(r"const cols = zh\s*\?\s*(\[[^\]]*\])\s*:\s*(\[[^\]]*\])", s)
zh_cols = re.findall(r"'([^']+)'", mc.group(1)) if mc else []
en_cols = re.findall(r"'([^']+)'", mc.group(2)) if mc else []
print("  zh:", zh_cols)
print("  en:", en_cols)
EXPECT_EN = ["Core session", "Approval & mode", "Changes & diff", "Usage"]
cols_ok = en_cols == EXPECT_EN and len(zh_cols) == 4

# --------------------------------------------------------------------- 3. rows
print("\n=== 3. MATRIX ROWS — exactly 6 agents ===")
ri = s.find("const rows = [")
block = s[ri:s.find("const fg =", ri)] if ri > 0 else ""
# split per row on the agent key
raw_rows = re.split(r"\{\s*agent:\s*'", block)[1:]
rows = {}
order = []
for chunk in raw_rows:
    name = chunk.split("'", 1)[0]
    order.append(name)
    cm = re.search(r"cells:\s*\[(.*?)\]\s*\}", chunk, re.S)
    body = cm.group(1) if cm else ""
    # normalise each of the 4 cells to yes/no/lim
    cells, depth, cur = [], 0, ""
    for ch in body:
        if ch in "{[":
            depth += 1
        elif ch in "}]":
            depth -= 1
        if ch == "," and depth == 0:
            cells.append(cur.strip()); cur = ""
        else:
            cur += ch
    if cur.strip():
        cells.append(cur.strip())
    norm = []
    for c in cells:
        if re.search(r"v:\s*'lim'", c):
            norm.append("lim")
        elif re.search(r"v:\s*'yes'", c) or c.strip() == "Y":
            norm.append("yes")
        elif re.search(r"v:\s*'no'", c) or c.strip() == "N":
            norm.append("no")
        else:
            norm.append("?" + c[:18])
    rows[name] = norm
    print("  %-18s %s" % (name, norm))
print("  row count:", len(order))

EXPECT_ROWS = {
    "Claude Code":      ["yes", "yes", "yes", "yes"],
    "OpenAI Codex":     ["yes", "yes", "yes", "yes"],
    "OpenCode":         ["yes", "no",  "no",  "yes"],
    "Kimi Code":        ["yes", "yes", "no",  "yes"],   # Usage new in v1.8.0
    "ZCode":            ["yes", "yes", "no",  "yes"],   # Usage new in v1.8.0
    "DeepSeek Harness": ["yes", "lim", "no",  "no"],
}
rows_ok = len(order) == 6 and all(rows.get(k) == v for k, v in EXPECT_ROWS.items())
for k, v in EXPECT_ROWS.items():
    got = rows.get(k)
    if got != v:
        print("  MISMATCH %-18s expected %s got %s" % (k, v, got))

kimi_usage_ok = rows.get("Kimi Code", [None] * 4)[3] == "yes"
zcode_usage_ok = rows.get("ZCode", [None] * 4)[3] == "yes"
ds = rows.get("DeepSeek Harness", [None] * 4)
deepseek_ok = ds == ["yes", "lim", "no", "no"]

# ------------------------------------------------------- 4. limited state text
print("\n=== 4. ACCESSIBLE THREE-STATE LABELS (symbol + text) ===")
lm = re.search(r"const L = \{(.*?)\}", s, re.S)
lab = lm.group(1) if lm else ""
has_lim_state = bool(re.search(r"lim:\s*zh\s*\?\s*'△[^']*'\s*:\s*'△\s*Limited'", lab))
print("  ", " ".join(lab.split())[:190])
print("   △ Limited state present:", has_lim_state)

# --------------------------------------------------------------- 5. jobs, loop
print("\n=== 5. FOUR PUBLIC JOBS + LOOP NODES ===")
ji = s.find("const jobs = (zh")
jobs = re.findall(r"\['0(\d)', '([^']+)'", s[ji:ji + 3000]) if ji > 0 else []
job_verbs = [lab2.split(" —")[0].strip() for _, lab2 in jobs][:4]
print("  jobs:", job_verbs)
km = re.search(r"nodeDefs\s*=\s*zh", s)
nodes = re.findall(r"\['([A-Za-z]+)',", s[km.start():km.start() + 1400])[:4] if km else []
print("  loop:", nodes)
WACI = ["Watch", "Approve", "Continue", "Inspect"]
jobs_ok = job_verbs == WACI
loop_ok = nodes == WACI

# ------------------------------------------------------------- 6. version base
print("\n=== 6. VERSION BASELINE ===")
n177 = len(re.findall(r"1\.7\.7", s))
n180 = len(re.findall(r"1\.8\.0", s))
fake_date = len(re.findall(r"2026-08-13", s))
print("  v1.7.7 occurrences:", n177, "(must be 0)")
print("  v1.8.0 occurrences:", n180, "(must be > 0)")
print("  2026-08-13 (v1.7.7 date reused):", fake_date, "(must be 0)")
version_ok = n177 == 0 and n180 > 0 and fake_date == 0

# --------------------------------------------------------- 7. self-containment
print("\n=== 7. SELF-CONTAINED EXPORT ===")
ext = re.findall(r'(?:src|href)="https?://[^"]+', s)
instr = re.findall(r"data-om-id|omelette-injected", s)
print("  external src/href:", len(ext), ext[:4])
print("  editor instrumentation:", len(instr))
print("  <button>:", s.count("<button"), "| <table>:", s.count("<table"), "| <ol>:", s.count("<ol"))
self_contained = len(ext) == 0 and len(instr) == 0

# ------------------------------------------------------------- 8. preserved
print("\n=== 8. PRESERVED FACTS (informational) ===")
for k in ["Full access", "HarmonyOS", "Build from source", "No official package",
          "GENERATED REAL-UI ASSET SLOT", "ShowcaseRender", "DesktopScreenshotTest",
          "Preview", "narrow v1", "6162816a", "Escape"]:
    print("  %-30s %d" % (k, len(re.findall(re.escape(k), s, re.I))))

# ------------------------------------------------------------------- verdict
print("\n=== VERDICT ===")
checks = {
    "exclusion-zero": banned_total == 0,
    "matrix-4-columns": cols_ok,
    "matrix-6-agents": rows_ok,
    "kimi-usage-yes": kimi_usage_ok,
    "zcode-usage-yes": zcode_usage_ok,
    "deepseek-yes-lim-no-no": deepseek_ok,
    "limited-state-accessible": has_lim_state,
    "jobs-watch-approve-continue-inspect": jobs_ok,
    "loop-watch-approve-continue-inspect": loop_ok,
    "baseline-v1.8.0": version_ok,
    "self-contained": self_contained,
}
for k, v in checks.items():
    print("  %-38s %s" % (k, "PASS" if v else "FAIL"))
sys.exit(0 if all(checks.values()) else 1)
