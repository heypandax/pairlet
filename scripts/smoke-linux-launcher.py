#!/usr/bin/env python3
"""Run the real packaged launcher with small Linux pipes (regression #385).

Linux reduces new pipes to two pages when a UID reaches pipe-user-pages-soft.
Hold empty pipes briefly to exercise that normal kernel condition, without
changing system limits or replacing/interposing the launcher. All descriptors
are closed on exit. Run as an ordinary user: root can bypass the pipe quota.
"""

import argparse
import fcntl
import os
from pathlib import Path
import resource
import subprocess
import tempfile


def smoke(image):
    launcher = image.resolve() / "bin" / "CC Pocket"
    if not launcher.is_file():
        raise ValueError(f"No launcher at {launcher}")
    descriptors = []
    old_limit = resource.getrlimit(resource.RLIMIT_NOFILE)
    # Two pages are 8 KiB on x86_64; some ARM64 kernels use larger pages.
    small_capacity = 2 * os.sysconf("SC_PAGESIZE")
    try:
        # Bound both descriptor usage and time even on hosts with nondefault quotas.
        hard = old_limit[1]
        soft = 8192 if hard == resource.RLIM_INFINITY else min(8192, hard)
        resource.setrlimit(resource.RLIMIT_NOFILE, (soft, hard))
        for _ in range(min(2048, (soft - 64) // 2)):
            read_fd, write_fd = os.pipe()
            descriptors.extend((read_fd, write_fd))
            capacity = fcntl.fcntl(write_fd, fcntl.F_GETPIPE_SZ)
            if capacity <= small_capacity:
                break
        else:
            raise RuntimeError("Could not obtain two-page pipes; regression was NOT tested. "
                               "Use an unprivileged Linux user with standard pipe quotas.")
        print(f"Launcher regression: pipe capacity={capacity}, held pipes={len(descriptors) // 2}",
              flush=True)
        with tempfile.TemporaryDirectory(prefix="ccpocket-launcher-smoke-") as directory:
            marker = Path(directory) / "success"
            result = subprocess.run([str(launcher), "--package-smoke", str(marker)],
                                    timeout=45, capture_output=True, text=True)
            if result.returncode != 0:
                raise RuntimeError(f"Launcher exited {result.returncode}\n{result.stdout}\n{result.stderr}")
            if not marker.is_file() or marker.read_text().strip() != "CCP_PACKAGE_SMOKE_OK":
                raise RuntimeError(f"Packaged JVM did not write success marker\n{result.stdout}\n{result.stderr}")
        print("CCP_LINUX_LAUNCHER_SMOKE_OK")
    finally:
        for descriptor in descriptors:
            os.close(descriptor)
        resource.setrlimit(resource.RLIMIT_NOFILE, old_limit)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, help="Linux app image directory or .deb package")
    args = parser.parse_args()
    if args.artifact.is_file() and args.artifact.suffix == ".deb":
        # Test the actual installer payload too, without installing into the CI host.
        with tempfile.TemporaryDirectory(prefix="ccpocket-deb-smoke-") as directory:
            subprocess.run(["dpkg-deb", "-x", str(args.artifact.resolve()), directory], check=True)
            smoke(Path(directory) / "opt" / "cc-pocket")
    else:
        smoke(args.artifact)


if __name__ == "__main__":
    main()
