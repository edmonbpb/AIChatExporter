# Handoff — AIChatExporter, release-readiness wave, 2026-10-31 (~21:40 local)

Read this whole file before touching anything. It states what is already DONE and VERIFIED (do not redo
it), what is LEFT (do it, in this order), and the rules that bind every step.

Repository: `C:\Users\edmon\Desktop\AIChatExporter` — Android, Kotlin 2.x, AGP 9, Gradle 9, Jetpack
Compose, Room, OkHttp, Credential Manager + legacy `play-services-auth 21.1.1`. Branch `master`.
Public GitHub repo: `edmonbpb/AIChatExporter`.

---

## 1. State of the world — verified, do not re-derive

**Identity.** `applicationId = com.aistudio.aichatexporter` (the AI-Studio suffix `kxrtva` was removed
in commit `b734346`). `namespace` is still `com.example`. The old `…kxrtva` app is still installed on the
owner's phone and was never uninstalled; both apps coexist. The owner's data was migrated through Drive.

**Google Cloud.** Project `gen-lang-client-0263171045` (number `1000282107055`), OAuth app is **In
production**, External, `drive.file` (non-sensitive). Both Android clients were re-pointed at the new
package and re-read after a page reload:
- release client `1000282107055-bkm6p84ei2ek83gac8cjvvanpjqg3los`, SHA-1 `B7:07:5E:5E:79:BD:68:7F:3C:6F:0D:C6:DF:F2:9A:6E:38:F0:1B:9B`
- debug client `1000282107055-574oaje2do0r0t966hfckrhhs669bfnc`, SHA-1 `11:92:08:64:2F:AB:5E:E8:98:60:93:CF:62:5E:8F:38:16:0D:E1:73`
- web client (the `serverClientId` that lands in `BuildConfig.GOOGLE_CLIENT_ID` from `.env`):
  `1000282107055-rv2e1rp9kgkm4kq8hntjmvhjlp74ku6m` — unchanged, do not touch.
Cloud Console requires 2-step verification on the account; the owner enabled it on 2026-10-31, so the
console is reachable from the in-app browser session (`sbedmon1@gmail.com`).

**Published policy pages** (Google's consent screen links them; verified `200` and they carry the new
package id): `https://edmonbpb.github.io/`, `/privacy.html`, `/terms.html`. Source of truth is
`docs/privacy-policy.html` and `docs/terms-of-service.html`; `docs/privacy-site/privacy.html` must stay
byte-identical to `docs/privacy-policy.html`.

**Device.** `R58X30GS9QJ` (Samsung SM-A155F). The UFI entry `6517e41c` is a 4G modem on the same USB bus:
**never** install on it, tap it, reboot it or lock it. Installed right now: the signed keyless release
APK built 22:32 local (46,611,797 bytes, `SHA-256 8d900741916fb3f7691c9c28559bc30f720d1cdf9c4b10d2132006dd2d9abfea`),
installed 20:39 phone-local — this one **does** contain the version line, and the line was then read off
the real screen (§2.2 is done). The phone is signed in (`sbedmon1@gmail.com`), Drive is connected, the
library shows «گفتگوها (۳)», and the last backup is `aichatexporter-backup-20261031-171330.json` (533 KB)
in Drive folder id `1FU-X_X1TMAW_U79o3fpuG1xgSTMT2I_1`. Crash buffer was 0 after every run.

**Test baseline.** `./gradlew :app:testDebugUnitTest` → **167 tests, 0 failures** (measured by the
controller at 8m32s, log `/tmp/ver-suite.log`). Relevant classes: `DriveAccessRefusedTest` 13,
`DriveReconnectTest` 4, `DriveGuestCardTest` 5, `GoogleSignInCauseTest` 2.

**Register** (`docs/09_DEBUG_PLAN.md`, 7 pipe-delimited fields per row): B-32, B-42, B-43, B-44 and B-45
are CLOSED with witnesses. Still open: **SEC-02** (a leftover `firebase-applet-config.json` and
`metadata.json` sit at the repo root — gitignored and unverified-clean of the old key, deleting them is
the owner's call) and one minor logged inside B-45's row: after signing back in, the Drive card's status
line keeps the previous cause's sentence until the next Drive call refreshes it.

**Git.** The controller committed the version line together with this handoff file and pushed
`master`, so **the working tree you start from is clean and everything above is on GitHub**. Verify
before trusting it: `git status --porcelain` must be empty and `git log --oneline -3` should show the
version-line commit. Nothing is left uncommitted by design.

**No Play Store.** The owner decided on 2026-10-31: «این برنامه را در گوگل پلی نمی‌گذارم» — the app ships
as a signed APK handed out directly. Do not spend time on store listings, and do not propose Play again.

---

## 2. What is LEFT, in this order

### 2.1 The version line — DONE (code + screen)
The app previously showed no version anywhere, which matters for hand-out APKs. Landed in
`SettingsScreen.kt:789-797`: it renders `نسخهٔ برنامه: <versionName> (<versionCode>)` inside the existing
about/privacy card, tagged `settings_version_line`, and one test
`DriveReconnectTest.aboutCardNamesTheVersionThisBuildWasMadeWith` asserts it. 167/167 green.
Decisions already taken — do not reopen: Latin digits on purpose (a build id must match
`build.gradle.kts` and the APK metadata, not the app's Persian-numbers switch); the test lives in
`DriveReconnectTest` because that class already renders the settings screen.
What is NOT done: no built artifact contains it yet, so nobody has seen it on a real screen. That is
§2.2.

### 2.2 Rebuild, verify, install, and SEE the version line — DONE 2026-10-31 22:39
Both builds ran with the cache rule and passed, `tools/verify.ps1 -Stage 4` printed
`package=com.aistudio.aichatexporter … RESULT: PASS`, the artifact verified as described below
(SHA-1 `b7075e5e…1b9b`, AI key in 0 of 3 dex, version label present in the dex), it was installed on
`R58X30GS9QJ`, and the line was then read off the screen from the hierarchy, not a screenshot:
`settings_version_line | نسخهٔ برنامه: 1.0 (1)` (evidence `docs/reports/raw/rel31d-03-scrolled.xml`).
The commands below are kept because any future build needs the same cache dance:
```bash
cd /c/Users/edmon/Desktop/AIChatExporter
./gradlew --stop
export KEYSTORE_PATH="C:/Users/edmon/.qoder/secrets/aichatexporter-upload.jks"
export STORE_PASSWORD=$(grep -s "^store_password" ~/.qoder/secrets/accounts.ini | cut -d= -f2 | tr -d ' \r')
export KEY_PASSWORD=$(grep -s "^key_password" ~/.qoder/secrets/accounts.ini | cut -d= -f2 | tr -d ' \r')
./gradlew --no-configuration-cache -PpublicBuild=true :app:assembleRelease   # keyless public build
./gradlew --stop && ./gradlew --no-configuration-cache :app:assembleDebug     # keyed debug, gate reads this one
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify.ps1 -Stage 4  # must print package=com.aistudio.aichatexporter, RESULT: PASS
adb -s R58X30GS9QJ install -r app/build/outputs/apk/release/app-release.apk
```
Mandatory cache rule (register row B-40, hit again in this very session): a `-PpublicBuild=true` run
poisons the Gradle daemon **and** the configuration cache for the next keyed build, and the symptom is a
Kotlin `unresolved reference: GEMINI_API_KEY`, not a message about the key. Always pair `--stop` with
`--no-configuration-cache`.
Then verify the artifact, never assume: signature SHA-1 must be `b7075e5e…1b9b`; the Gemini key must be
found in **0 of 3** `classes*.dex` (read the key from `.env` at run time and never print it); `aapt dump
badging` package must be `com.aistudio.aichatexporter`.
On the phone: open Settings, scroll to the about card, `uiautomator dump` with `sh tools/uidump.sh
R58X30GS9QJ docs/reports/raw/<name>.xml` and prove the tag/text is there. Assert from the hierarchy, not
from a screenshot. Save the dump under `docs/reports/raw/` (that directory is gitignored on purpose).

### 2.3 Decide and do the version numbers for a hand-out release
`versionCode = 1`, `versionName = "1.0"` (`app/build.gradle.kts:38-39`). With no Play Store there is no
hard requirement, but if the owner hands out more than one build, bumping `versionCode` per published
file is what makes an in-place upgrade possible. This is the owner's call — ask in one plain sentence,
and if they say yes, do it as its own commit and rebuild (§2.2 depends on the answer, so ask first).

### 2.4 The stale status line (minor, logged in B-45's row)
After a successful Google sign-in, the Drive card's status text can keep the previous cause's sentence
until the next Drive call. Fix idea already written in the register: refresh the status line when the
session becomes a Google one (or clear it), with a test that pins it. Small, single-file-ish. Needs a
team dispatch per §4 and a fresh reviewer.

### 2.5 Close out
Push (`git push origin master`), then update the two project memory files
(`reference-google-oauth-aichatexporter.md`, `project-release-blockers-2026-10-11.md`) and the register.
Then tell the owner where the APK is, its size and its SHA-256 so they can hand it out.

---

## 3. Git and commit rules for this repo
- Commits are the **controller's** job only. Agents never `git add`/`commit`/`push`.
- Identity is not configured; use per-command flags, never `git config`:
  `git -c user.name="AIChatExporter controller" -c user.email="controller@localhost" commit -m "$(cat <<'EOF' … EOF)"`
- One commit per item. Message says **why**, in ordinary English, no emoji.
- `docs/reports/raw/` and `.env` are gitignored — never force-add them. Before any push, re-verify that
  no secret is tracked: `git ls-files | grep -iE "\.env$|keystore|accounts\.ini|google-services\.json|firebase-applet"`
  must print nothing, and the Gemini key must not appear in the pushed tree.
- The repo is **public**. `README.md:4` and `LICENSE` carry the "use only content you have the right to
  access / misuse is yours" notice and the early-stage notice. Every future public push must keep them.
- To republish the legal pages: the site is the repo `edmonbpb/edmonbpb.github.io`, branch `main`,
  Pages from `/`. Clone/refresh `%TEMP%/aichat-site` (already exists), copy
  `docs/privacy-policy.html → privacy.html` and `docs/terms-of-service.html → terms.html`, commit with
  the same `-c` flags, `git push origin main`, then curl the live URLs and prove the expected bytes
  arrived (Pages rebuilds in well under a minute).

## 4. Working rules the owner set (binding)
- Multi-file code changes go through a subagent team (Agent tool, `expert-engineer` worked well);
  **builds and devices belong to the controller, one at a time**. Every delivery gets one read-only
  reviewer (`expert-reviewer`). Keep each dispatch to about three numbered items: agents die at their own
  ~60-turn ceiling — two did in this session. If a dispatch dies, measure the real disk state with
  `git status`/`git diff` before believing any report, finish small leftovers inline only when it is
  genuinely one small file, and say out loud that you did it.
- An agent's report is intent, not evidence. Re-read the diff, run the tests, and run at least one
  deliberate break per claim: the named tests must go red and their siblings stay green. When reverting a
  break, restore from a copy you made (`cp` to `/tmp`) — never `git checkout` a file whose work is
  uncommitted; that destroys it.
- **Never shut down, restart or sleep the host.** The owner is present.
- On the phone: no `pm clear`, no uninstall, no reboot, no system-setting changes, no touching `6517e41c`.
  Installing/reinstalling the app and signing in/out inside it are allowed and must be reported.
- Room schema/version bumps are **forbidden** — `fallbackToDestructiveMigration()` would wipe the owner's
  library. Fix stale data at the read site.
- Secrets: keys live in `C:\Users\edmon\.qoder\secrets\accounts.ini` (sections `[android-signing]`,
  etc.). Read them into env vars, never print them, never paste them into files, commits or reports.
- Google account: standing permission to act as `sbedmon1@gmail.com` in the **in-app** browser
  (`mcp__browser-use__*`, invoked through `mcp_call`). Keep that session signed in. Say one line before
  clicking any consent that grants new data access. Console gotchas: create/edit clients from
  `/auth/clients`; deleting a row needs the row checkbox then "More actions → Delete" (the row/detail
  "Delete" buttons hang forever); heavy SPA pages time out — use short `evaluate_script` calls, take a
  `take_snapshot` only when you need element uids, and never put long sleeps in one call. If the panel is
  hidden the console SPA renders nothing (`visibilityState: hidden`).
- Persian text: `Write`/`Edit` corrupt Persian silently and still report success. Splice Persian bytes
  out of an existing file instead of retyping them, and after every write run the gate:
  `PYTHONIOENCODING=utf-8 python "$TEMP/fa_check.py" <files>` (use `--min-persian 1` for a source file
  that only carries a short needle). Register rows must keep exactly 7 pipe-delimited fields — check
  with `awk '/needle/{print gsub(/\|/,"|")}'` and expect 8.
- Replies to the owner are Persian, right-to-left: first line `<div dir="rtl">`, last line `</div>`, code
  fences outside the wrapper, RTL punctuation (، ؛ ؟ «»), ZWNJ inside words, and plain language: describe
  what a person sees on the screen, keep technical names in backticks, put a recommendation on every
  point, and say which decisions are the owner's.
- Robolectric/Compose test rules that burned this repo: `@Config(instrumentedPackages = [...])` must name
  exactly ONE Google class (`com.google.android.gms.auth.GoogleAuthUtil`) or Robolectric dies with a
  `VerifyError` on `gms/common/logging/Logger`; every `waitUntil` needs `compose.waitForIdle()` inside
  the predicate or it times out; cross-class pollution is real — Google's last-account cache is
  process-wide, so fixtures must clear `com.google.android.gms.signin` and prove their precondition
  loudly instead of passing vacuously (that is how `DriveGuestCardTest` was fixed).
- Do not run two Gradle invocations at once; they share the daemon and the project lock.
- Never run bulk search (`git grep`, `rg`, `find` sweeps) without a timeout: this host has 4 cores and
  another repo on it has a 319 MB tracked file that pinned two git processes for two days.

## 5. Files you will want
- `docs/09_DEBUG_PLAN.md` — the defect register; row B-43 holds the Drive-refusal story end to end,
  B-45 the message-honesty story including the controller's own two mistakes (a contract that claimed
  `AccountManager` was already in use, and an added account query that could not work on the handset).
- `contracts/release-blockers-2026-10-31.md` — Items A/B/C/D/D2, the spec each agent worked from.
- `app/src/main/java/com/example/data/remote/drive/DriveClient.kt` — `DriveAccessRefusedException`,
  `throwIfAccessRefused(code, body)` (401 always, 403 unless the body says rate-limited).
- `app/src/main/java/com/example/data/backup/BackupManager.kt` — `Outcome(ok, message, accessRefused)`
  plus the `drive_refused` key in the `drive_backup` prefs file.
- `app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt` — `driveLinkedAfterToken` (the card
  follows Drive's answer, not Google's minted token) and `drivePermissionNotice(consentRecoveryPending,
  guestSession)`.
- `app/src/main/java/com/example/data/remote/auth/GoogleAuthManager.kt` — `NO_CREDENTIAL_FA` (internal),
  the sentence that replaced the "no Google account on this device" lie; provider text goes to logcat.
- `tools/verify.ps1` (`-Stage 3` = Persian integrity, `-Stage 4` = packaging gate on the **debug** APK),
  `tools/uidump.sh <serial> <out.xml>`.
