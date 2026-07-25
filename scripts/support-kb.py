#!/usr/bin/env python3
"""Search and govern the CC Pocket support knowledge base.

The public manual is canonical. Code-backed answers are stored as candidates
with immutable evidence hashes so they can be reused provisionally, audited
against a newer checkout, reviewed by a stronger model, and promoted through a
human-reviewed manual change.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import html
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANUAL = ROOT / "site" / "manual" / "manual-content.json"
DEFAULT_KB = ROOT / "support" / "kb"
PUBLIC_MANUAL = "https://heypandax.github.io/cc-pocket/manual"
ALLOWED_EVIDENCE_ROOTS = {
    "daemon",
    "docs",
    "mobile",
    "protocol",
    "relay",
    "scripts",
    "site",
}
ALLOWED_EVIDENCE_FILES = {
    "AGENTS.md",
    "README.md",
    "README.zh-CN.md",
    "CONTRIBUTING.md",
}
REUSABLE_STATUSES = {"observed", "verified"}
REVIEW_VERDICTS = {"verified", "rejected", "needs_changes"}


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    path.write_text(rendered, encoding="utf-8")


def localized(value: Any, locale: str, fallback: str = "") -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        selected = value.get(locale) or value.get("en") or value.get("zh") or fallback
        return str(selected)
    return fallback


def strip_markup(value: str) -> str:
    without_tags = re.sub(r"<[^>]+>", " ", value)
    return re.sub(r"\s+", " ", html.unescape(without_tags)).strip()


def flatten_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield strip_markup(value)
    elif isinstance(value, list):
        for item in value:
            yield from flatten_strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from flatten_strings(item)


def flatten_localized_strings(value: Any, locale: str) -> Iterable[str]:
    if isinstance(value, dict) and locale in value and ({"en", "zh"} & set(value)):
        yield from flatten_localized_strings(value[locale], locale)
    elif isinstance(value, dict):
        for item in value.values():
            yield from flatten_localized_strings(item, locale)
    elif isinstance(value, list):
        for item in value:
            yield from flatten_localized_strings(item, locale)
    elif isinstance(value, str):
        yield strip_markup(value)


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.casefold()).strip()


def query_terms(value: str) -> list[str]:
    normalized = normalize(value)
    ascii_terms = re.findall(r"[a-z0-9][a-z0-9._+-]*", normalized)
    cjk_runs = re.findall(r"[\u3400-\u9fff]+", normalized)
    cjk_terms: list[str] = []
    for run in cjk_runs:
        cjk_terms.append(run)
        if len(run) > 1:
            cjk_terms.extend(run[index : index + 2] for index in range(len(run) - 1))
        cjk_terms.extend(run)
    return list(dict.fromkeys(ascii_terms + cjk_terms))


def score_text(query: str, fields: list[tuple[str, int]]) -> int:
    terms = query_terms(query)
    if not terms:
        return 0
    query_norm = normalize(query)
    total = 0
    for raw, weight in fields:
        text_norm = normalize(strip_markup(raw))
        if not text_norm:
            continue
        if query_norm == text_norm:
            total += weight * 8
        elif query_norm in text_norm:
            total += weight * 4
        total += sum(weight for term in terms if term in text_norm)
    return total


def manual_records(manual: dict[str, Any], locale: str) -> list[dict[str, Any]]:
    verified_at = str(manual.get("verifiedAt", "unknown"))
    records: list[dict[str, Any]] = []
    for article in manual.get("articles", []):
        slug = str(article["slug"])
        title = localized(article.get("title"), locale)
        summary = localized(article.get("summary"), locale)
        answer = localized(article.get("shortAnswer"), locale, summary)
        aliases_value = article.get("aliases", {})
        aliases = aliases_value.get(locale, []) if isinstance(aliases_value, dict) else []
        body = " ".join(flatten_localized_strings(article.get("sections", []), locale))
        records.append(
            {
                "kind": "manual",
                "id": f"manual:{slug}",
                "slug": slug,
                "status": "canonical",
                "title": strip_markup(title),
                "summary": strip_markup(summary),
                "answer": strip_markup(answer),
                "aliases": [str(item) for item in aliases],
                "body": body,
                "verifiedAt": verified_at,
                "url": f"{PUBLIC_MANUAL}/{locale}/{slug}/",
            }
        )
    return records


def promoted_candidate_ids(manual: dict[str, Any]) -> set[str]:
    promoted: set[str] = set()
    for article in manual.get("articles", []):
        if not isinstance(article, dict):
            continue
        candidate_ids = article.get("sourceCandidateIds", [])
        if not isinstance(candidate_ids, list):
            continue
        promoted.update(
            candidate_id
            for candidate_id in candidate_ids
            if isinstance(candidate_id, str) and candidate_id.startswith("kb-")
        )
    return promoted


def candidate_files(kb_dirs: list[Path]) -> Iterable[Path]:
    seen: set[Path] = set()
    for kb_dir in kb_dirs:
        candidates_dir = kb_dir / "candidates" if kb_dir.name != "candidates" else kb_dir
        if not candidates_dir.exists():
            continue
        for path in sorted(candidates_dir.glob("*.json")):
            resolved = path.resolve()
            if resolved not in seen:
                seen.add(resolved)
                yield path


def review_files(kb_dirs: list[Path]) -> Iterable[Path]:
    seen: set[Path] = set()
    for kb_dir in kb_dirs:
        reviews_dir = kb_dir / "reviews" if kb_dir.name != "reviews" else kb_dir
        if not reviews_dir.exists():
            continue
        for path in sorted(reviews_dir.glob("*.json")):
            resolved = path.resolve()
            if resolved not in seen:
                seen.add(resolved)
                yield path


def candidate_identity(candidate: dict[str, Any]) -> dict[str, Any]:
    return {
        "schemaVersion": candidate.get("schemaVersion"),
        "questions": candidate.get("questions"),
        "answer": candidate.get("answer"),
        "evidenceSummary": candidate.get("evidenceSummary", ""),
        "evidence": candidate.get("evidence"),
        "capturedAt": candidate.get("capturedAt"),
        "capturedBy": candidate.get("capturedBy"),
    }


def candidate_digest(candidate: dict[str, Any]) -> str:
    encoded = json.dumps(
        candidate_identity(candidate),
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return sha256(encoded)


def expected_candidate_id(candidate: dict[str, Any]) -> str:
    identity = {
        "schemaVersion": candidate.get("schemaVersion"),
        "questions": candidate.get("questions"),
        "answer": candidate.get("answer"),
        "evidenceSummary": candidate.get("evidenceSummary", ""),
        "evidence": candidate.get("evidence"),
    }
    encoded = json.dumps(
        identity,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return f"kb-{sha256(encoded)[:12]}"


def matching_review(candidate: dict[str, Any], kb_dirs: list[Path]) -> dict[str, Any] | None:
    matches: list[dict[str, Any]] = []
    digest = candidate_digest(candidate)
    candidate_id = str(candidate.get("id", ""))
    for path in review_files(kb_dirs):
        try:
            review = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            continue
        if review.get("id") != candidate_id or review.get("candidateSha256") != digest:
            continue
        verdict = str(review.get("verdict", ""))
        if verdict not in REVIEW_VERDICTS:
            continue
        matches.append(review)
    if not matches:
        return None
    matches.sort(key=lambda item: str(item.get("reviewedAt", "")), reverse=True)
    return matches[0]


def resolve_evidence_path(repo_root: Path, raw_path: str) -> Path:
    relative = Path(raw_path)
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise ValueError(f"unsafe evidence path: {raw_path}")
    if relative.parts[0] not in ALLOWED_EVIDENCE_ROOTS and raw_path not in ALLOWED_EVIDENCE_FILES:
        raise ValueError(f"evidence path is outside the support allowlist: {raw_path}")
    resolved_root = repo_root.resolve()
    resolved = (resolved_root / relative).resolve()
    if resolved != resolved_root and resolved_root not in resolved.parents:
        raise ValueError(f"evidence path escapes repository: {raw_path}")
    return resolved


def file_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def validate_evidence(repo_root: Path, evidence: dict[str, Any]) -> dict[str, Any]:
    raw_path = str(evidence.get("path", ""))
    try:
        path = resolve_evidence_path(repo_root, raw_path)
    except ValueError as exc:
        return {"path": raw_path, "state": "invalid", "reason": str(exc)}
    if not path.is_file():
        return {"path": raw_path, "state": "missing", "reason": "file no longer exists"}
    try:
        lines = file_lines(path)
    except UnicodeDecodeError:
        return {"path": raw_path, "state": "invalid", "reason": "file is not UTF-8 text"}
    start = int(evidence.get("startLine", 0))
    end = int(evidence.get("endLine", 0))
    if start < 1 or end < start or end > len(lines):
        return {
            "path": raw_path,
            "state": "invalid",
            "reason": f"line range {start}-{end} is outside 1-{len(lines)}",
        }
    current_file_hash = sha256(path.read_bytes())
    excerpt = "\n".join(lines[start - 1 : end]).encode("utf-8")
    current_excerpt_hash = sha256(excerpt)
    expected_file_hash = evidence.get("fileSha256")
    expected_excerpt_hash = evidence.get("excerptSha256")
    if expected_file_hash == current_file_hash and expected_excerpt_hash == current_excerpt_hash:
        state = "current"
        reason = "file and cited lines match captured evidence"
    elif expected_excerpt_hash == current_excerpt_hash:
        state = "current"
        reason = "cited lines still match; unrelated parts of the file changed"
    else:
        state = "stale"
        reason = "cited lines changed"
    return {
        "path": raw_path,
        "startLine": start,
        "endLine": end,
        "state": state,
        "reason": reason,
        "currentFileSha256": current_file_hash,
        "currentExcerptSha256": current_excerpt_hash,
    }


def candidate_validation(repo_root: Path, candidate: dict[str, Any]) -> dict[str, Any]:
    candidate_id = str(candidate.get("id", ""))
    expected_id = expected_candidate_id(candidate)
    if candidate_id != expected_id:
        return {
            "state": "invalid",
            "checkedAt": utc_now(),
            "evidence": [],
            "reason": f"candidate content digest does not match id {candidate_id!r}",
        }
    evidence = candidate.get("evidence", [])
    if not isinstance(evidence, list) or not evidence:
        return {"state": "invalid", "checkedAt": utc_now(), "evidence": [], "reason": "no evidence"}
    results = [validate_evidence(repo_root, item) for item in evidence if isinstance(item, dict)]
    states = {item["state"] for item in results}
    if "invalid" in states or not results:
        state = "invalid"
    elif "missing" in states:
        state = "missing"
    elif "stale" in states:
        state = "stale"
    else:
        state = "current"
    return {"state": state, "checkedAt": utc_now(), "evidence": results}


def candidate_records(repo_root: Path, kb_dirs: list[Path], locale: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in candidate_files(kb_dirs):
        try:
            candidate = load_json(path)
        except (OSError, ValueError, json.JSONDecodeError):
            continue
        status = str(candidate.get("status", ""))
        if status != "observed":
            continue
        validation = candidate_validation(repo_root, candidate)
        if validation["state"] != "current":
            continue
        review = matching_review(candidate, kb_dirs)
        if review and review.get("verdict") in {"rejected", "needs_changes"}:
            continue
        effective_status = "verified" if review and review.get("verdict") == "verified" else "observed"
        if effective_status not in REUSABLE_STATUSES:
            continue
        questions = candidate.get("questions", {})
        question_values = questions.get(locale, []) if isinstance(questions, dict) else []
        if isinstance(question_values, str):
            question_values = [question_values]
        answer = localized(candidate.get("answer"), locale)
        records.append(
            {
                "kind": "candidate",
                "id": str(candidate.get("id", path.stem)),
                "status": effective_status,
                "title": str(question_values[0]) if question_values else str(candidate.get("id", path.stem)),
                "summary": strip_markup(answer),
                "answer": strip_markup(answer),
                "aliases": [str(item) for item in question_values],
                "body": " ".join(flatten_strings(candidate.get("evidenceSummary", ""))),
                "verifiedAt": (review or {}).get("reviewedAt") or candidate.get("capturedAt"),
                "url": None,
                "evidence": candidate.get("evidence", []),
            }
        )
    return records


def search_records(query: str, records: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    ranked: list[dict[str, Any]] = []
    for record in records:
        score = score_text(
            query,
            [
                (record.get("title", ""), 12),
                (" ".join(record.get("aliases", [])), 9),
                (record.get("summary", ""), 5),
                (record.get("answer", ""), 4),
                (record.get("body", ""), 1),
            ],
        )
        if score:
            item = {key: value for key, value in record.items() if key not in {"body", "aliases"}}
            item["score"] = score
            ranked.append(item)
    ranked.sort(key=lambda item: (-item["score"], 0 if item["kind"] == "manual" else 1, item["id"]))
    return ranked[:limit]


def git_head(repo_root: Path) -> str:
    marker = repo_root / ".support-commit"
    if marker.is_file():
        commit = marker.read_text(encoding="utf-8").strip()
        if re.fullmatch(r"[0-9a-f]{40,64}", commit):
            return commit
        raise ValueError(f"invalid support snapshot commit marker: {marker}")
    result = subprocess.run(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def validate_capture_payload(payload: dict[str, Any]) -> None:
    questions = payload.get("questions")
    answer = payload.get("answer")
    evidence = payload.get("evidence")
    if not isinstance(questions, dict) or not any(questions.get(locale) for locale in ("zh", "en")):
        raise ValueError("questions must contain at least one zh or en question")
    if not isinstance(answer, dict) or not any(answer.get(locale) for locale in ("zh", "en")):
        raise ValueError("answer must contain at least one zh or en answer")
    if not isinstance(evidence, list) or not evidence:
        raise ValueError("evidence must contain at least one code citation")
    for item in evidence:
        if not isinstance(item, dict):
            raise ValueError("each evidence item must be an object")
        if not item.get("path") or not item.get("startLine") or not item.get("endLine"):
            raise ValueError("each evidence item needs path, startLine, and endLine")


def capture_candidate(repo_root: Path, queue_dir: Path, payload: dict[str, Any]) -> Path:
    validate_capture_payload(payload)
    commit = git_head(repo_root)
    captured_evidence: list[dict[str, Any]] = []
    for raw in payload["evidence"]:
        path = resolve_evidence_path(repo_root, str(raw["path"]))
        if not path.is_file():
            raise ValueError(f"evidence file does not exist: {raw['path']}")
        lines = file_lines(path)
        start = int(raw["startLine"])
        end = int(raw["endLine"])
        if start < 1 or end < start or end > len(lines):
            raise ValueError(f"invalid evidence range {raw['path']}:{start}-{end}")
        excerpt = "\n".join(lines[start - 1 : end]).encode("utf-8")
        captured_evidence.append(
            {
                "kind": "code",
                "path": str(raw["path"]),
                "startLine": start,
                "endLine": end,
                "commit": commit,
                "fileSha256": sha256(path.read_bytes()),
                "excerptSha256": sha256(excerpt),
                "note": str(raw.get("note", "")).strip(),
            }
        )
    now = utc_now()
    candidate = {
        "schemaVersion": 1,
        "id": "",
        "status": "observed",
        "questions": payload["questions"],
        "answer": payload["answer"],
        "evidenceSummary": payload.get("evidenceSummary", ""),
        "evidence": captured_evidence,
        "capturedAt": now,
        "capturedBy": str(payload.get("capturedBy", "cc-pocket-support")),
        "validation": {"state": "current", "checkedAt": now},
    }
    candidate_id = expected_candidate_id(candidate)
    candidate["id"] = candidate_id
    candidates_dir = queue_dir / "candidates"
    path = candidates_dir / f"{candidate_id}.json"
    if path.exists():
        existing = load_json(path)
        if existing.get("questions") == candidate["questions"] and existing.get("answer") == candidate["answer"]:
            return path
        raise ValueError(f"candidate id collision: {candidate_id}")
    write_json(path, candidate)
    return path


def build_ai_index(manual_path: Path, output: Path, llms_output: Path) -> None:
    manual = load_json(manual_path)
    verified_at = str(manual.get("verifiedAt", "unknown"))
    articles: list[dict[str, Any]] = []
    llms_lines = [
        "# CC Pocket User Manual — full AI index",
        "",
        f"Canonical source: {PUBLIC_MANUAL}/",
        f"Content verified: {verified_at}",
        "",
        "Use the matching article URL as the citation. Do not invent steps that are absent from the article.",
        "",
    ]
    for article in manual.get("articles", []):
        slug = str(article["slug"])
        locale_records: dict[str, Any] = {}
        for locale in ("en", "zh"):
            record = manual_records({"verifiedAt": verified_at, "articles": [article]}, locale)[0]
            locale_records[locale] = {
                "title": record["title"],
                "summary": record["summary"],
                "shortAnswer": record["answer"],
                "aliases": record["aliases"],
                "text": record["body"],
                "url": record["url"],
            }
            llms_lines.extend(
                [
                    f"## {record['title']} ({locale})",
                    "",
                    f"URL: {record['url']}",
                    "",
                    record["answer"],
                    "",
                    record["body"],
                    "",
                ]
            )
        articles.append({"slug": slug, "verifiedAt": verified_at, "locales": locale_records})
    index = {
        "schemaVersion": 1,
        "source": "site/manual/manual-content.json",
        "verifiedAt": verified_at,
        "canonical": f"{PUBLIC_MANUAL}/",
        "articles": articles,
    }
    write_json(output, index)
    llms_output.parent.mkdir(parents=True, exist_ok=True)
    llms_output.write_text("\n".join(llms_lines).rstrip() + "\n", encoding="utf-8")


def command_search(args: argparse.Namespace) -> int:
    manual = load_json(args.manual)
    records = manual_records(manual, args.locale)
    promoted = promoted_candidate_ids(manual)
    records.extend(
        record
        for record in candidate_records(args.repo_root, args.kb, args.locale)
        if record["id"] not in promoted
    )
    results = search_records(args.query, records, args.limit)
    if args.format == "json":
        print(json.dumps({"query": args.query, "locale": args.locale, "results": results}, ensure_ascii=False, indent=2))
    else:
        for item in results:
            source = item.get("url") or item["id"]
            print(f"[{item['kind']}:{item['status']}] score={item['score']} {item['title']}")
            print(f"  {item['answer']}")
            print(f"  {source}")
    return 0 if results else 1


def command_capture(args: argparse.Namespace) -> int:
    payload = load_json(args.input) if args.input else json.load(sys.stdin)
    if not isinstance(payload, dict):
        raise ValueError("capture payload must be a JSON object")
    path = capture_candidate(args.repo_root, args.queue, payload)
    print(json.dumps({"ok": True, "path": str(path), "candidate": path.stem}, ensure_ascii=False))
    return 0


def command_audit(args: argparse.Namespace) -> int:
    reports: list[dict[str, Any]] = []
    stale = 0
    for path in candidate_files(args.kb):
        candidate = load_json(path)
        validation = candidate_validation(args.repo_root, candidate)
        if validation["state"] != "current":
            stale += 1
        if args.write:
            candidate["validation"] = validation
            if validation["state"] in {"stale", "missing", "invalid"} and candidate.get("status") in REUSABLE_STATUSES:
                candidate["status"] = "stale"
            write_json(path, candidate)
        reports.append({"id": candidate.get("id", path.stem), "path": str(path), "validation": validation})
    print(json.dumps({"checked": len(reports), "stale": stale, "reports": reports}, ensure_ascii=False, indent=2))
    return 2 if stale else 0


def command_review(args: argparse.Namespace) -> int:
    review = load_json(args.input)
    candidate_path = args.candidate_kb / "candidates" / f"{review.get('id', '')}.json"
    if not candidate_path.is_file():
        raise ValueError(f"candidate not found: {candidate_path}")
    verdict = str(review.get("verdict", ""))
    if verdict not in REVIEW_VERDICTS:
        raise ValueError(f"verdict must be one of {sorted(REVIEW_VERDICTS)}")
    model = str(review.get("model", "")).strip()
    rationale = str(review.get("rationale", "")).strip()
    if not model or not rationale:
        raise ValueError("review needs model and rationale")
    candidate = load_json(candidate_path)
    validation = candidate_validation(args.repo_root, candidate)
    if verdict == "verified" and validation["state"] != "current":
        raise ValueError(f"cannot verify stale evidence: {validation['state']}")
    recorded_review = {
        "schemaVersion": 1,
        "id": candidate["id"],
        "candidateSha256": candidate_digest(candidate),
        "verdict": verdict,
        "model": model,
        "rationale": rationale,
        "reviewedAt": utc_now(),
        "reviewedCommit": git_head(args.repo_root),
        "validation": validation,
    }
    review_path = args.governance / "reviews" / f"{candidate['id']}.json"
    write_json(review_path, recorded_review)
    print(json.dumps({"ok": True, "id": candidate["id"], "verdict": verdict, "path": str(review_path)}, ensure_ascii=False))
    return 0


def command_promote(args: argparse.Namespace) -> int:
    candidate_path = args.candidate_kb / "candidates" / f"{args.id}.json"
    candidate = load_json(candidate_path)
    review = matching_review(candidate, [args.governance])
    if not review or review.get("verdict") != "verified":
        raise ValueError("only verified candidates can produce a manual proposal")
    validation = candidate_validation(args.repo_root, candidate)
    if validation["state"] != "current":
        raise ValueError(f"candidate evidence is {validation['state']}")
    questions = candidate.get("questions", {})
    answer = candidate.get("answer", {})
    evidence_lines = []
    for item in candidate.get("evidence", []):
        evidence_lines.append(
            f"- `{item['path']}:{item['startLine']}-{item['endLine']}` at `{item['commit']}` — {item.get('note', '')}"
        )
    content = "\n".join(
        [
            f"# Manual promotion proposal: {candidate['id']}",
            "",
            "Status: requires maintainer review and a normal pull request.",
            "",
            "## User questions",
            "",
            f"- zh: {'；'.join(questions.get('zh', []))}",
            f"- en: {'; '.join(questions.get('en', []))}",
            "",
            "## Proposed short answer",
            "",
            f"**中文：** {answer.get('zh', '')}",
            "",
            f"**English:** {answer.get('en', '')}",
            "",
            "## Code evidence",
            "",
            *evidence_lines,
            "",
            "## Reviewer",
            "",
            f"- Model: {review.get('model', '')}",
            f"- Rationale: {review.get('rationale', '')}",
            f"- Candidate SHA-256: `{review.get('candidateSha256', '')}`",
            f"- Reviewed commit: `{review.get('reviewedCommit', '')}`",
            "",
            "## Promotion checklist",
            "",
            "- [ ] Re-read every cited line on current `main`.",
            "- [ ] Add or update the bilingual article in `site/manual/manual-content.json`.",
            "- [ ] Run `python3 scripts/build-manual.py` and the site checks.",
            "- [ ] Review screenshots and user-facing wording.",
            "- [ ] Merge through the normal repository review flow.",
            "",
        ]
    )
    output = args.output or (args.governance / "promotions" / f"{candidate['id']}.md")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(content, encoding="utf-8")
    print(json.dumps({"ok": True, "path": str(output)}, ensure_ascii=False))
    return 0


def command_build(args: argparse.Namespace) -> int:
    build_ai_index(args.manual, args.output, args.llms_output)
    print(json.dumps({"ok": True, "index": str(args.output), "llms": str(args.llms_output)}, ensure_ascii=False))
    return 0


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    subparsers = root.add_subparsers(dest="command", required=True)

    search = subparsers.add_parser("search", help="search canonical manual and current reusable candidates")
    search.add_argument("query")
    search.add_argument("--locale", choices=("zh", "en"), default="zh")
    search.add_argument("--manual", type=Path, default=DEFAULT_MANUAL)
    search.add_argument("--repo-root", type=Path, default=ROOT)
    search.add_argument("--kb", type=Path, action="append", default=[])
    search.add_argument("--limit", type=int, default=5)
    search.add_argument("--format", choices=("json", "text"), default="json")
    search.set_defaults(func=command_search)

    capture = subparsers.add_parser("capture", help="capture a code-backed candidate from JSON")
    capture.add_argument("--input", type=Path)
    capture.add_argument("--repo-root", type=Path, default=ROOT)
    capture.add_argument("--queue", type=Path, default=DEFAULT_KB)
    capture.set_defaults(func=command_capture)

    audit = subparsers.add_parser("audit", help="check whether candidate code evidence is still current")
    audit.add_argument("--repo-root", type=Path, default=ROOT)
    audit.add_argument("--kb", type=Path, action="append", default=[DEFAULT_KB])
    audit.add_argument("--write", action="store_true")
    audit.set_defaults(func=command_audit)

    review = subparsers.add_parser("review", help="record an external model review bound to candidate content")
    review.add_argument("--input", type=Path, required=True)
    review.add_argument("--repo-root", type=Path, default=ROOT)
    review.add_argument("--candidate-kb", type=Path, default=DEFAULT_KB)
    review.add_argument("--governance", type=Path, default=DEFAULT_KB)
    review.set_defaults(func=command_review)

    promote = subparsers.add_parser("promote", help="generate a human-reviewed manual promotion proposal")
    promote.add_argument("id")
    promote.add_argument("--repo-root", type=Path, default=ROOT)
    promote.add_argument("--candidate-kb", type=Path, default=DEFAULT_KB)
    promote.add_argument("--governance", type=Path, default=DEFAULT_KB)
    promote.add_argument("--output", type=Path)
    promote.set_defaults(func=command_promote)

    build = subparsers.add_parser("build-index", help="build the public AI index from the manual")
    build.add_argument("--manual", type=Path, default=DEFAULT_MANUAL)
    build.add_argument("--output", type=Path, default=ROOT / "site" / "manual" / "ai-index.json")
    build.add_argument("--llms-output", type=Path, default=ROOT / "site" / "manual" / "llms-full.txt")
    build.set_defaults(func=command_build)
    return root


def main() -> int:
    args = parser().parse_args()
    if getattr(args, "kb", None) == []:
        args.kb = [DEFAULT_KB]
    try:
        return int(args.func(args))
    except (ValueError, OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
