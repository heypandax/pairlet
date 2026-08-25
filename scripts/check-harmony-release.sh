#!/usr/bin/env bash
# Static release-contract check: no DevEco installation or signing material required.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE_SCRIPT="$ROOT/scripts/release-harmony.sh"
BUILD_SCRIPT="$ROOT/scripts/build-harmony.sh"
BUILD_PROFILE="$ROOT/harmony/entry/build-profile.json5"
RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"

fail() { echo "Harmony release contract failed: $*"; exit 1; }

grep -Fq 'readonly RELEASE_BUILD_PROPERTY="buildMode=release"' "$RELEASE_SCRIPT" ||
  fail "release buildMode property is not pinned"
MODE_USES=$(grep -F -c -- '-p "$RELEASE_BUILD_PROPERTY"' "$RELEASE_SCRIPT" || true)
[ "$MODE_USES" -eq 2 ] ||
  fail "release buildMode must be passed exactly twice (assembleHap + assembleApp), found $MODE_USES"
grep -Fq -- '--no-daemon "$@"' "$BUILD_SCRIPT" ||
  fail "build-harmony.sh no longer forwards optional hvigor arguments"
grep -Fq 'export JAVA_HOME="$DEVECO/jbr/Contents/Home"' "$RELEASE_SCRIPT" ||
  fail "release-harmony.sh does not export DevEco JBR for the parent hvigor process"
grep -Fq 'export PATH="$JAVA_HOME/bin:$DEVECO/tools/node/bin:$DEVECO/tools/ohpm/bin:$PATH"' "$RELEASE_SCRIPT" ||
  fail "release-harmony.sh does not export DevEco node/ohpm for assembleApp"
grep -q '"name"[[:space:]]*:[[:space:]]*"release"' "$BUILD_PROFILE" ||
  fail "entry build profile does not define release"

METADATA_CHECKS=$(grep -F -c 'assert_package_metadata "$UNSIGNED' "$RELEASE_SCRIPT" || true)
[ "$METADATA_CHECKS" -eq 2 ] ||
  fail "unsigned HAP + APP metadata must both be checked, found $METADATA_CHECKS"
CERTIFICATE_CHECKS=$(grep -F -c 'assert_certificate_matches "$VERIFY_DIR/' "$RELEASE_SCRIPT" || true)
[ "$CERTIFICATE_CHECKS" -eq 2 ] ||
  fail "signed HAP + APP certificates must both be checked, found $CERTIFICATE_CHECKS"
grep -Fq 'summary.app.bundleName' "$RELEASE_SCRIPT" || fail "bundleName is not checked from pack.info"
grep -Fq 'summary.app.version.name' "$RELEASE_SCRIPT" || fail "versionName is not checked from pack.info"
grep -Fq 'summary.app.version.code' "$RELEASE_SCRIPT" || fail "versionCode is not checked from pack.info"
grep -Fq -- '-sha256 -fingerprint' "$RELEASE_SCRIPT" || fail "certificate SHA-256 fingerprint is not checked"
grep -Fq 'trap - EXIT' "$RELEASE_SCRIPT" || fail "EXIT cleanup can mask the original failure status"
grep -Fq 'exit "$status"' "$RELEASE_SCRIPT" || fail "EXIT cleanup does not preserve failure status"
grep -Fq 'RELEASE_SUCCEEDED=1' "$RELEASE_SCRIPT" || fail "premature exits can be reported as successful"

HARMONY_JOB=$(awk '/^  harmony:/{copy=1} /^  bump-scoop:/{copy=0} copy' "$RELEASE_WORKFLOW")
[ -n "$HARMONY_JOB" ] || fail "Harmony workflow job not found"
grep -Fq 'environment: harmony-release' <<< "$HARMONY_JOB" ||
  fail "Harmony job is not protected by the harmony-release environment"
grep -Fq 'RELEASE_VERSION: ${{ inputs.version }}' <<< "$HARMONY_JOB" ||
  fail "workflow input is not captured in RELEASE_VERSION"
INPUT_USES=$(grep -F -c '${{ inputs.version }}' <<< "$HARMONY_JOB" || true)
[ "$INPUT_USES" -eq 1 ] ||
  fail "Harmony job must use inputs.version only once in env, found $INPUT_USES"
grep -Fq 'id: release_target' <<< "$HARMONY_JOB" || fail "release tag resolver step is missing"
grep -Fq '[[ "$RELEASE_VERSION" =~ ^(0|[1-9][0-9]*)\.' <<< "$HARMONY_JOB" ||
  fail "version is not validated before checkout"
grep -Fq '[ "$GITHUB_REF" = "$TAG_REF" ]' <<< "$HARMONY_JOB" || fail "workflow dispatch ref is not pinned to the tag"
grep -Fq '[ "$GITHUB_REF_PROTECTED" = "true" ]' <<< "$HARMONY_JOB" || fail "release tag protection is not enforced"
grep -Fq 'TAG_REF="refs/tags/${TAG}"' <<< "$HARMONY_JOB" || fail "release tag ref is not pinned"
grep -Fq -- '--json targetCommitish' <<< "$HARMONY_JOB" || fail "Release targetCommitish is not checked"
grep -Fq 'ref: ${{ steps.release_target.outputs.tag_ref }}' <<< "$HARMONY_JOB" ||
  fail "checkout is not pinned to the validated release tag"
grep -Eq 'uses: actions/checkout@[0-9a-f]{40}$' <<< "$HARMONY_JOB" ||
  fail "Harmony signing checkout must be pinned to a full commit SHA"
grep -Fq 'Release tag moved during the job; refusing to upload' <<< "$HARMONY_JOB" ||
  fail "release tag is not revalidated before upload"
grep -Fq 'group: release-${{ github.repository }}-${{ inputs.version }}' "$RELEASE_WORKFLOW" ||
  fail "same-version release dispatches are not serialized"
grep -Fq 'cancel-in-progress: false' "$RELEASE_WORKFLOW" ||
  fail "same-version release serialization may cancel an in-flight signing run"

echo "Harmony release contract OK: trusted tag + release metadata + signed HAP/APP identity"
