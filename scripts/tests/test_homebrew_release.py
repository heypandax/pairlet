"""Exercise both release stages without network access or published artifacts."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("homebrew_release", ROOT / "scripts/homebrew-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class HomebrewReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        directory = self.root / "packaging/homebrew"
        shutil.copytree(ROOT / "packaging/homebrew", directory)
        self.cask = directory / "Casks/cc-pocket.rb"
        self.record = directory / "release.json"
        self.assets = self.root / "assets"
        self.assets.mkdir()
        text = self.cask.read_text()
        text = re.sub(r'(^\s*version ")[^"]+(")', r'\g<1>2.6.0\2',
                      text, count=1, flags=re.MULTILINE)
        self.cask.write_text(text)
        self.record.write_text(json.dumps(release.cask_fields(text)))
        self.original = self.cask.read_bytes(), self.record.read_bytes()

    def artifacts(self, version="2.6.1"):
        sums = []
        for arch in release.ARCHES:
            name = f"cc-pocket-daemon-{version}-macos-{arch}.tar.gz"
            body = f"fixture bytes for {version} {arch}".encode()
            (self.assets / name).write_bytes(body)
            sums.append(f"{hashlib.sha256(body).hexdigest()}  {name}\n")
        (self.assets / "SHA256SUMS").write_text("".join(sums))

    def test_candidate_allows_explicit_previous_release(self):
        self.assertEqual("2.6.0", release.check(self.root, "2.6.1")["version"])

    def test_editing_only_the_cask_version_is_rejected(self):
        self.cask.write_text(self.cask.read_text().replace('version "2.6.0"', 'version "2.6.1"'))
        with self.assertRaisesRegex(ValueError, "differs"):
            release.check(self.root, "2.6.1")

    def test_editing_only_one_hash_is_rejected(self):
        old = json.loads(self.record.read_text())["sha256"]["arm64"]
        self.cask.write_text(self.cask.read_text().replace(old, "0" * 64))
        with self.assertRaisesRegex(ValueError, "differs"):
            release.check(self.root, "2.6.1")

    def test_unexpected_url_is_rejected(self):
        self.cask.write_text(self.cask.read_text().replace("https://github.com/", "https://example.test/"))
        with self.assertRaisesRegex(ValueError, "URL"):
            release.check(self.root, "2.6.1")

    def test_future_cask_and_invalid_candidate_are_rejected(self):
        for version in ["2.5.9", "02.6.1", "2.6.1-rc1"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.check(self.root, version)

    def test_final_verification_rejects_previous_release(self):
        self.artifacts()
        with self.assertRaisesRegex(ValueError, "target version"):
            release.verify(self.root, "2.6.1", self.assets)

    def test_prepare_without_write_leaves_both_files_unchanged(self):
        self.artifacts()
        self.assertEqual("2.6.1", release.prepare(self.root, "2.6.1", self.assets)["version"])
        self.assertEqual(self.original, (self.cask.read_bytes(), self.record.read_bytes()))

    def test_prepare_writes_both_arches_and_final_verify_passes(self):
        self.artifacts()
        target = release.prepare(self.root, "2.6.1", self.assets, write=True)
        release.verify(self.root, "2.6.1", self.assets)
        self.assertEqual(target, json.loads(self.record.read_text()))
        self.assertEqual(target, release.cask_fields(self.cask.read_text()))
        self.assertNotEqual(target["sha256"]["arm64"], target["sha256"]["x86_64"])

    def test_missing_architecture_does_not_modify_files(self):
        self.artifacts()
        (self.assets / "cc-pocket-daemon-2.6.1-macos-x86_64.tar.gz").unlink()
        with self.assertRaises(OSError):
            release.prepare(self.root, "2.6.1", self.assets, write=True)
        self.assertEqual(self.original, (self.cask.read_bytes(), self.record.read_bytes()))

    def test_tampered_asset_does_not_modify_files(self):
        self.artifacts()
        (self.assets / "cc-pocket-daemon-2.6.1-macos-arm64.tar.gz").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            release.prepare(self.root, "2.6.1", self.assets, write=True)
        self.assertEqual(self.original, (self.cask.read_bytes(), self.record.read_bytes()))

    def test_missing_and_duplicate_checksum_entries_are_rejected(self):
        for duplicate in [False, True]:
            with self.subTest(duplicate=duplicate):
                self.artifacts()
                sums = self.assets / "SHA256SUMS"
                lines = sums.read_text().splitlines(keepends=True)
                sums.write_text("".join(lines + [lines[0]] if duplicate else lines[:1]))
                with self.assertRaisesRegex(ValueError, "duplicate|missing"):
                    release.prepare(self.root, "2.6.1", self.assets, write=True)
                self.assertEqual(self.original, (self.cask.read_bytes(), self.record.read_bytes()))

    def test_rebuilding_recorded_version_with_different_hashes_is_rejected(self):
        self.artifacts("2.6.0")
        with self.assertRaisesRegex(ValueError, "already recorded version"):
            release.prepare(self.root, "2.6.0", self.assets, write=True)

    def test_full_source_preflight_still_rejects_core_or_requested_version_mismatch(self):
        paths = [
            "scripts/check-release-version.sh", "scripts/homebrew-release.py",
            "daemon/build.gradle.kts", "mobile/composeApp/build.gradle.kts",
            "mobile/composeApp/src/desktopMain/kotlin/dev/ccpocket/app/desktop/SeedDesktopModel.kt",
            "iosApp/iosApp/Info.plist", "harmony/AppScope/app.json5",
            "fastlane/metadata/en-US/release_notes.txt",
            "fastlane/metadata/zh-Hans/release_notes.txt",
        ]
        for rel in paths:
            dest = self.root / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / rel, dest)
        version = release.one(r'.*val appVersionName = "([^"]+)".*',
                              (self.root / paths[3]).read_text(), "mobile version")

        def run(expected):
            return subprocess.run(["bash", str(self.root / paths[0]), expected],
                                  capture_output=True, text=True)

        passed = run(version)
        self.assertEqual(0, passed.returncode, passed.stderr)
        self.assertNotEqual(0, run("99.0.0").returncode)
        daemon = self.root / paths[2]
        daemon.write_text(daemon.read_text().replace(f'?: "{version}"', '?: "99.0.0"'))
        failed = run(version)
        self.assertNotEqual(0, failed.returncode)
        self.assertIn("daemon is 99.0.0", failed.stderr)


if __name__ == "__main__":
    unittest.main()
