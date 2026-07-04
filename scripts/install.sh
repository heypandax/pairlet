#!/usr/bin/env bash
# One-shot installer for the cc-pocket daemon — macOS (arm64/x86_64) and Linux (x86_64/arm64):
#
#   curl -fsSL https://raw.githubusercontent.com/heypandax/cc-pocket/main/scripts/install.sh | bash
#
# The Claude Code distribution model: downloads the self-contained build (bundled JRE — no system
# Java), verifies it against the release's SHA256SUMS, installs it under
#   ~/.local/share/cc-pocket/versions/<ver>/          (one dir per version)
#   ~/.local/bin/cc-pocket-daemon -> versions/<ver>/… (stable launcher symlink, atomic flip)
# and registers the background service (launchd on macOS / systemd --user on Linux) pointed at the
# SYMLINK — so upgrades are just "flip the link + restart", which is exactly what the daemon's own
# `cc-pocket-daemon update` (and its daily update check) does from then on.
#
# Re-run to upgrade. Env overrides: CC_POCKET_VERSION=vX.Y.Z (default latest), CC_POCKET_ROOT,
# CC_POCKET_BIN, CC_POCKET_NO_SERVICE=1 (install files only — used by CI/tests).
set -euo pipefail

REPO="heypandax/cc-pocket"
BIN="cc-pocket-daemon"
ROOT="${CC_POCKET_ROOT:-$HOME/.local/share/cc-pocket}"
BINDIR="${CC_POCKET_BIN:-$HOME/.local/bin}"
VERSION="${CC_POCKET_VERSION:-latest}"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
err()  { printf '\033[1;31merror:\033[0m %s\n'  "$*" >&2; exit 1; }

# --- platform ---
os="$(uname -s)"; arch="$(uname -m)"
case "$os" in
  Darwin) plat="macos" ;;
  Linux)  plat="linux" ;;
  *) err "unsupported OS: $os (Windows: irm https://raw.githubusercontent.com/$REPO/main/scripts/install.ps1 | iex)" ;;
esac
case "$arch" in
  x86_64|amd64)  arch="x86_64" ;;
  aarch64|arm64) arch="arm64" ;;
  *) err "unsupported architecture: $arch" ;;
esac
for c in curl tar; do command -v "$c" >/dev/null 2>&1 || err "missing required command: $c"; done
shasum_cmd=""
if command -v sha256sum >/dev/null 2>&1; then shasum_cmd="sha256sum"
elif command -v shasum   >/dev/null 2>&1; then shasum_cmd="shasum -a 256"; fi

# --- resolve version (GitHub redirects /releases/latest -> /releases/tag/vX.Y.Z; no API quota used) ---
if [ "$VERSION" = "latest" ]; then
  say "resolving latest release"
  VERSION="$(curl -fsSL -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest" | sed -E 's#.*/tag/##')"
  [ -n "$VERSION" ] || err "could not resolve the latest version (set CC_POCKET_VERSION=vX.Y.Z to pin one)"
fi
ver="${VERSION#v}"   # asset names drop the leading v
asset="${BIN}-${ver}-${plat}-${arch}.tar.gz"
base="https://github.com/$REPO/releases/download/${VERSION}"

# --- download + verify ---
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
say "downloading $asset"
curl -fSL --progress-bar "$base/$asset" -o "$tmp/$asset" || err "download failed: $base/$asset"
if [ -n "$shasum_cmd" ] && curl -fsSL "$base/SHA256SUMS" -o "$tmp/SHA256SUMS" 2>/dev/null; then
  expected="$(awk -v a="$asset" '$2==a || $2=="*"a {print tolower($1)}' "$tmp/SHA256SUMS" | head -1)"
  if [ -n "$expected" ]; then
    actual="$($shasum_cmd "$tmp/$asset" | awk '{print tolower($1)}')"
    [ "$actual" = "$expected" ] || err "checksum mismatch for $asset (expected $expected, got $actual) — corrupted download or tampered artifact"
    say "checksum OK"
  else
    warn "SHA256SUMS has no entry for $asset — skipping verification"
  fi
else
  warn "no SHA256SUMS on this release (or no sha256 tool) — skipping verification"
fi

# --- install into the versioned layout + atomic symlink flip ---
# one rule: versions/<ver>/ holds the archive's top-level entry UNCHANGED — macOS ships a signed
# cc-pocket-daemon.app bundle, Linux a cc-pocket-daemon/ dir (the daemon's self-update assumes the same)
dest="$ROOT/versions/$ver"
say "installing to $dest"
rm -rf "$dest"; mkdir -p "$dest" "$BINDIR"
tar -xzf "$tmp/$asset" -C "$tmp"
if [ -d "$tmp/$BIN.app" ]; then
  mv "$tmp/$BIN.app" "$dest/"
  launcher="$dest/$BIN.app/Contents/MacOS/$BIN"
elif [ -d "$tmp/$BIN" ]; then
  mv "$tmp/$BIN" "$dest/"
  launcher="$dest/$BIN/bin/$BIN"
else
  err "unexpected tarball layout: no $BIN[.app]/ top-level entry"
fi
[ -x "$launcher" ] || err "unexpected layout: $launcher not found after extraction"
ln -sfn "$launcher" "$BINDIR/$BIN"

# --- background service, anchored at the SYMLINK (stable across upgrades/self-update) ---
if [ "${CC_POCKET_NO_SERVICE:-}" = "1" ]; then
  warn "CC_POCKET_NO_SERVICE=1 — skipping service registration"
elif [ "$plat" = "macos" ]; then
  say "registering launchd agent"
  # service-install rewrites the plist + reload: the running daemon (any origin) is replaced by this one
  "$BINDIR/$BIN" service-install --apply --exec "$BINDIR/$BIN"
else
  if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
    say "registering systemd --user service"
    "$BINDIR/$BIN" service-install --apply --exec "$BINDIR/$BIN"
    # enable --now is a no-op on an already-running service; force onto the new binary
    say "restarting onto the new binary"
    systemctl --user restart "$BIN"
  else
    warn "no systemd --user session detected — skipping service registration"
    warn "start the daemon manually instead: $BINDIR/$BIN run"
  fi
fi

# --- migrate the legacy flat layout (pre-1.2.x installs at ~/.local/opt) ---
legacy="$HOME/.local/opt/$BIN"
if [ -d "$legacy" ]; then
  say "removing legacy install at $legacy (replaced by the versioned layout)"
  rm -rf "$legacy"
fi

# --- prune old versions (keep the newest 2; `head -n -N` is GNU-only, so count explicitly) ---
count="$(ls -1 "$ROOT/versions" 2>/dev/null | wc -l | tr -d ' ')"
if [ "${count:-0}" -gt 2 ]; then
  ls -1 "$ROOT/versions" | sort -t. -k1,1n -k2,2n -k3,3n | head -n "$((count - 2))" | while read -r old; do
    if [ "$old" != "$ver" ]; then
      rm -rf "$ROOT/versions/$old"
      say "pruned old version $old"
    fi
  done
fi

case ":$PATH:" in
  *":$BINDIR:"*) ;;
  *) warn "$BINDIR is not on PATH — add it:  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.$(basename "${SHELL:-bash}")rc" ;;
esac

logs="journalctl --user -u $BIN -f"
[ "$plat" = "macos" ] && logs="tail -f ~/Library/Logs/cc-pocket/daemon.err.log"
cat <<EOF

  ✅ Installed $BIN $VERSION

  Next — pair your phone:

      $BIN pair

  (prints a QR + 6-digit code; scan it in the CC Pocket app)

  Logs:    $logs
  Upgrade: $BIN update   (the daemon also checks daily and notifies your phone)
EOF
