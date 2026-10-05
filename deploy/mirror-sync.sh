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
#                       + release-manifest.json / .sig copied byte for byte when the release is signed
#   dl/install.sh|.ps1  the one-line installers (raw.githubusercontent is slow/blocked in CN too)
# latest.json is written LAST and atomically — it never references a half-mirrored version.
#
# Signed releases (docs/RELEASE.md「更新包签名」): the mirror holds no key and verifies no signature — the
# clients do. It only carries the manifest + signature unchanged, refuses a half-signed release (one file
# without the other), and refuses to serve a daemon artifact whose SHA256SUMS hash disagrees with the
# manifest. MIRROR_REQUIRE_SIGNATURE=1 (set once signing is enforced) also refuses an unsigned release;
# the default 0 keeps mirroring unsigned releases exactly as before.
set -euo pipefail

REPO="heypandax/cc-pocket"
DEST="${MIRROR_DEST:-/var/www/cc-pocket-dl}"          # overridable for the offline test only
BASE_URL="https://pocket.ark-nexus.cc/dl"
KEEP=2                                                # version dirs to retain
MIRROR_RE='^cc-pocket-daemon-.*\.(tar\.gz|zip)$'      # daemon artifacts; SHA256SUMS handled explicitly
MANIFEST="release-manifest.json"
MANIFEST_SIG="release-manifest.json.sig"
MANIFEST_SCHEMA="pairlet-release-manifest/1"
REQUIRE_SIGNATURE="${MIRROR_REQUIRE_SIGNATURE:-0}"

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

# The signed manifest is re-fetched every run too (a hotfix re-signs it under the same tag).
manifest_url="$(jq -r --arg n "$MANIFEST" '.assets[] | select(.name==$n) | .browser_download_url' <<<"$api" | head -1)"
sig_url="$(jq -r --arg n "$MANIFEST_SIG" '.assets[] | select(.name==$n) | .browser_download_url' <<<"$api" | head -1)"
signed=0
if [ -n "$manifest_url" ] && [ -n "$sig_url" ]; then
  curl -fsSL --max-time 60 "$manifest_url" -o "$tmp/$MANIFEST"
  curl -fsSL --max-time 60 "$sig_url" -o "$tmp/$MANIFEST_SIG"
  jq -e --arg v "$ver" --arg s "$MANIFEST_SCHEMA" '.schema == $s and .version == $v and (.assets | type == "object")' \
    "$tmp/$MANIFEST" >/dev/null 2>&1 ||
    { echo "release $tag: $MANIFEST is not a $MANIFEST_SCHEMA manifest for $ver — refusing to mirror"; exit 1; }
  [ -s "$tmp/$MANIFEST_SIG" ] || { echo "release $tag: $MANIFEST_SIG is empty — refusing to mirror"; exit 1; }
  signed=1
elif [ -n "$manifest_url" ] || [ -n "$sig_url" ]; then
  echo "release $tag has only one of $MANIFEST / $MANIFEST_SIG — refusing to mirror a half-signed release"; exit 1
elif [ "$REQUIRE_SIGNATURE" = "1" ]; then
  echo "release $tag has no $MANIFEST / $MANIFEST_SIG and MIRROR_REQUIRE_SIGNATURE=1 — refusing to mirror an unsigned release"; exit 1
else
  echo "note: release $tag is unsigned (no $MANIFEST) — mirrored without one, as before"
fi

mkdir -p "$vdir"
while IFS=$'\t' read -r name url; do
  [[ "$name" =~ $MIRROR_RE ]] || continue
  expected="$(awk -v a="$name" '$2==a || $2=="*"a {print tolower($1)}' "$tmp/SHA256SUMS" | head -1)"
  [ -n "$expected" ] || { echo "skip $name (no SHA256SUMS entry)"; continue; }
  if [ "$signed" = 1 ]; then
    # never serve bytes the signed manifest does not vouch for (e.g. SUMS refreshed before a re-sign landed)
    signed_sha="$(jq -r --arg a "$name" '.assets[$a].sha256 // empty' "$tmp/$MANIFEST")"
    [ "$signed_sha" = "$expected" ] ||
      { echo "release $tag: $name is ${signed_sha:-not listed} in $MANIFEST but $expected in SHA256SUMS — refusing to mirror"; exit 1; }
  fi
  if [ -f "$vdir/$name" ] && [ "$(sha256sum "$vdir/$name" | awk '{print tolower($1)}')" = "$expected" ]; then
    continue  # already mirrored and still matches the (possibly refreshed) sums
  fi
  echo "fetching $name"
  # Resume across runs: GitHub from this box can crawl at ~100KB/s, so a ~110MB asset does not fit in
  # one attempt. The partial lives OUTSIDE $tmp (which the EXIT trap wipes) so the next timer run
  # continues where this one stopped instead of starting over; it is verified before going live.
  mkdir -p "$DEST/.partial"; part="$DEST/.partial/$tag-$name"
  if ! curl -fSL -C - --retry 3 --retry-delay 5 --retry-all-errors \
       --speed-limit 10240 --speed-time 120 --max-time 1500 "$url" -o "$part"; then
    echo "download interrupted for $name — partial kept at $part for the next run"; exit 28
  fi
  actual="$(sha256sum "$part" | awk '{print tolower($1)}')"
  [ "$actual" = "$expected" ] || { echo "checksum mismatch for $name (expected $expected got $actual)"; rm -f "$part"; exit 1; }
  mv -f "$part" "$vdir/$name"
done < <(jq -r '.assets[] | [.name, .browser_download_url] | @tsv' <<<"$api")
mv -f "$tmp/SHA256SUMS" "$vdir/SHA256SUMS"
if [ "$signed" = 1 ]; then
  mv -f "$tmp/$MANIFEST" "$vdir/$MANIFEST"
  mv -f "$tmp/$MANIFEST_SIG" "$vdir/$MANIFEST_SIG"
else
  rm -f "$vdir/$MANIFEST" "$vdir/$MANIFEST_SIG"  # never leave an old signature beside refreshed assets
fi

# latest.json: complete asset map (mirrored → this host, the rest → GitHub), swapped in atomically
jq -n --arg ver "$ver" --arg tag "$tag" --arg base "$BASE_URL" --arg re "$MIRROR_RE" \
  --arg m "$MANIFEST" --arg s "$MANIFEST_SIG" --arg signed "$signed" \
  --argjson assets "$(jq '[.assets[] | {name, url: .browser_download_url}]' <<<"$api")" '
  {version: $ver,
   assets: ($assets | map(
     if (.name | test($re)) or .name == "SHA256SUMS" or ($signed == "1" and (.name == $m or .name == $s))
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
