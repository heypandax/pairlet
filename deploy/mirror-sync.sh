#!/usr/bin/env bash
# cc-pocket release mirror — runs ON the relay box (systemd timer, see cc-pocket-mirror-sync.timer).
#
# Pulls the latest GitHub release's DAEMON assets into /var/www/cc-pocket-dl so installs and
# self-updates from mainland China download over this box's direct link instead of GitHub's CDN.
# Caddy serves the tree at https://pocket.ark-nexus.cc/dl/ (handle_path /dl/*). Pull model on
# purpose: HK→GitHub is fast, no CI-side secrets, and a missed run self-heals on the next tick.
#
# Contract with clients (scripts/install.sh, install.ps1, protocol ReleaseClient):
#   dl/latest.json      {"version":"1.6.2","assets":{"<asset>":"<url>",…}} — the release's COMPLETE
#                       asset map: mirrored files point at this host, everything else keeps its
#                       GitHub URL, so a Release parsed from here is interchangeable with one from
#                       the GitHub API regardless of mirror scope (desktop updater included).
#   dl/<tag>/<asset>    mirrored artifacts + SHA256SUMS (verified against GitHub before going live)
#   dl/install.sh|.ps1  the one-line installers (raw.githubusercontent is slow/blocked in CN too)
# latest.json is written LAST and atomically — it never references a half-mirrored version.
set -euo pipefail

REPO="heypandax/cc-pocket"
DEST="/var/www/cc-pocket-dl"
BASE_URL="https://pocket.ark-nexus.cc/dl"
KEEP=2                                                # version dirs to retain
MIRROR_RE='^cc-pocket-daemon-.*\.(tar\.gz|zip)$'      # daemon artifacts; SHA256SUMS handled explicitly

mkdir -p "$DEST"
exec 9>"$DEST/.lock"; flock -n 9 || { echo "another sync is running"; exit 0; }

api="$(curl -fsSL --max-time 30 -H 'Accept: application/vnd.github+json' -H 'User-Agent: cc-pocket-mirror' \
  "https://api.github.com/repos/$REPO/releases/latest")"
tag="$(jq -r '.tag_name // empty' <<<"$api")"
[ -n "$tag" ] || { echo "no tag_name in the GitHub API response"; exit 1; }
ver="${tag#v}"
vdir="$DEST/$tag"

tmp="$(mktemp -d "$DEST/.sync.XXXXXX")"; trap 'rm -rf "$tmp"' EXIT

# SHA256SUMS is re-fetched EVERY run: a desktop-only hotfix regenerates it under the same tag, and
# serving a stale manifest next to refreshed GitHub assets would fail client-side verification.
sums_url="$(jq -r '.assets[] | select(.name=="SHA256SUMS") | .browser_download_url' <<<"$api" | head -1)"
[ -n "$sums_url" ] || { echo "release $tag has no SHA256SUMS — refusing to mirror unverifiable assets"; exit 1; }
curl -fsSL --max-time 60 "$sums_url" -o "$tmp/SHA256SUMS"

mkdir -p "$vdir"
while IFS=$'\t' read -r name url; do
  [[ "$name" =~ $MIRROR_RE ]] || continue
  expected="$(awk -v a="$name" '$2==a || $2=="*"a {print tolower($1)}' "$tmp/SHA256SUMS" | head -1)"
  [ -n "$expected" ] || { echo "skip $name (no SHA256SUMS entry)"; continue; }
  if [ -f "$vdir/$name" ] && [ "$(sha256sum "$vdir/$name" | awk '{print tolower($1)}')" = "$expected" ]; then
    continue  # already mirrored and still matches the (possibly refreshed) sums
  fi
  echo "fetching $name"
  curl -fSL --max-time 900 "$url" -o "$tmp/$name"
  actual="$(sha256sum "$tmp/$name" | awk '{print tolower($1)}')"
  [ "$actual" = "$expected" ] || { echo "checksum mismatch for $name (expected $expected got $actual)"; exit 1; }
  mv -f "$tmp/$name" "$vdir/$name"
done < <(jq -r '.assets[] | [.name, .browser_download_url] | @tsv' <<<"$api")
mv -f "$tmp/SHA256SUMS" "$vdir/SHA256SUMS"

# latest.json: complete asset map (mirrored → this host, the rest → GitHub), swapped in atomically
jq -n --arg ver "$ver" --arg tag "$tag" --arg base "$BASE_URL" --arg re "$MIRROR_RE" \
  --argjson assets "$(jq '[.assets[] | {name, url: .browser_download_url}]' <<<"$api")" '
  {version: $ver,
   assets: ($assets | map(
     if (.name | test($re)) or .name == "SHA256SUMS"
     then {(.name): ($base + "/" + $tag + "/" + .name)}
     else {(.name): .url} end) | add)}' > "$tmp/latest.json"
# sanity: every asset latest.json claims we host must actually be on disk (a skipped/missing file
# must degrade to the GitHub URL path, never to a 404 on the mirror)
jq -r --arg base "$BASE_URL/$tag/" '.assets[] | select(startswith($base)) | sub($base; "")' "$tmp/latest.json" |
  while read -r f; do [ -f "$vdir/$f" ] || { echo "latest.json references missing $f"; exit 1; }; done
mv -f "$tmp/latest.json" "$DEST/latest.json"

# the installers themselves (replace only on a successful, sane-looking fetch)
for s in install.sh install.ps1; do
  if curl -fsSL --max-time 30 "https://raw.githubusercontent.com/$REPO/main/scripts/$s" -o "$tmp/$s" \
      && grep -q "cc-pocket" "$tmp/$s"; then
    mv -f "$tmp/$s" "$DEST/$s"
  else
    echo "warn: could not refresh $s (keeping the current copy)"
  fi
done

# prune version dirs beyond the newest KEEP (never the one latest.json points at)
ls -1d "$DEST"/v*/ 2>/dev/null | sed 's:/$::' | sort -V | head -n -"$KEEP" | while read -r d; do
  [ "$(basename "$d")" = "$tag" ] && continue
  echo "pruning $(basename "$d")"
  rm -rf "$d"
done

echo "mirror in sync: $tag"
