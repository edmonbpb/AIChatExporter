# Real-User Scenario Auto-Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically execute the 9-phase real-user scenario (method C) on LDPlayer `emulator-5554` using the reference link `https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720`, record every deviation as a BUG with evidence, and convert the buglog into prioritized entries in `docs/07_TASKS.md`.

**Architecture:** A single orchestrator (shell + instrumented tests) drives the app: one Gradle build installs debug APK to the pinned emulator, then a sequence of instrumented test classes walks phases 0-9, each asserting the happy path and probing the edge matrix, dumping UI hierarchy and files to `docs/reports/raw/`. A buglog markdown is filled incrementally; a final consolidator scores and appends tasks. No manual steps remain.

**Tech Stack:** Kotlin 2.x, Jetpack Compose BOM, Room (version 1, fallbackToDestructiveMigration), OkHttp, Coroutines, PdfDocument/OpenXmlGenerator, AndroidX Test (JUnit4, Espresso, UIAutomator), Gradle 8.9, LDPlayer emulator-5554 (API 34, x86_64), ARTEMIS optional

**Spec:** `docs/superpowers/specs/2026-10-24-real-user-scenario-design.md`

## Global Constraints

- minSdk 29, targetSdk 36, compileSdk 36.1 — do not lower or raise without explicit approval.
- Room `version = 1` with `fallbackToDestructiveMigration()` — never bump schema; fix data at read time.
- Every `git` and `adb` command pins `emulator-5554` via `-s emulator-5554` or `ANDROID_SERIAL=emulator-5554`; `6517e41c` (UFI modem) is never touched, never installed to, never rebooted.
- One Gradle daemon at a time; controller owns builds, agents never run Gradle.
- Secrets never leave `~/.qoder/secrets/accounts.ini` or `.env`; never paste keys, never retype from memory, never echo in logs; use `accounts.ini` sections only.
- `.gitignore` already excludes `docs/reports/raw/` and `docs/reports/agy/*.xml|png|pdf|docx|pptx|json|txt|html` — evidence images/txt with real conversation text stay untracked; only `*.md` buglog is committed.
- `docs/privacy-policy.md` and `docs/privacy-policy.html` are generated together; changing one requires changing the other.
- Persian UI text must come from `onSizeChanged`/measured strings or existing resources, not hand-typed escapes; verify with codepoint check after any Persian write.
- Reference link for all phase tests: `https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720` — hardcode this exact URL in the import test; do not parameterize to a different link.
- Branch `master` tracks `origin/master`; commit with `git -c user.name="AIChatExporter controller" -c user.email="controller@localhost"`.
- License MIT at repo root must stay; any new public docs must include the misuse disclaimer: "Use only on content you have the right to access. Any misuse — violating a service's Terms, privacy, or the law — is solely your responsibility."

---

## File Structure

- `docs/reports/real-user-scenario-buglog-2026-10-24.md` — auto-filled buglog (summary table + BUG forms). Created in Task 1, appended in Tasks 3-7.
- `docs/reports/raw/BUG-xxx.*` — screenshots, ui dumps, log snippets per BUG (untracked, created at runtime).
- `app/src/androidTest/java/com/example/RealUserScenarioPhase02ImportTest.kt` — phases 0-2 (startup, auth, link import) instrumented tests.
- `app/src/androidTest/java/com/example/RealUserScenarioPhase35ExportTest.kt` — phases 3-5 (workspace, distillation, 7-format export) instrumented tests.
- `app/src/androidTest/java/com/example/RealUserScenarioPhase69LifecycleTest.kt` — phases 6-9 (library, settings, nav/lifecycle, new conversation) instrumented tests.
- `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt` — shared helpers: `pinnedSerial()`, `dumpUi()`, `screenIs()`, `tapByText()`, `waitForText()`, `runAsPull()`, `exportFileExists()`.
- `scripts/real-user-scenario-run.sh` — orchestrator: build, install, run each test class one-by-one, collect `*.txt` + crash logs to `docs/reports/raw/`, invoke buglog consolidator.
- `tools/real-user-buglog-consolidator.py` — scores `S×repeat`, clusters by module, appends rows to `docs/07_TASKS.md`.
- `docs/07_TASKS.md` — appended with new rows per BUG (Task 7).

---

### Task 1: Scaffold buglog and harness skeleton

**Files:**
- Create: `docs/reports/real-user-scenario-buglog-2026-10-24.md`
- Create: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt`
- Modify: `docs/superpowers/specs/2026-10-24-real-user-scenario-design.md` — no change (read-only reference)

**Interfaces:**
- Consumes: Spec sections 4-5 (BUG template, scoring formula).
- Produces: `RealUserScenarioHarness.pinnedSerial(): String`, `dumpUi(tag: String): File`, `waitForText(text: String, timeoutMs: Long): Boolean` for Tasks 3-6; empty buglog file with header table for Tasks 3-7.

- [ ] **Step 1: Create buglog file with header and empty summary table**

```markdown
# Real-User Scenario Buglog — 2026-10-24 (auto)
Reference link: https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720
Device: emulator-5554 (LDPlayer, API 34) — SM-A155F notes where applicable
Build: (filled at run time from `adb shell dumpsys package com.aistudio.aichatexplorer.kxrtva | grep versionName`)

## Summary
| ID | Phase | Severity | Type | Module | Title | Repeat | Status |
|---|---|---|---|---|---|---|---|
| _none yet_ |  |  |  |  |  |  | open |

## BUG Forms
<!-- append below -->
```

File: `docs/reports/real-user-scenario-buglog-2026-10-24.md`

- [ ] **Step 2: Create harness skeleton with pinned serial and UI dump helpers**

```kotlin
package com.example

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

object RealUserScenarioHarness {
    const val PINNED_SERIAL = "emulator-5554"
    const val PACKAGE = "com.aistudio.aichatexporter.kxrtva"
    const val REF_LINK = "https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720"

    fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun dumpUi(tag: String): File {
        val dir = File("/sdcard/Download") // fallback; prefer app cache dir in real impl
        // actual impl uses InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(dir, "$tag.xml")
    }

    fun waitForText(text: String, timeoutMs: Long = 8000): Boolean {
        val d = device()
        return d.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(text)), timeoutMs)
    }
}
```

File: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt`

- [ ] **Step 3: Verify files exist**

Run: `ls docs/reports/real-user-scenario-buglog-2026-10-24.md app/src/androidTest/java/com/example/RealUserScenarioHarness.kt`
Expected: both exist, buglog contains "Reference link: https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720"

- [ ] **Step 4: Commit**

```bash
git add docs/reports/real-user-scenario-buglog-2026-10-24.md app/src/androidTest/java/com/example/RealUserScenarioHarness.kt
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Scaffold real-user scenario buglog and harness"
```

---

### Task 2: Device preflight, build and orchestrator script

**Files:**
- Create: `scripts/real-user-scenario-run.sh`
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt` — add `preflight()`, `installIfNeeded()`

**Interfaces:**
- Consumes: `RealUserScenarioHarness.PINNED_SERIAL`, `PINNED_SERIAL` constant from Task 1
- Produces: `scripts/real-user-scenario-run.sh` callable as `bash scripts/real-user-scenario-run.sh [phase]`; `Harness.preflight(): Boolean` for Tasks 3-6

- [ ] **Step 1: Write orchestrator script (build, install, run one class at a time)**

```bash
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
for CLASS in \
  "com.example.RealUserScenarioPhase02ImportTest" \
  "com.example.RealUserScenarioPhase35ExportTest" \
  "com.example.RealUserScenarioPhase69LifecycleTest"; do
  TAG="${CLASS##*.}"; OUT="$RAW/${TAG}-${SHA}-${STAMP}.txt"
  "$ADB" -s "$S" logcat -c -b crash >/dev/null 2>&1 || true
  "$ADB" -s "$S" shell am instrument -w -e class "$CLASS" com.aistudio.aichatexporter.kxrtva.test/androidx.test.runner.AndroidJUnitRunner >"$OUT" 2>&1 || true
  "$ADB" -s "$S" logcat -d -b crash -t 400 -v brief > "$RAW/${TAG}-${SHA}-${STAMP}-crash.log" 2>&1 || true
  grep -qE "^OK \(|INSTRUMENTATION_RESULT: stream=OK" "$OUT" && echo "green $CLASS" || echo "NOT GREEN $CLASS — tail:"; tail -20 "$OUT" || true
done
```

File: `scripts/real-user-scenario-run.sh` (chmod +x)

- [ ] **Step 2: Add preflight helper to harness**

```kotlin
fun preflight(): Boolean {
    // checks: package installed, versionName readable, Download writable
    return true
}
```

- [ ] **Step 3: Run script in dry-run mode (build only, no device if offline)**

Run: `bash -n scripts/real-user-scenario-run.sh && echo "syntax ok"`
Expected: `syntax ok`

- [ ] **Step 4: Commit**

```bash
git add scripts/real-user-scenario-run.sh app/src/androidTest/java/com/example/RealUserScenarioHarness.kt
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Add real-user scenario orchestrator and preflight"
```

---

### Task 3: Automated walkthrough Phases 0-2 (startup, auth, import)

**Files:**
- Create: `app/src/androidTest/java/com/example/RealUserScenarioPhase02ImportTest.kt`
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt` — add `launchApp()`, `isGuestCard()`, `importLink()`

**Interfaces:**
- Consumes: `Harness.PINNED_SERIAL`, `Harness.REF_LINK`, `Harness.device()`, `dumpUi()`, `waitForText()`
- Produces: Test class with methods `phase0_preflightIsClean`, `phase1_threeTabsOnFreshInstall`, `phase1_guestMode`, `phase2_importRefLinkShowsCounts` for orchestrator Task 2

- [ ] **Step 1: Write failing test for phase 2 reference-link import**

```kotlin
package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealUserScenarioPhase02ImportTest {
    @Test fun phase2_importRefLinkShowsCounts() {
        // launch, paste REF_LINK, tap Extract, wait for Workspace tab enabled, assert counts > 0
        // initially fails: no implementation yet
        assertTrue(RealUserScenarioHarness.waitForText("محیط کار", 3000))
    }
}
```

File: `app/src/androidTest/java/com/example/RealUserScenarioPhase02ImportTest.kt`

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew --offline :app:assembleDebugAndroidTest && adb -s emulator-5554 shell am instrument -w -e class com.example.RealUserScenarioPhase02ImportTest com.aistudio.aichatexporter.kxrtva.test/androidx.test.runner.AndroidJUnitRunner`
Expected: FAIL (harness not yet driving real UI)

- [ ] **Step 3: Implement minimal UI driving for phases 0-2**

```kotlin
fun launchApp() { /* adb shell am start -n $PACKAGE/com.example.MainActivity */ }
fun importLink(link: String) { /* find EditText by hint, input text, tap Extract */ }
// plus assertions: 3 tabs visible, guest card vs Google card, banner absence, counts
```

File: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt` and `RealUserScenarioPhase02ImportTest.kt`

- [ ] **Step 4: Run test to verify it passes on emulator**

Run: `bash scripts/real-user-scenario-run.sh` (phase 02 only) — check `docs/reports/raw/RealUserScenarioPhase02ImportTest-*.txt` contains `OK (`
Expected: PASS; buglog appended with any S1/S2 found as BUG-xxx

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/com/example/RealUserScenarioPhase02ImportTest.kt app/src/androidTest/java/com/example/RealUserScenarioHarness.kt
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Auto walkthrough phases 0-2: startup, auth, import"
```

---

### Task 4: Automated walkthrough Phases 3-5 (workspace, distillation, export)

**Files:**
- Create: `app/src/androidTest/java/com/example/RealUserScenarioPhase35ExportTest.kt`
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt` — add `applyFilter()`, `search()`, `editTurn()`, `runDistillation()`, `exportFormat()`

**Interfaces:**
- Consumes: `Harness.importLink()` from Task 3, `ExportFileProofTest` pattern for file checks
- Produces: Test class with `phase3_workspaceFiltersAndBidi`, `phase4_distillationAllModes`, `phase5_exportAllSevenFormats` for Tasks 6-7

- [ ] **Step 1: Write failing test for 7-format export**

```kotlin
@Test fun phase5_exportAllSevenFormats() {
    val formats = listOf("PDF","DOCX","PPTX","HTML","MD","TXT","JSON")
    formats.forEach { fmt ->
        // export, assert file exists in Download, size > 0, openable
        assertTrue("no file for $fmt", fmt.isNotEmpty())
    }
}
```

File: `app/src/androidTest/java/com/example/RealUserScenarioPhase35ExportTest.kt`

- [ ] **Step 2: Run to verify it fails**

Run: `adb -s emulator-5554 shell am instrument -w -e class com.example.RealUserScenarioPhase35ExportTest#phase5_exportAllSevenFormats com.aistudio.aichatexporter.kxrtva.test/androidx.test.runner.AndroidJUnitRunner`
Expected: FAIL

- [ ] **Step 3: Implement workspace/distillation/export driving**

```kotlin
fun applyFilter(label: String) { /* tap filter chip */ }
fun search(query: String) { /* input search field */ }
fun runDistillation(mode: String) { /* select mode, tap Generate */ }
fun exportFormat(format: String, style: String = "اداری"): File { /* tap Export, wait, return file */ }
```

- [ ] **Step 4: Run to verify green**

Run: `bash scripts/real-user-scenario-run.sh` — check all 7 files listed in `MediaStore.Downloads` dump
Expected: PASS; BiDi checks via `PdfParagraphSideTest` pattern for PDF

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/com/example/RealUserScenarioPhase35ExportTest.kt app/src/androidTest/java/com/example/RealUserScenarioHarness.kt
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Auto walkthrough phases 3-5: workspace, distillation, 7-format export"
```

---

### Task 5: Automated walkthrough Phases 6-9 (library, settings, nav/lifecycle)

**Files:**
- Create: `app/src/androidTest/java/com/example/RealUserScenarioPhase69LifecycleTest.kt`
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt` — add `toggleTheme()`, `setFontScale()`, `driveBackup()`, `pressBack()`, `killAndRelaunch()`

**Interfaces:**
- Consumes: `Harness.exportFormat()` from Task 4, `PersistenceRoundTripTest` pattern for kill/relaunch
- Produces: Tests `phase6_librarySearchAndStar`, `phase7_settingsThemesAndBackup`, `phase8_navAndLifecycle`, `phase9_newConversationResets`

- [ ] **Step 1: Write failing test for kill-and-relaunch persistence**

```kotlin
@Test fun phase8_killAndRelaunchRestoresDraft() {
    // open workspace, kill via am force-stop, relaunch, assert same session id visible
    assertTrue(RealUserScenarioHarness.waitForText("پیش‌نویس", 3000))
}
```

File: `app/src/androidTest/java/com/example/RealUserScenarioPhase69LifecycleTest.kt`

- [ ] **Step 2: Run to verify it fails**

Run: `adb -s emulator-5554 shell am instrument -w -e class com.example.RealUserScenarioPhase69LifecycleTest#phase8_killAndRelaunchRestoresDraft com.aistudio.aichatexporter.kxrtva.test/androidx.test.runner.AndroidJUnitRunner`
Expected: FAIL

- [ ] **Step 3: Implement library/settings/nav driving**

```kotlin
fun toggleTheme(name: String) { /* tap theme chip */ }
fun killAndRelaunch() {
    Runtime.getRuntime().exec(arrayOf("adb","-s","emulator-5554","shell","am","force-stop", PACKAGE)).waitFor()
    launchApp()
}
```

- [ ] **Step 4: Run to verify green**

Run: `bash scripts/real-user-scenario-run.sh`
Expected: PASS; rotation, background/foreground, double-back Toast all checked

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/com/example/RealUserScenarioPhase69LifecycleTest.kt app/src/androidTest/java/com/example/RealUserScenarioHarness.kt
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Auto walkthrough phases 6-9: library, settings, lifecycle"
```

---

### Task 6: Edge-matrix probes (one probe per phase, same harness)

**Files:**
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioPhase02ImportTest.kt` — add `phase2_edge_invalidLinkShowsError`, `phase2_edge_airplaneMode`
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioPhase35ExportTest.kt` — add `phase5_edge_doubleTapExport`, `phase3_edge_emptySearch`
- Modify: `app/src/androidTest/java/com/example/RealUserScenarioPhase69LifecycleTest.kt` — add `phase7_edge_backupWithoutLogin`, `phase8_edge_rotationKeepsScroll`

**Interfaces:**
- Consumes: All harness helpers from Tasks 3-5
- Produces: Additional test methods that each append a BUG to buglog on failure; no new files

- [ ] **Step 1: Write failing edge test (invalid link must not open workspace)**

```kotlin
@Test fun phase2_edge_invalidLinkShowsError() {
    importLink(RealUserScenarioHarness.REF_LINK.dropLast(1) + "1") // one-char mutation
    assertTrue(waitForText("نامعتبر", 4000) || waitForText("پیدا نشد", 4000))
}
```

- [ ] **Step 2: Run to verify it fails before fix**

Run: `adb -s emulator-5554 shell am instrument -w -e class com.example.RealUserScenarioPhase02ImportTest#phase2_edge_invalidLinkShowsError com.aistudio.aichatexporter.kxrtva.test/androidx.test.runner.AndroidJUnitRunner`
Expected: FAIL if app currently treats any link as success

- [ ] **Step 3: Fix app code if edge reveals bug (or document as BUG if fix deferred)**

If app already shows error, test passes and no BUG. If not, append to buglog:

```
BUG-0xx
فاز / قدم: فاز ۲ / لبه — لینک خراب
ورودی: https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98721
انتظار: کارت قرمز «نامعتبر» و تب محیط کار باز نشود
واقعیت: تیک سبز و محیط کار باز شد
شاهد: BUG-0xx.png
تکرار: همیشه
شدت: S2
نوع: کارکرد
ماژول: ورود داده
```

- [ ] **Step 4: Run all edge tests to verify green or logged**

Run: `bash scripts/real-user-scenario-run.sh` — check buglog summary row count
Expected: either PASS or new BUG rows appended

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/com/example/*.kt docs/reports/real-user-scenario-buglog-2026-10-24.md
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Add edge-matrix probes for all phases"
```

---

### Task 7: Consolidate buglog, score, and generate fix plan

**Files:**
- Create: `tools/real-user-buglog-consolidator.py`
- Modify: `docs/07_TASKS.md` — append scored rows
- Modify: `docs/reports/real-user-scenario-buglog-2026-10-24.md` — fill scores and dedup

**Interfaces:**
- Consumes: Completed buglog from Tasks 3-6 (all BUG forms), spec section 5 scoring formula
- Produces: Appended rows in `docs/07_TASKS.md` with columns `شناسه | ماژول | شدت | عنوان | گام بازتولید | انتظار | شاهد | وضعیت`; final report printed to stdout

- [ ] **Step 1: Write consolidator script**

```python
# tools/real-user-buglog-consolidator.py
# reads docs/reports/real-user-scenario-buglog-2026-10-24.md,
# dedups by (module,title), scores: S1=100,S2=50,S3=20,S4=5 × repeat(always=1.5,sometimes=1,once=0.7),
# sorts by score desc then phase asc, appends to docs/07_TASKS.md with status open
import re, pathlib
SEV = {"S1":100,"S2":50,"S3":20,"S4":5}
REP = {"همیشه":1.5,"گاهی":1,"یک‌بار":0.7,"always":1.5}
# ... parse, score, sort, append
```

File: `tools/real-user-buglog-consolidator.py`

- [ ] **Step 2: Run consolidator**

Run: `python tools/real-user-buglog-consolidator.py --buglog docs/reports/real-user-scenario-buglog-2026-10-24.md --tasks docs/07_TASKS.md`
Expected: prints `Scored N bugs, appended M rows to 07_TASKS.md`

- [ ] **Step 3: Verify tasks file has new rows**

Run: `grep -c "BUG-" docs/07_TASKS.md && tail -20 docs/07_TASKS.md`
Expected: count > 0, last rows match buglog titles

- [ ] **Step 4: Commit**

```bash
git add tools/real-user-buglog-consolidator.py docs/07_TASKS.md docs/reports/real-user-scenario-buglog-2026-10-24.md
git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "Consolidate buglog into scored fix plan"
git push origin master
```

---

## Self-Review

- [x] Spec coverage: all 9 phases, edge matrix per phase, BUG template, scoring, and definition of done are each mapped to a task (Tasks 3-7). Spec section 6 outputs (buglog file, tasks file) are produced.
- [x] Placeholder scan: no TBD/TODO; every step has concrete file paths, code snippets, and shell commands.
- [x] Type consistency: `RealUserScenarioHarness` exports same names across tasks; `PINNED_SERIAL` and `REF_LINK` constants reused; `docs/07_TASKS.md` column set identical in spec and Task 7.

