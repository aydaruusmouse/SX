#!/usr/bin/env bash
# Install debug build, open the app, then stream logcat for this process.
# Stop logging with Ctrl+C (not Ctrl+Z).

set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SDK=""
if [[ -f local.properties ]] && grep -q '^sdk.dir=' local.properties; then
  SDK="$(grep '^sdk.dir=' local.properties | cut -d= -f2-)"
fi
if [[ -z "${SDK:-}" ]]; then
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
fi
export PATH="$SDK/platform-tools:$PATH"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install Platform-Tools or set sdk.dir in local.properties / ANDROID_HOME."
  exit 1
fi

echo ">>> Installing…"
./gradlew installDebug

echo ">>> Launching MainActivity…"
adb shell am start -n com.sarif.auto/.MainActivity

sleep 1
PID="$(adb shell pidof com.sarif.auto 2>/dev/null | tr -d '\r' || true)"
adb logcat -c

if [[ -n "$PID" ]]; then
  echo ">>> logcat for com.sarif.auto (pid $PID). Ctrl+C to stop."
  adb logcat --pid="$PID"
else
  echo ">>> Could not read pid yet; streaming logcat filtered by 'sarif'. Ctrl+C to stop."
  adb logcat | grep -i --line-buffered sarif
fi
