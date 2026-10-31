#!/usr/bin/env bash
# Real-user scenario runner (method C). One class at a time, evidence per run.
# The app's own VPN stays untouched: nothing here stops, installs or reboots any other package.
set -euo pipefail
ADB="${ADB_PATH:-/c/Users/edmon/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
PKG="com.aistudio.aichatexporter"
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

pick_serial() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then echo "$ANDROID_SERIAL"; return; fi
  if "$ADB" devices | grep -q '^emulator-5554[[:space:]]'; then echo "emulator-5554"; return; fi
  # Fall back to the named phone only; never the UFI modem on this host.
  if "$ADB" devices | grep -q 'R58X30GS9QJ'; then echo "R58X30GS9QJ"; return; fi
  echo ""
}
S="$(pick_serial)"
[ -n "$S" ] || { echo "no approved device attached"; exit 2; }
RAW="docs/reports/raw"; mkdir -p "$RAW"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; SHA="$(git rev-parse --short HEAD)"

gstate() { "$ADB" -s "$S" get-state 2>/dev/null | tr -d '\r'; }
[ "$(gstate)" = "device" ] || { echo "device $S not ready (state: $(gstate))"; exit 2; }

./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest
APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[ -f "$APK" ] && [ -f "$TEST_APK" ] || { echo "APKs missing"; exit 3; }
"$ADB" -s "$S" install -r -t "$APK" >/dev/null
"$ADB" -s "$S" install -r -t "$TEST_APK" >/dev/null

start_app() { MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell am start -n "$PKG/com.example.MainActivity" >/dev/null 2>&1 || true; sleep 2; }
dump_ui() { # $1 tag
  MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell uiautomator dump /sdcard/"$1".xml >/dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell cat /sdcard/"$1".xml 2>/dev/null > "$RAW/$1-$SHA-$STAMP.xml" || true
  MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell rm -f /sdcard/"$1".xml >/dev/null 2>&1 || true
}

CLASSES=(
  "com.example.RealUserScenarioPhase02ImportTest"
  "com.example.RealUserScenarioPhase35ExportTest"
  "com.example.RealUserScenarioPhase69LifecycleTest"
)
if [ $# -ge 1 ]; then
  case "$1" in
    02*|Phase02*) CLASSES=("com.example.RealUserScenarioPhase02ImportTest") ;;
    35*|Phase35*) CLASSES=("com.example.RealUserScenarioPhase35ExportTest") ;;
    69*|Phase69*) CLASSES=("com.example.RealUserScenarioPhase69LifecycleTest") ;;
    *) CLASSES=("$1") ;;
  esac
fi

start_app
for CLASS in "${CLASSES[@]}"; do
  TAG="${CLASS##*.}"; OUT="$RAW/${TAG}-${SHA}-${STAMP}.txt"
  echo "=== $CLASS ==="
  "$ADB" -s "$S" logcat -c -b crash >/dev/null 2>&1 || true
  MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell am instrument -w -e class "$CLASS" "$RUNNER" >"$OUT" 2>&1 || true
  "$ADB" -s "$S" logcat -d -b crash -t 400 -v brief > "$RAW/${TAG}-${SHA}-${STAMP}-crash.log" 2>&1 || true
  if grep -qE "^OK \(|INSTRUMENTATION_RESULT: stream=OK" "$OUT"; then
    echo "green $CLASS  $(grep -oE '^OK \(.*\)' "$OUT" | tail -1)"
  else
    echo "NOT GREEN $CLASS — tail:"; tail -12 "$OUT" || true
  fi

  # Phase 8, kill half: this is the only place the app is force-stopped, and it happens from
  # OUTSIDE the instrumentation process so the runner is not the thing being killed.
  if [ "$CLASS" = "com.example.RealUserScenarioPhase69LifecycleTest" ]; then
    MSYS_NO_PATHCONV=1 "$ADB" -s "$S" shell am force-stop "$PKG" >/dev/null 2>&1 || true
    sleep 1
    start_app
    dump_ui "phase8_after_kill"
  fi
  echo ""
done
echo "evidence: $RAW  (commit $SHA, device $S)"
