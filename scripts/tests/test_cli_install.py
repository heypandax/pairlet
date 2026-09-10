"""Exercise the real installer against local release fixtures; never start a daemon or touch HOME."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class CliInstallTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="pairlet cli ")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / "home"
        self.home.mkdir()
        self.bin = self.home / ".local/bin"
        self.releases = self.root / "releases"
        self.releases.mkdir()
        self.mock = self.root / "tools"
        self.mock.mkdir()
        curl = self.mock / "curl"
        curl.write_text(f"#!{sys.executable}\n" + """
import os, pathlib, shutil, sys
args = sys.argv[1:]
url = next(a for a in args if a.startswith('https://'))
target = args[args.index('-o') + 1]
shutil.copyfile(pathlib.Path(os.environ['FIXTURE_RELEASES']) / url.rsplit('/', 1)[1], target)
""")
        curl.chmod(0o755)
        self.env = dict(os.environ, HOME=str(self.home), CC_POCKET_ROOT=str(self.home / ".local/share/cc-pocket"),
                        CC_POCKET_BIN=str(self.bin), CC_POCKET_NO_SERVICE="1", CC_POCKET_MIRROR="off",
                        FIXTURE_RELEASES=str(self.releases), PATH=f"{self.mock}:{os.environ['PATH']}")

    def install(self, platform, version):
        uname = self.mock / "uname"
        uname.write_text(f'#!/bin/sh\nif [ "$1" = "-s" ]; then echo {platform}; else echo arm64; fi\n')
        uname.chmod(0o755)
        top = "cc-pocket-daemon.app" if platform == "Darwin" else "cc-pocket-daemon"
        inside = "Contents/MacOS/cc-pocket-daemon" if platform == "Darwin" else "bin/cc-pocket-daemon"
        staging = self.root / f"stage-{platform}-{version}"
        launcher = staging / top / inside
        launcher.parent.mkdir(parents=True, exist_ok=True)
        launcher.write_text(f'#!/bin/sh\nprintf "{version}\\n"\nprintf "<%s>\\n" "$@"\nexit 23\n')
        launcher.chmod(0o755)
        asset = self.releases / f'cc-pocket-daemon-{version}-{"macos" if platform == "Darwin" else "linux"}-arm64.tar.gz'
        with tarfile.open(asset, "w:gz") as archive:
            archive.add(staging / top, arcname=top)
        digest = hashlib.sha256(asset.read_bytes()).hexdigest()
        (self.releases / "SHA256SUMS").write_text(f"{digest}  {asset.name}\n")
        result = subprocess.run(["bash", str(REPO / "scripts/install.sh")],
                                env=dict(self.env, CC_POCKET_VERSION=f"v{version}"), text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("checksum OK", result.stdout)
        self.assertIn("skipping service registration", result.stderr)
        return result

    def assert_command(self, name, version):
        result = subprocess.run([str(self.bin / name), "pair", "two words", "", "a&b"], text=True, capture_output=True)
        self.assertEqual(23, result.returncode)
        self.assertEqual(f"{version}\n<pair>\n<two words>\n<>\n<a&b>\n", result.stdout)

    def test_macos_install_and_retry_keep_both_commands(self):
        self.install("Darwin", "1.0.0")
        self.install("Darwin", "1.0.0")
        self.install("Darwin", "1.0.1")
        self.assertFalse((self.bin / "pairlet").is_symlink())
        self.assertTrue(os.access(self.bin / "pairlet", os.X_OK))
        for name in ["pairlet", "cc-pocket-daemon"]:
            self.assert_command(name, "1.0.1")

    def test_linux_upgrade_and_pruning_keep_alias_on_latest_version(self):
        for version in ["1.0.0", "1.0.1", "1.0.2"]:
            self.install("Linux", version)
        self.assertFalse((Path(self.env["CC_POCKET_ROOT"]) / "versions/1.0.0").exists())
        for name in ["pairlet", "cc-pocket-daemon"]:
            self.assert_command(name, "1.0.2")

    def test_existing_pairlet_is_preserved_with_legacy_fallback(self):
        self.bin.mkdir(parents=True)
        (self.bin / "pairlet").write_text("unrelated tool")
        result = self.install("Darwin", "1.0.0")
        self.assertEqual("unrelated tool", (self.bin / "pairlet").read_text())
        self.assertIn("keeping it", result.stderr)
        self.assertIn("cc-pocket-daemon pair", result.stdout)
        self.assert_command("cc-pocket-daemon", "1.0.0")

    def test_dangling_pairlet_symlink_is_preserved(self):
        self.bin.mkdir(parents=True)
        (self.bin / "pairlet").symlink_to("another-missing-tool")
        self.install("Linux", "1.0.0")
        self.assertEqual("another-missing-tool", os.readlink(self.bin / "pairlet"))
        self.assert_command("cc-pocket-daemon", "1.0.0")


if __name__ == "__main__":
    unittest.main()
