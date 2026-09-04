#!/usr/bin/env bash
#
# Two-device call scenario: signs both emulators in, then runs the callee and
# caller flows in parallel against a live Matrix exchange.
#
#   scripts/e2e-call.sh                 # full run: login both, then call
#   scripts/e2e-call.sh --skip-login    # devices already signed in
#
# Emulator audio is synthetic, so this proves signalling, the incoming-call UI,
# answer and teardown — NOT that anyone can hear anything. Audio faults are
# vendor-firmware defects and need real hardware.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEVICE_A="${DEVICE_A:-emulator-5554}"
DEVICE_B="${DEVICE_B:-emulator-5556}"
APK="android/app/build/outputs/apk/sideload/debug/app-sideload-debug.apk"
SKIP_LOGIN=0
[ "${1:-}" = "--skip-login" ] && SKIP_LOGIN=1

if [ -f .env ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    key=${line%%=*}; value=${line#*=}
    case "$key" in *[!A-Za-z0-9_]*|'') continue ;; esac
    export "$key=$value"
  done < .env
fi

: "${MAESTRO_E2E_PRIVATE_KEY:=${TEST1:-}}"
: "${MAESTRO_E2E_PRIVATE_KEY_B:=${TEST2:-}}"

[ -n "${MAESTRO_E2E_PRIVATE_KEY:-}" ] && [ -n "${MAESTRO_E2E_PRIVATE_KEY_B:-}" ] || {
  echo "[call] need two account keys (TEST1/TEST2 or MAESTRO_E2E_PRIVATE_KEY*) in .env" >&2
  exit 1
}
[ -n "${MAESTRO_E2E_PEER_NAME:-}" ] || {
  echo "[call] set MAESTRO_E2E_PEER_NAME to the display name device A sees for device B" >&2
  exit 1
}

export MAESTRO_CLI_NO_ANALYTICS=1
export PATH="$HOME/.maestro/bin:$HOME/Library/Android/sdk/platform-tools:$PATH"

for d in "$DEVICE_A" "$DEVICE_B"; do
  adb -s "$d" get-state >/dev/null 2>&1 || { echo "[call] $d not connected" >&2; exit 1; }
  echo "[call] installing on $d"
  adb -s "$d" install -r "$APK" >/dev/null

  # Required, not cosmetic. A headless emulator with a dark screen lets Android
  # freeze the app process ("Sending oneway calls to frozen process" in logcat);
  # the Matrix sync then stops and the invite never arrives, which looks exactly
  # like the "incoming call never rings" bug. Keeping the screen on is what
  # makes the callee reachable without FCM.
  adb -s "$d" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb -s "$d" shell svc power stayon true >/dev/null 2>&1 || true
done

if [ "$SKIP_LOGIN" = "0" ]; then
  echo "[call] signing in A"
  MAESTRO_E2E_PRIVATE_KEY="$MAESTRO_E2E_PRIVATE_KEY" \
    maestro --device "$DEVICE_A" test e2e/maestro/flows/03-login.yaml
  echo "[call] signing in B"
  MAESTRO_E2E_PRIVATE_KEY="$MAESTRO_E2E_PRIVATE_KEY_B" \
    maestro --device "$DEVICE_B" test e2e/maestro/flows/03-login.yaml
fi

# The callee starts first and waits: an invite that lands before Maestro is
# watching would be missed, and the ring times out in about a minute.
echo "[call] arming callee on $DEVICE_B"
maestro --device "$DEVICE_B" test e2e/maestro/flows/05-call-answer.yaml &
CALLEE_PID=$!

# The callee's sync needs to be live before the invite is sent, otherwise the
# event lands while the client is still catching up and is never surfaced.
sleep 25

echo "[call] placing call from $DEVICE_A"
set +e
maestro --device "$DEVICE_A" test e2e/maestro/flows/04-call-place.yaml
CALLER_RC=$?
wait "$CALLEE_PID"
CALLEE_RC=$?
set -e

echo "[call] caller rc=$CALLER_RC callee rc=$CALLEE_RC"
[ "$CALLER_RC" -eq 0 ] && [ "$CALLEE_RC" -eq 0 ]
