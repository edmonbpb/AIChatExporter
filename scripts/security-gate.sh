#!/usr/bin/env bash
# The hand-out check. Everything here is measured on the built artifact, because the source being
# clean is not what a user installs. Run after a release build and before anything is sent:
#   bash scripts/security-gate.sh app/build/outputs/apk/release/app-release.apk
# Exit 0 means every check passed; exit 1 names the ones that did not.
set -uo pipefail

APK="${1:-}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "FAIL: give an existing apk path: security-gate.sh <path-to-apk>"
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_TOOLS="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/build-tools/36.0.0"
AAPT2="$BUILD_TOOLS/aapt2.exe"
[ -x "$AAPT2" ] || AAPT2="$BUILD_TOOLS/aapt2"

fails=0
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() { echo "FAIL: $1"; fails=$((fails + 1)); }
pass() { echo "PASS: $1"; }

unzip -o -q "$APK" -d "$work" || { echo "FAIL: the artifact cannot be opened"; exit 1; }

# 1. No AI key may ride in the shipped code.
hits=$(timeout 120 grep -a -o -E 'AIzaSy[A-Za-z0-9_-]{30,}' "$work"/classes*.dex 2>/dev/null | wc -l)
if [ "$hits" -eq 0 ]; then
  pass "no AI key in any dex ($(ls "$work"/classes*.dex | wc -l) files searched)"
else
  fail "$hits AI-key-shaped strings found in the dex files; build with -PpublicBuild=true"
fi

# 2. The account service must actually be configured, or sign-up only ever answers that it is not.
if [ -f "$ROOT/app/google-services.json" ]; then
  project_id="$(timeout 20 awk -F'"' '/"project_id"/ {print $4; exit}' "$ROOT/app/google-services.json")"
  if [ -n "$project_id" ] && timeout 60 grep -a -q "$project_id" "$work/resources.arsc"; then
    pass "the account configuration is inside ($project_id)"
  else
    fail "no trace of $project_id in the artifact: this build carries no account service"
  fi
else
  fail "app/google-services.json is not on this machine, so the artifact cannot carry it"
fi

# 3. A release handed out must not be debuggable: run-as and the debugger reach the app's files.
dump="$(timeout 120 "$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null)"
if printf '%s' "$dump" | grep -q 'android:debuggable'; then
  fail "the artifact sets android:debuggable"
else
  pass "the artifact is not debuggable"
fi

# 4. No cleartext. A scheme= or usesCleartextTraffic=true means text can leave unencrypted.
if printf '%s' "$dump" | grep -q 'android:usesCleartextTraffic'; then
  fail "the artifact sets android:usesCleartextTraffic"
else
  pass "the artifact sets no cleartext permission"
fi

# 5. The version number on disk is the one in the build file.
code_in_file="$(timeout 20 grep -oE 'versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | head -1 | grep -oE '[0-9]+')"
code_in_apk="$(timeout 120 "$AAPT2" dump badging "$APK" 2>/dev/null | timeout 20 grep -oE "versionCode='[0-9]+'" | grep -oE '[0-9]+')"
if [ -n "$code_in_file" ] && [ "$code_in_file" = "$code_in_apk" ]; then
  pass "versionCode $code_in_apk matches the build file"
else
  fail "versionCode in the artifact is '$code_in_apk', the build file says '$code_in_file'"
fi

# 6. Data may not be extracted by the system backup agent.
if printf '%s' "$dump" | grep -q 'android:allowBackup=.true'; then
  fail "the artifact allows backup"
else
  pass "the artifact disallows backup"
fi

if [ "$fails" -gt 0 ]; then
  echo "RESULT: FAIL ($fails check(s))"
  exit 1
fi
echo "RESULT: PASS"
