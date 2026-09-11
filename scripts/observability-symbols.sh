#!/usr/bin/env bash
# Validate an archived iOS application against its exact dSYM before optional upload.
# No recursive project scanning or source bundle upload. Token stays in the environment.
set -euo pipefail
umask 077
ARCHIVE="${1:?usage: bash scripts/observability-symbols.sh archive.xcarchive [--upload]}"
MODE="${2:---check}"
[[ "$MODE" == --check || "$MODE" == --upload ]] || exit 64
[[ -d "$ARCHIVE/Products/Applications" && -d "$ARCHIVE/dSYMs" ]] || exit 65
command -v dwarfdump >/dev/null
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
shopt -s nullglob
apps=("$ARCHIVE"/Products/Applications/*.app)
[[ ${#apps[@]} == 1 ]] || { echo 'archive must contain one application'; exit 65; }
EXECUTABLE=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "${apps[0]}/Info.plist")
[[ -n "$EXECUTABLE" && "$EXECUTABLE" != */* && "$EXECUTABLE" != . && "$EXECUTABLE" != .. ]] || exit 65
APP_BINARY="${apps[0]}/$EXECUTABLE"
APP_DSYM="$ARCHIVE/dSYMs/$(basename "${apps[0]}").dSYM"
[[ -f "$APP_BINARY" && -d "$APP_DSYM" ]] || exit 65
BINARY_UUIDS=$(dwarfdump --uuid "$APP_BINARY" | awk '{print $2, $3}' | sort)
SYMBOL_UUIDS=$(dwarfdump --uuid "$APP_DSYM" | awk '{print $2, $3}' | sort)
[[ -n "$BINARY_UUIDS" && "$BINARY_UUIDS" == "$SYMBOL_UUIDS" ]] || { echo 'App/dSYM UUID mismatch'; exit 65; }
printf 'App UUIDs matched:\n%s\n' "$BINARY_UUIDS"
for framework in "${apps[0]}"/Frameworks/*.framework; do
    name=$(basename "$framework" .framework)
    symbols="$ARCHIVE/dSYMs/$name.framework.dSYM"
    if [[ ! -d "$symbols" ]]; then
        # Xcode can replace a statically linked framework's executable with an empty dylib.
        # Its code belongs to the App dSYM. Identify the actual binary, never exempt an SDK by name.
        if python3 "$SCRIPT_DIR/observability-codeless-framework.py" "$framework/$name"; then
            echo "Codeless Xcode framework stub: $name (no separate code to symbolicate)"
            continue
        fi
        echo "embedded framework missing dSYM: $name"; exit 65
    fi
    a=$(dwarfdump --uuid "$framework/$name" | awk '{print $2, $3}' | sort)
    b=$(dwarfdump --uuid "$symbols" | awk '{print $2, $3}' | sort)
    [[ -n "$a" && "$a" == "$b" ]] || { echo "framework UUID mismatch: $name"; exit 65; }
done
echo 'Static Kotlin code may live in the App binary. Verify its source frames in the received event.'
if [[ "$MODE" == --upload ]]; then
    : "${SENTRY_AUTH_TOKEN:?set a scoped CI upload token in the environment}"
    command -v sentry-cli >/dev/null
    sentry-cli debug-files upload -o pairlet -p pairlet-ios --wait "$ARCHIVE/dSYMs"
    echo 'Server symbol processing finished; actual symbolicated crash acceptance is still required.'
fi
