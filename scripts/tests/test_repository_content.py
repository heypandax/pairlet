"""Exercise the guard against actual staged Git trees, without the user's index."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest


CHECK = Path(__file__).resolve().parents[1] / "check-repository-content.py"


class RepositoryContentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Do not inherit an alternate index or repository from a caller's staging check.
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
        self.git("init", "-q")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, env=self.env, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def stage(self, name, data="fixture\n"):
        target = self.root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(data)
        self.git("add", "-f", "--", name)

    def check(self):
        return subprocess.run([sys.executable, str(CHECK)], cwd=self.root, env=self.env,
                              capture_output=True, text=True)

    def test_forced_ignored_file_is_rejected_and_untracking_preserves_local_copy(self):
        self.stage(".gitignore", "scratch/\n")
        self.stage("scratch/note.md")
        self.assertIn("tracked despite", self.check().stderr)
        self.git("rm", "--cached", "--", "scratch/note.md")
        self.assertTrue((self.root / "scratch/note.md").exists())
        self.assertEqual(self.check().returncode, 0)

    def test_private_export_is_rejected_even_without_ignore_rule(self):
        self.stage("docs/design/claude-design-handoff/chats/chat1.md")
        self.stage("marketing/video/tools/probe.py")
        result = self.check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("raw design export", result.stderr)
        self.assertIn("local work product", result.stderr)

    def test_reviewed_design_and_pipeline_source_are_allowed(self):
        self.stage("docs/design/claude-design-handoff/mobile-ui-2.0/README.md")
        self.stage("marketing/site/generate-assets.sh")
        self.assertEqual(self.check().returncode, 0)

    def test_size_check_reads_index_and_permits_required_preview(self):
        data = "x" * (10 * 1024 * 1024 + 1)
        self.stage("fastlane/previews/en-US/app-preview.mov", data)
        self.assertEqual(self.check().returncode, 0)
        self.stage("accidental-output.bin", data)
        (self.root / "accidental-output.bin").write_text("small unstaged replacement")
        self.assertIn("over 10 MiB", self.check().stderr)

    def test_personal_global_ignore_does_not_change_repository_policy(self):
        personal_ignore = self.root / "personal-ignore"
        personal_ignore.write_text("*.md\n")
        self.git("config", "core.excludesFile", str(personal_ignore))
        self.stage("README.md")
        self.assertEqual(self.check().returncode, 0)


if __name__ == "__main__":
    unittest.main()
