#!/usr/bin/env bash
set -euo pipefail
S="${ANDROID_SERIAL:-emulator-5554}"
ADB="${ADB_PATH:-/c/Users/edmon/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
RAW="docs/reports/raw"; mkdir -p "$RAW"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"; SHA="$(git rev-parse --short HEAD)"

# 0. Preflight
"$ADB" -s "$S" get-state | grep -q device || { echo "device $S not ready"; exit 2; }
# 1. Build
./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest
APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[ -f "$APK" ] && [ -f "$TEST_APK" ] || { echo "APKs missing"; exit 3; }
# 2. Install
"$ADB" -s "$S" install -r -t "$APK"
"$ADB" -s "$S" install -r -t "$TEST_APK"
# 3. Run each class
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
for CLASS in "${CLASSES[@]}"; do
  TAG="${CLASS##*.}"; OUT="$RAW/${TAG}-${SHA}-${STAMP}.txt"
  "$ADB" -s "$S" logcat -c -b crash >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell am instrument -w -e class "$CLASS" com.aistudio.aichatexporter.kxrtva.test/androidx.test.runner.AndroidJUnitRunner >"$OUT" 2>&1 || true
  "$ADB" -s "$S" logcat -d -b crash -t 400 -v brief > "$RAW/${TAG}-${SHA}-${STAMP}-crash.log" 2>&1 || true
  grep -qE "^OK \(|INSTRUMENTATION_RESULT: stream=OK" "$OUT" && echo "green $CLASS" || { echo "NOT GREEN $CLASS — tail:"; tail -20 "$OUT" || true; }
done
