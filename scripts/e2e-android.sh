#!/usr/bin/env bash
#
# Run the Maestro flows against a connected device or emulator.
#
#   scripts/e2e-android.sh                 # smoke only — needs no credentials
#   scripts/e2e-android.sh --all           # every flow (needs .env)
#   scripts/e2e-android.sh --build         # rebuild + reinstall the debug APK first
#   scripts/e2e-android.sh flows/02-send-text.yaml
#
# Credentials come from .env, which is gitignored and never printed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APK="android/app/build/outputs/apk/sideload/debug/app-sideload-debug.apk"
BUILD=0
TARGET="e2e/maestro/flows/00-smoke-launch.yaml"

for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --all) TARGET="e2e/maestro/flows" ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) TARGET="$arg" ;;
  esac
done

# `set -a` exports every assignment so Maestro sees them as ${VAR}. Values are
# never echoed — the file holds a private key.
if [ -f .env ]; then
  # Parsed line by line rather than sourced: a value may legitimately contain
  # spaces (a mnemonic phrase is twelve words), and `.` would try to run it as
  # a command. Nothing from .env is ever echoed.
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ''|'#'*) continue ;;
    esac
    key=${line%%=*}
    value=${line#*=}
    case "$key" in
      *[!A-Za-z0-9_]*|'') continue ;;
    esac
    # Strip one layer of surrounding quotes if present.
    case "$value" in
      \"*\") value=${value#\"}; value=${value%\"} ;;
      "'"*"'") value=${value#"'"}; value=${value%"'"} ;;
    esac
    export "$key=$value"
  done < .env

  # Accept the shorter TEST1/TEST2 names as aliases so an existing .env works
  # without being rewritten.
  : "${MAESTRO_E2E_PRIVATE_KEY:=${TEST1:-}}"
  : "${MAESTRO_E2E_PRIVATE_KEY_B:=${TEST2:-}}"
  export MAESTRO_E2E_PRIVATE_KEY MAESTRO_E2E_PRIVATE_KEY_B

  if [ -n "${MAESTRO_E2E_PRIVATE_KEY:-}" ]; then
    echo "[e2e] loaded .env (account key present)"
  else
    echo "[e2e] loaded .env (no account key — credentialed flows will fail)"
  fi
else
  echo "[e2e] no .env — only credential-free flows will pass"
fi

export MAESTRO_CLI_NO_ANALYTICS=1
export PATH="$HOME/.maestro/bin:$PATH"

command -v maestro >/dev/null || {
  echo "[e2e] maestro not found. Install: curl -Ls https://get.maestro.mobile.dev | bash" >&2
  exit 1
}

# Pick a device explicitly. Bare `adb get-state`/`adb install` fail with "more
# than one device" as soon as a second emulator is running, which is the normal
# state here because the call scenario needs two.
DEVICE="${DEVICE:-$(adb devices | awk '$2 == "device" { print $1; exit }')}"
[ -n "$DEVICE" ] || {
  echo "[e2e] no device. Start one: emulator -avd callA" >&2
  exit 1
}
echo "[e2e] using $DEVICE"

if [ "$BUILD" = "1" ]; then
  echo "[e2e] building debug APK"
  npm run cap:build
  (cd android && ./gradlew assembleSideloadDebug)
fi

[ -f "$APK" ] || {
  echo "[e2e] $APK missing — run with --build" >&2
  exit 1
}

echo "[e2e] installing APK"
adb -s "$DEVICE" install -r "$APK" >/dev/null

echo "[e2e] running $TARGET"
maestro --device "$DEVICE" test "$TARGET"
