#!/usr/bin/env python3
"""Check macOS jpackage CLI compatibility in an isolated home; never run or register a service."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path, help="built cc-pocket-daemon.app")
    args = parser.parse_args()
    source = args.app.resolve()
    assert (source / "Contents/MacOS/cc-pocket-daemon").is_file(), source
    with tempfile.TemporaryDirectory(prefix="pairlet-native-") as tmp:
        home = Path(tmp) / "home"
        version = home / ".local/share/cc-pocket/versions/cli-smoke"
        version.mkdir(parents=True)
        app = version / "cc-pocket-daemon.app"
        shutil.copytree(source, app, symlinks=True)
        bin_dir = home / ".local/bin"
        bin_dir.mkdir(parents=True)
        legacy = bin_dir / "cc-pocket-daemon"
        legacy.symlink_to(app / "Contents/MacOS/cc-pocket-daemon")
        short = bin_dir / "pairlet"
        env = dict(os.environ, HOME=str(home), JAVA_TOOL_OPTIONS=f'-Duser.home="{home}"')

        def invoke(command):
            result = subprocess.run([str(command), "version"], env=env, text=True,
                                    capture_output=True, timeout=30)
            assert result.returncode == 0, (result.stdout, result.stderr)
            assert "self-update enabled" in result.stdout, result.stdout
            return result.stdout

        # Emulate the old updater: it has only installed/switched the legacy stable launcher.
        assert not short.exists()
        original = invoke(legacy)
        assert short.is_file() and not short.is_symlink(), "native jpackage needs a forwarding script"
        assert invoke(short) == original
        # Installer and first-start bootstrap must recognize each other's shim on retry.
        installer = (Path(__file__).resolve().parent / "install.sh").read_text()
        shell_shim = installer.split("<<'PAIRLET_CLI'\n", 1)[1].split("\nPAIRLET_CLI", 1)[0] + "\n"
        assert short.read_text() == shell_shim
        assert not (home / "Library/LaunchAgents").exists()
        print("macOS native CLI OK: first-start alias, both commands, install ownership, installer shim match; no service registered")


if __name__ == "__main__":
    main()
