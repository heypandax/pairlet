#!/usr/bin/env bash
set -euo pipefail

# Run the smoke entrypoint through the jpackage IMAGE's own launcher — using Gradle's JVM here would
# miss the exact jlink/module failure this gate exists to catch (#251/#305). jpackage strips the image's
# standalone `java` command, and a Windows GUI launcher may detach / hide stdout, so success is a marker
# written by the packaged JVM only after every assertion passes.
APP_IMAGE=${1:?usage: smoke-desktop-image.sh <jpackage-app-image>}

case "$(uname -s)" in
  Darwin)
    LAUNCHER="$APP_IMAGE/Contents/MacOS/CC Pocket"
    ;;
  *)
    LAUNCHER="$APP_IMAGE/CC Pocket.exe"
    ;;
esac

[ -x "$LAUNCHER" ] || { echo "desktop image launcher not found: $LAUNCHER" >&2; exit 1; }

MARKER=$(mktemp -t ccpocket-package-smoke.XXXXXX)
trap 'rm -f "$MARKER"' EXIT

LAUNCH_STATUS=0
"$LAUNCHER" --package-smoke "$MARKER" || LAUNCH_STATUS=$?

# A console launcher reaches this point after the JVM exits. A Windows GUI launcher may return first;
# poll briefly for the packaged process to finish instead of treating the detach as a pass.
for _ in $(seq 1 60); do
  if grep -Fqx 'CCP_PACKAGE_SMOKE_OK' "$MARKER"; then
    echo 'CCP_PACKAGE_SMOKE_OK'
    exit 0
  fi
  [ "$LAUNCH_STATUS" -eq 0 ] || break
  sleep 1
done

echo "desktop image smoke failed (launcher exit $LAUNCH_STATUS)" >&2
exit 1
