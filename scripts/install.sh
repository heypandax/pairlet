#!/usr/bin/env bash
# One-shot installer for the cc-pocket daemon on Linux (x86_64).
# Mirrors the macOS `brew install --cask heypandax/tap/cc-pocket` experience:
#
#   curl -fsSL https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.sh | bash
#
# Downloads the self-contained tarball (bundled JRE) from GitHub Releases, installs it under
# ~/.local, and registers a `systemd --user` service. No root, no system Java. Re-run to upgrade.
#
# Env overrides: CC_POCKET_VERSION=vX.Y.Z (default: latest), CC_POCKET_OPT, CC_POCKET_BIN.
set -euo pipefail

REPO="heypandax/cc-pocket"
BIN="cc-pocket-daemon"
OPT="${CC_POCKET_OPT:-$HOME/.local/opt}"
BINDIR="${CC_POCKET_BIN:-$HOME/.local/bin}"
VERSION="${CC_POCKET_VERSION:-latest}"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31merror:\033[0m %s\n'  "$*" >&2; exit 1; }

# --- platform check ---
[ "$(uname -s)" = "Linux" ] || err "this installer is Linux-only; on macOS run: brew install --cask heypandax/tap/cc-pocket"
arch="$(uname -m)"
case "$arch" in
  x86_64|amd64) arch="x86_64" ;;
  aarch64|arm64) err "no prebuilt Linux arm64 tarball yet — build from source: ./gradlew :daemon:packageDaemon (see README)" ;;
  *) err "unsupported architecture: $arch" ;;
esac

# --- required tools ---
for c in curl tar; do command -v "$c" >/dev/null 2>&1 || err "missing required command: $c"; done

# --- resolve version (GitHub redirects /releases/latest -> /releases/tag/vX.Y.Z; no API quota used) ---
if [ "$VERSION" = "latest" ]; then
  say "resolving latest release"
  VERSION="$(curl -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest" | sed -E 's#.*/tag/##')"
  [ -n "$VERSION" ] || err "could not resolve the latest version (set CC_POCKET_VERSION=vX.Y.Z to pin one)"
fi
ver="${VERSION#v}"   # asset names drop the leading v
asset="${BIN}-${ver}-linux-${arch}.tar.gz"
url="https://github.com/$REPO/releases/download/${VERSION}/${asset}"

# --- download ---
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
say "downloading $asset"
curl -fSL --progress-bar "$url" -o "$tmp/$asset" || err "download failed: $url"

# --- install (idempotent: a re-run replaces the prior copy = upgrade) ---
say "installing to $OPT/$BIN"
mkdir -p "$OPT" "$BINDIR"
rm -rf "${OPT:?}/$BIN"
tar -xzf "$tmp/$asset" -C "$OPT"
launcher="$OPT/$BIN/bin/$BIN"
[ -x "$launcher" ] || err "unexpected tarball layout: $launcher not found after extraction"
ln -sf "$launcher" "$BINDIR/$BIN"

# --- register the background service (point ExecStart at the real binary, not the symlink) ---
if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
  say "registering systemd --user service"
  "$launcher" service-install --apply --exec "$launcher"
  # service-install does `enable --now`, which is a NO-OP on an already-running service — so an
  # in-place upgrade would keep running the old (rm'd-but-still-alive) binary. Force a restart so the
  # new binary actually takes over. (macOS avoids this via the cask uninstall-then-postflight order.)
  say "restarting onto the new binary"
  systemctl --user restart "$BIN"
else
  warn "no systemd --user session detected — skipping service registration"
  warn "start the daemon manually instead: $BINDIR/$BIN run"
fi

# --- PATH hint (the service uses an absolute path, but `pair` is run by hand) ---
case ":$PATH:" in
  *":$BINDIR:"*) ;;
  *) warn "$BINDIR is not on PATH — add it:  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.bashrc && source ~/.bashrc" ;;
esac

cat <<EOF

  ✅ Installed $BIN $VERSION

  Next — pair your phone:

      $BIN pair

  (prints a QR + 6-digit code; scan it in the CC Pocket app)

  Logs:    journalctl --user -u $BIN -f
  Upgrade: re-run this installer
EOF
