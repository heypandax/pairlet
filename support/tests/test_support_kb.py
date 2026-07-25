from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("support_kb", ROOT / "scripts" / "support-kb.py")
assert SPEC and SPEC.loader
support_kb = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(support_kb)


class SupportKnowledgeBaseTest(unittest.TestCase):
    def test_manual_search_prefers_canonical_article(self) -> None:
        manual = support_kb.load_json(ROOT / "site" / "manual" / "manual-content.json")
        records = support_kb.manual_records(manual, "zh")
        results = support_kb.search_records("怎么预约定时发送提示词", records, 3)

        self.assertTrue(results)
        self.assertEqual("manual:schedule-a-prompt", results[0]["id"])
        self.assertEqual("canonical", results[0]["status"])

    def test_build_ai_index_contains_bilingual_stable_urls(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ai-index.json"
            llms = Path(directory) / "llms-full.txt"
            support_kb.build_ai_index(
                ROOT / "site" / "manual" / "manual-content.json",
                output,
                llms,
            )
            index = json.loads(output.read_text(encoding="utf-8"))
            article = next(item for item in index["articles"] if item["slug"] == "install-and-pair")

            self.assertEqual(1, index["schemaVersion"])
            self.assertEqual(
                "https://heypandax.github.io/cc-pocket/manual/zh/install-and-pair/",
                article["locales"]["zh"]["url"],
            )
            self.assertIn("CC Pocket User Manual", llms.read_text(encoding="utf-8"))

    def test_promoted_candidate_is_retired_from_search(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            queue = Path(directory) / "queue"
            evidence_file = repo / "docs" / "fact.md"
            evidence_file.parent.mkdir(parents=True)
            evidence_file.write_text("The answer is canonical now.\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "support@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Support Test"], check=True)
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
            candidate_path = support_kb.capture_candidate(
                repo,
                queue,
                {
                    "questions": {"zh": ["正式答案是什么？"]},
                    "answer": {"zh": "这是已经写入手册的答案。"},
                    "evidence": [
                        {
                            "path": "docs/fact.md",
                            "startLine": 1,
                            "endLine": 1,
                            "note": "Maintained documentation",
                        }
                    ],
                },
            )
            candidate = support_kb.load_json(candidate_path)
            manual = {
                "verifiedAt": "2026-07-25",
                "articles": [
                    {
                        "slug": "canonical-answer",
                        "sourceCandidateIds": [candidate["id"]],
                        "title": {"zh": "正式答案"},
                        "summary": {"zh": "这是已经写入手册的答案。"},
                        "shortAnswer": {"zh": "这是已经写入手册的答案。"},
                        "aliases": {"zh": ["正式答案是什么"]},
                        "sections": [],
                    }
                ],
            }

            records = support_kb.manual_records(manual, "zh")
            promoted = support_kb.promoted_candidate_ids(manual)
            records.extend(
                record
                for record in support_kb.candidate_records(repo, [queue], "zh")
                if record["id"] not in promoted
            )
            results = support_kb.search_records("正式答案是什么", records, 5)

            self.assertEqual(["manual:canonical-answer"], [item["id"] for item in results])

    def test_code_candidate_is_reused_only_while_evidence_is_current(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            queue = Path(directory) / "queue"
            evidence_file = repo / "scripts" / "demo.py"
            evidence_file.parent.mkdir(parents=True)
            evidence_file.write_text("PAIR_CODE_LENGTH = 6\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "support@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Support Test"], check=True)
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)

            payload = {
                "questions": {
                    "zh": ["配对码有几位？"],
                    "en": ["How many digits are in a pairing code?"],
                },
                "answer": {
                    "zh": "配对码是 6 位数字。",
                    "en": "The pairing code contains 6 digits.",
                },
                "evidence": [
                    {
                        "path": "scripts/demo.py",
                        "startLine": 1,
                        "endLine": 1,
                        "note": "The protocol fixture defines the length.",
                    }
                ],
            }
            path = support_kb.capture_candidate(repo, queue, payload)
            candidate = support_kb.load_json(path)
            self.assertEqual("current", support_kb.candidate_validation(repo, candidate)["state"])
            self.assertEqual(1, len(support_kb.candidate_records(repo, [queue], "zh")))

            evidence_file.write_text("PAIR_CODE_LENGTH = 8\n", encoding="utf-8")
            self.assertEqual("stale", support_kb.candidate_validation(repo, candidate)["state"])
            self.assertEqual([], support_kb.candidate_records(repo, [queue], "zh"))

    def test_external_review_is_digest_bound_and_required_for_verified_status(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            queue = Path(directory) / "queue"
            governance = Path(directory) / "governance"
            evidence_file = repo / "protocol" / "Pairing.kt"
            evidence_file.parent.mkdir(parents=True)
            evidence_file.write_text("const val PAIR_CODE_LENGTH = 6\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "support@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Support Test"], check=True)
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)

            candidate_path = support_kb.capture_candidate(
                repo,
                queue,
                {
                    "questions": {"zh": ["配对码有几位？"], "en": ["How long is the pairing code?"]},
                    "answer": {"zh": "配对码是 6 位。", "en": "The pairing code has 6 digits."},
                    "evidenceSummary": "The protocol constant defines the length.",
                    "evidence": [
                        {
                            "path": "protocol/Pairing.kt",
                            "startLine": 1,
                            "endLine": 1,
                            "note": "Runtime protocol constant",
                        }
                    ],
                },
            )
            candidate = support_kb.load_json(candidate_path)
            observed = support_kb.candidate_records(repo, [queue, governance], "zh")
            self.assertEqual("observed", observed[0]["status"])
            audit_output = StringIO()
            with redirect_stdout(audit_output):
                support_kb.command_audit(
                    SimpleNamespace(repo_root=repo, kb=[queue], governance=[governance], write=False)
                )
            self.assertEqual(1, json.loads(audit_output.getvalue())["needsReview"])

            review_input = governance / "review-input" / f"{candidate['id']}.json"
            support_kb.write_json(
                review_input,
                {
                    "id": candidate["id"],
                    "verdict": "verified",
                    "model": "provider/strong-model",
                    "rationale": "The runtime constant directly supports the answer.",
                },
            )
            result = support_kb.command_review(
                SimpleNamespace(
                    input=review_input,
                    repo_root=repo,
                    candidate_kb=queue,
                    governance=governance,
                )
            )
            self.assertEqual(0, result)
            verified = support_kb.candidate_records(repo, [queue, governance], "zh")
            self.assertEqual("verified", verified[0]["status"])
            audit_output = StringIO()
            with redirect_stdout(audit_output):
                support_kb.command_audit(
                    SimpleNamespace(repo_root=repo, kb=[queue], governance=[governance], write=False)
                )
            self.assertEqual(0, json.loads(audit_output.getvalue())["needsReview"])

            tampered = support_kb.load_json(candidate_path)
            tampered["answer"]["zh"] = "配对码是 8 位。"
            support_kb.write_json(candidate_path, tampered)
            self.assertEqual([], support_kb.candidate_records(repo, [queue, governance], "zh"))

    def test_promotion_requires_matching_external_verified_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory) / "repo"
            queue = Path(directory) / "queue"
            governance = Path(directory) / "governance"
            evidence_file = repo / "docs" / "fact.md"
            evidence_file.parent.mkdir(parents=True)
            evidence_file.write_text("The feature is available on desktop.\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "support@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Support Test"], check=True)
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-qm", "fixture"], check=True)
            candidate_path = support_kb.capture_candidate(
                repo,
                queue,
                {
                    "questions": {"zh": ["在哪个平台可用？"], "en": ["Which platform supports it?"]},
                    "answer": {"zh": "目前支持桌面端。", "en": "It is currently available on desktop."},
                    "evidence": [
                        {
                            "path": "docs/fact.md",
                            "startLine": 1,
                            "endLine": 1,
                            "note": "Maintained documentation",
                        }
                    ],
                },
            )
            candidate = support_kb.load_json(candidate_path)
            args = SimpleNamespace(
                id=candidate["id"],
                repo_root=repo,
                candidate_kb=queue,
                governance=governance,
                output=None,
            )
            with self.assertRaisesRegex(ValueError, "only verified"):
                support_kb.command_promote(args)

            review_input = governance / "review-input" / f"{candidate['id']}.json"
            support_kb.write_json(
                review_input,
                {
                    "id": candidate["id"],
                    "verdict": "verified",
                    "model": "provider/strong-model",
                    "rationale": "The maintained documentation supports the platform scope.",
                },
            )
            support_kb.command_review(
                SimpleNamespace(
                    input=review_input,
                    repo_root=repo,
                    candidate_kb=queue,
                    governance=governance,
                )
            )
            self.assertEqual(0, support_kb.command_promote(args))
            proposal = governance / "promotions" / f"{candidate['id']}.md"
            self.assertIn("Candidate SHA-256", proposal.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
