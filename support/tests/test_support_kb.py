from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
