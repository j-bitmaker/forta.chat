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
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
  echo "[e2e] loaded .env"
else
  echo "[e2e] no .env — only credential-free flows will pass"
fi

export MAESTRO_CLI_NO_ANALYTICS=1
export PATH="$HOME/.maestro/bin:$PATH"

command -v maestro >/dev/null || {
  echo "[e2e] maestro not found. Install: curl -Ls https://get.maestro.mobile.dev | bash" >&2
  exit 1
}

adb get-state >/dev/null 2>&1 || {
  echo "[e2e] no device. Start one: emulator -avd callA" >&2
  exit 1
}

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
adb install -r "$APK" >/dev/null

echo "[e2e] running $TARGET"
maestro test "$TARGET"
