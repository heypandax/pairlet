#!/usr/bin/env bash
# Build the Pairlet DESKTOP app (Compose Desktop, two-pane client) for Linux:
#   createDistributable → packaged-image smoke → packageDeb + packageRpm → stable-named artifacts.
#
# Unlike macOS (scripts/release-desktop-macos.sh) there is no signing/notarization step on Linux, so
# this is the whole pipeline. jpackage CANNOT cross-compile: it bundles the host JRE and shells out to
# dpkg-deb / rpmbuild, so this must run on a Linux host of the target architecture (issue #379).
#
# Prereqs (see the `linux-desktop` job in .github/workflows/release.yml):
#   - JDK 17 with jpackage (JAVA_HOME)
#   - dpkg-deb + fakeroot   (deb)  — present on ubuntu runners
#   - rpmbuild              (rpm)  — NOT preinstalled on ubuntu runners; `apt-get install -y rpm`
#   - Android SDK + a google-services.json (composeApp applies the Android plugin at configure time)
#
# Usage: scripts/release-desktop-linux.sh [version]   (version defaults to build.gradle appVersionName)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${1:-$(grep -E 'val appVersionName *= *"' mobile/composeApp/build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')}"
[ -n "$VERSION" ] || { echo "ERROR: could not determine version (pass it as the first arg)"; exit 1; }

# Normalize to the release asset spelling used by every other Linux artifact (scripts/release-linux.sh,
# the daemon tarballs and the macOS dmg names all say "arm64"; uname on Linux reports "aarch64").
case "$(uname -m)" in
  aarch64|arm64) ARCH="arm64" ;;
  *)             ARCH="$(uname -m)" ;;
esac

# The repo's gradle.properties pins org.gradle.java.home to the Mac dev box's Homebrew JDK, which does
# not exist on Linux. Override it from JAVA_HOME (CI always sets it); same trick as release-linux.sh.
GRADLE_JDK=(${JAVA_HOME:+-Dorg.gradle.java.home="$JAVA_HOME"})

echo "==> gradle :mobile:composeApp:createDistributable  (v$VERSION · $ARCH)"
# Retry: Maven Central / the plugins portal occasionally 403s or times out from CI (same guard the
# windows-desktop job uses inline).
ok=0
for attempt in 1 2 3; do
  if ./gradlew :mobile:composeApp:createDistributable --no-daemon "${GRADLE_JDK[@]}"; then ok=1; break; fi
  echo "WARNING: createDistributable attempt $attempt failed (network?), retrying in 20s"; sleep 20
done
[ "$ok" = 1 ] || { echo "ERROR: createDistributable failed after 3 attempts"; exit 1; }

APP_IMAGE="mobile/composeApp/build/compose/binaries/main/app/CC Pocket"
[ -d "$APP_IMAGE" ] || { echo "ERROR: jpackage app image not found at $APP_IMAGE"; exit 1; }

echo "==> packaged-image smoke (runs the BUNDLED JVM, catches jlink module drops — #251/#305)"
bash scripts/smoke-desktop-image.sh "$APP_IMAGE"

echo "==> gradle packageDeb + packageRpm"
./gradlew :mobile:composeApp:packageDeb :mobile:composeApp:packageRpm --no-daemon "${GRADLE_JDK[@]}"

DEB="$(ls -t mobile/composeApp/build/compose/binaries/main/deb/*.deb 2>/dev/null | head -1)"
RPM="$(ls -t mobile/composeApp/build/compose/binaries/main/rpm/*.rpm 2>/dev/null | head -1)"
[ -n "$DEB" ] && [ -f "$DEB" ] || { echo "ERROR: packageDeb produced no .deb"; exit 1; }
[ -n "$RPM" ] && [ -f "$RPM" ] || { echo "ERROR: packageRpm produced no .rpm (is rpmbuild installed?)"; exit 1; }

# Payload gate: jpackage happily emits a well-formed package around an empty tree. Prove the launcher
# and the bundled runtime actually shipped before we attach either file to a release.
echo "==> verify package payloads"
# List each payload ONCE into a file, then grep the file. `dpkg-deb -c | grep -q` looks equivalent but
# is not under `set -o pipefail`: grep -q exits at the first match, dpkg-deb's tar gets SIGPIPE
# ("tar: stdout: write error", exit 2) and the pipeline reports failure for a package that is fine —
# exactly how the first v2.1.0 linux-desktop run died on both arches.
DEB_LIST="$(mktemp)"; RPM_LIST="$(mktemp)"
trap 'rm -f "$DEB_LIST" "$RPM_LIST"' EXIT
dpkg-deb -c "$DEB" > "$DEB_LIST" || { echo "ERROR: dpkg-deb could not list $DEB"; exit 1; }
rpm -qlp "$RPM" > "$RPM_LIST" || { echo "ERROR: rpm could not list $RPM"; exit 1; }
grep -Fq 'bin/CC Pocket' "$DEB_LIST" || { echo "ERROR: .deb payload has no 'bin/CC Pocket' launcher"; exit 1; }
grep -Fq 'lib/runtime/' "$DEB_LIST" || { echo "ERROR: .deb payload has no bundled JVM runtime"; exit 1; }
grep -Fq 'bin/CC Pocket' "$RPM_LIST" || { echo "ERROR: .rpm payload has no 'bin/CC Pocket' launcher"; exit 1; }
grep -Fq 'lib/runtime/' "$RPM_LIST" || { echo "ERROR: .rpm payload has no bundled JVM runtime"; exit 1; }
# Menu entry. jpackage never ships share/applications/ inside the payload: the .desktop file lives
# under <install-dir>/lib/ and the package's post-install script registers it with
# `xdg-desktop-menu install` (verified on the 2.1.0 packages). Without that registration the app
# installs but never appears in the desktop menu, which to a user reads as "the app is broken" —
# so assert BOTH halves, on both package formats.
grep -Eq 'lib/[^/ ]+\.desktop$' "$DEB_LIST" || { echo "ERROR: .deb payload has no .desktop file under lib/"; exit 1; }
grep -Eq 'lib/[^/ ]+\.desktop$' "$RPM_LIST" || { echo "ERROR: .rpm payload has no .desktop file under lib/"; exit 1; }
CTL_DIR="$(mktemp -d)"; RPM_SCRIPTS="$(mktemp)"
trap 'rm -rf "$DEB_LIST" "$RPM_LIST" "$CTL_DIR" "$RPM_SCRIPTS"' EXIT
dpkg-deb -e "$DEB" "$CTL_DIR" || { echo "ERROR: dpkg-deb could not extract control scripts from $DEB"; exit 1; }
grep -Fq 'xdg-desktop-menu install' "$CTL_DIR/postinst" || { echo "ERROR: .deb postinst does not register a menu entry (xdg-desktop-menu)"; exit 1; }
rpm -qp --scripts "$RPM" > "$RPM_SCRIPTS" || { echo "ERROR: rpm could not read scripts from $RPM"; exit 1; }
grep -Fq 'xdg-desktop-menu install' "$RPM_SCRIPTS" || { echo "ERROR: .rpm post-install does not register a menu entry (xdg-desktop-menu)"; exit 1; }

DEB_OUT="cc-pocket-desktop-${VERSION}-linux-${ARCH}.deb"
RPM_OUT="cc-pocket-desktop-${VERSION}-linux-${ARCH}.rpm"
cp -f "$DEB" "$ROOT/$DEB_OUT"
cp -f "$RPM" "$ROOT/$RPM_OUT"

echo ""
echo "    artifact : $DEB_OUT"
echo "    sha256   : $(sha256sum "$ROOT/$DEB_OUT" | awk '{print $1}')"
echo "    artifact : $RPM_OUT"
echo "    sha256   : $(sha256sum "$ROOT/$RPM_OUT" | awk '{print $1}')"
echo ""
echo "Install locally:  sudo apt install ./$DEB_OUT    |    sudo dnf install ./$RPM_OUT"
echo "Next: attach to the GitHub release →  gh release upload v$VERSION $DEB_OUT $RPM_OUT --clobber"
