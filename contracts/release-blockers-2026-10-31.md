# Contract — release blockers, 2026-10-31

Shared by both agents working this round. Read this file; do not restate the requirement from the
prompt. The controller owns Gradle, devices, git commits and the register (`docs/09_DEBUG_PLAN.md`).

## Ground rules for every agent

- Edit ONLY the files listed under your own item. No other file, no new config, no dependency change.
- Never run `gradlew`, `adb`, or any device command. Never `git add`, `git commit`, `git push`.
- Do not touch `docs/09_DEBUG_PLAN.md`, `docs/reports/**`, or any other agent's files.
- Copy the Persian user-facing strings byte-for-byte from this file. Do not retype them from memory
  and do not "improve" their wording.
- Report at the end: exact files changed, what you did not do, and anything you had to guess.

---

## Item A — B-43: a Drive refusal must reach the Drive card

**Measured defect (device, 2026-10-28, `R58X30GS9QJ`).** After the user revokes this app's access on
their Google account page, Google still mints an access token for a while, so `DriveAuth.accessToken`
returns a token and the card stays "connected". The Drive REST call then answers `401`
(`Request had invalid authentication credentials`) and the user sees backup/restore buttons that can
only ever fail.

**Root cause, read from disk.** `app/src/main/java/com/example/data/remote/drive/DriveClient.kt`
throws away the HTTP status: every non-2xx becomes `null` (`ensureFolder:49-53`, `download:91-93`,
`listFiles:110-115`) or `false` (`upload:70-72`). `BackupManager.kt:44` and `:63` map that single
sentinel to one generic sentence about internet or access. `MainViewModel` changes `_driveLinked`
only from the token outcome (`MainViewModel.kt:173-198`), so nothing that the HTTP layer learns can
ever move the card. The fact is destroyed at the first boundary.

**Fix at that boundary.** Carry the refusal up as a typed fact, in the same spirit as
`DriveAuth.TokenOutcome` (the pattern that closed B-42) and `FetchOutcome.httpCode` in
`SharedLinkExtractor.kt`.

Files you own:

1. `app/src/main/java/com/example/data/remote/drive/DriveClient.kt`
   - Declare `class DriveAccessRefusedException(val httpCode: Int) : IOException(...)` in this file
     (it is the file that learns the code). One sentence of KDoc saying why it exists: Drive looked at
     the token and said no, which is a different fact from "no answer".
   - Every place in this file that reads `response.isSuccessful` / `response.code` must throw it when
     the code is `401` or `403`, and keep the current behaviour for every other failure (still return
     `null` / `false`, still log the code and the body the way `listFiles:113` does today).
   - `listFiles` currently has `catch (_: IOException) { null }`. Because the new exception extends
     `IOException`, add a `catch (e: DriveAccessRefusedException) { throw e }` branch **before** it.
     Check every other `IOException` catch in the file for the same shadowing.

2. `app/src/main/java/com/example/data/backup/BackupManager.kt`
   - `data class Outcome(val ok: Boolean, val message: String, val accessRefused: Boolean = false)`.
   - In `backUp` and `restoreLatest`, catch `DriveAccessRefusedException` around the Drive calls and
     return `Outcome(false, REVOKED_MESSAGE, accessRefused = true)`. Nothing else about those two
     functions changes: the restore rule must stay non-destructive.
   - `REVOKED_MESSAGE` is exactly:

     دسترسی این برنامه به گوگل درایو در حسابِ تو برداشته شده است؛ با دکمهٔ اتصال دوباره وصلش کن.

3. `app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt`
   - The three Drive call sites (`backUpToDriveNow` ~:266, `restoreFromDrive` ~:276,
     `autoBackUpIfDue` ~:287) currently do `_driveBackupStatus.value = outcome.message`. When
     `outcome.accessRefused` is true they must also set `_driveLinked.value = false`, so the card
     offers the connect button again. Keep the message from the outcome (do not invent a second
     sentence in the view model).
   - Do not change `driveTokenOrNull`, `beginDriveConsent`, `onDriveConsentResult`, `signOut`, or the
     B-42 message `اجازهٔ نوشتن در گوگل درایو داده نشده است؛ از دکمهٔ اتصال استفاده کن.` — the
     reconnect tests assert that string verbatim.

4. `app/src/test/java/com/example/DriveAccessRefusedTest.kt` (new file)
   - Robolectric, plain JVM, no device. `DriveClient` already takes its `OkHttpClient` as a
     constructor parameter, so build one with a network interceptor that answers a canned
     `401 Unauthorized` JSON response without touching the network — `Chain.request().toMockedResponse`
     is the way. Pass that client into the real `BackupManager`.
   - Assert at least: (a) `ensureFolder` surfaces the refusal as `DriveAccessRefusedException` with the
     code it was given; (b) `backUp(token)` returns `accessRefused == true`, `ok == false`, and the
     message equal to `REVOKED_MESSAGE`; (c) `restoreLatest(token)` does the same and leaves the local
     database untouched; (d) a `500` answer still behaves as before (`ok == false`, `accessRefused ==
     false`) so the new branch did not swallow ordinary failures.
   - Follow the working example in `app/src/test/java/com/example/DriveReconnectTest.kt` for
     Robolectric configuration, `@Config`, `LooperMode.PAUSED`, and how the application/DB are obtained.
     `BackupManager` and `DriveClient` are `internal`, and the test is in the same module, so use them
     directly.
   - Do not add dependencies. If you think you need one, stop and report instead.

**Verification the controller will run afterwards** (do not run it yourself): the full JVM suite, a
deliberate break of the new `401` branch to prove your test goes red while its siblings stay green,
then a signed release build installed on `R58X30GS9QJ` with the access actually revoked.

---

## Item B — drop the `kxrtva` suffix from the package name

The application id is a leftover of the AI Studio applet that generated this project. The owner has
now ordered it removed. New value, everywhere it is a live reference:

`com.aistudio.aichatexporter`

Files you own (the only live references, found by searching the repository for `aichatexporter`):

1. `app/build.gradle.kts:35` — `applicationId = "com.aistudio.aichatexporter"`. Nothing else in the
   file changes: the namespace stays `com.example`, and `versionCode`/`versionName` stay as they are.
2. `app/src/androidTest/java/com/example/RealUserScenarioHarness.kt:12` — `const val PACKAGE`.
3. `tools/verify.ps1:12` and `tools/verify.ps1:58` — the docstring line and `$ExpectedPackage`.
4. `scripts/real-user-scenario-run.sh:6` — `PKG=`.
5. `AGY-E2E-PLAN.md:36`, `:92`, `:93` — the three commands/rows that name the package.
6. `docs/privacy-policy.md:5`, `docs/privacy-policy.html:32`, `docs/terms-of-service.html:29` and
   `:79`, `docs/privacy-site/privacy.html:32` — the sentence that states the package id, in Persian and
   in English. Only the id inside the `<code>`/backtick token changes; the surrounding text is already
   correct and must stay byte-identical. `docs/privacy-site/privacy.html` must remain an exact copy of
   `docs/privacy-policy.html`.
7. `docs/store/**` — search for `kxrtva` there; if a store asset names the package, update it the same
   way. If there is no hit, say so in your report.

Deliberately **not** yours, do not edit:

- `docs/05_AGENT_MASTER_PROMPT.md` (it describes the old rename plan, including
  `com.aistudio.aichatexporter.prod`).
- Everything under `docs/reports/` and `contracts/hardening-round-2026-10-10.md` — point-in-time
  evidence, kept as measured.
- `docs/09_DEBUG_PLAN.md` — the controller's register.
- Any Google Cloud console work: the owner's browser session handles the Android client's package and
  the SHA-1 pair after the build exists.

Report the complete list of files where you changed the id, and the count of `kxrtva` occurrences that
remain in the tree outside `docs/reports/` (run a search, paste the numbers).

---

## Item C — the reviewer's findings on Item A (2026-10-31, same file set as Item A)

A read-only reviewer read the merged Item A diff and found that the fix is incomplete in a way the
controller's own spec caused: **Item A.3 said "do not change `driveTokenOrNull`", and that is exactly
where the stale "connected" gets written.** The controller owns that mistake; fix it as follows.

C.1 **Critical — the card goes back to "connected" one screen later.** `SettingsScreen.kt:232` runs
`refreshDriveConnection()` on every open of Settings; that calls `driveTokenOrNull()`, Google still
mints a token after a revocation, and `MainViewModel.kt:179` sets `_driveLinked.value = true`. So the
backup/restore buttons return, the connect button disappears, and the status line still says access
was withdrawn — B-43's own symptom. Change the rule so the card follows **Drive's answer, not the
token**:
- Add `private var driveRefused = false` beside the other Drive state in `MainViewModel`, and persist
  it under the key `drive_refused` in the existing `drive_backup` SharedPreferences (the same file
  `BackupManager` uses for `last_backup_date`). Seed it when the view model starts.
- `MainViewModel.kt:179` (the Token branch) must not link the card while `driveRefused` is set. Put the
  decision in one internal pure function next to the view model so the rule itself is testable, e.g.
  `internal fun driveLinkedAfterToken(tokenMinted: Boolean, driveRefused: Boolean): Boolean` with a
  KDoc line saying why a token is not proof of access.
- Clear `driveRefused` only when Drive itself accepts something: a successful `backUp`/`restoreLatest`
  (`outcome.ok`), and when the user starts a fresh consent (`beginDriveConsent`). Set it whenever
  `outcome.accessRefused` arrives, at all three call sites that already check it.

C.2 **Major — one throw site is unmeasured.** `DriveClient.kt:57` (the folder-creation POST) is never
executed by any test: `ensureFolder` either throws earlier in the listing or finds the folder. Add the
first-backup-ever sequence to `DriveAccessRefusedTest`: a `200` with `{"files":[]}` followed by a `401`,
asserting `DriveAccessRefusedException.httpCode == 401` and that the outcome still reports revoked
access.

C.3 **Minor — a bare `403` is several different facts.** Google answers `403` for `drive.file`
permission problems and for rate limits, and a rate-limited user must not be told they revoked
something. Keep `401` as a refusal unconditionally; for `403`, treat it as a refusal **unless** the
error body's `status`/`reason` says `rateLimitExceeded` or `userRateLimitExceeded` — read the body the
same way `listFiles:123` already does, and say in one comment line why the code alone is not enough.

C.4 **Minor, pre-existing — two inconsistencies the diff touched the neighbourhood of.**
`DriveClient.kt:128` parses a 200 body without `runCatching` (its sibling at `:59` has one), so a
malformed body escapes as `JSONException` and reaches `MainViewModel.kt:210`'s generic catch; wrap it
like `:59`. `BackupManager.kt:87` uses `runCatching` around an insert and so swallows
`CancellationException`; rethrow cancellation, keep ignoring the rest.

C.5 **Nit — the fixtures pretend to be production values.** `DriveAccessRefusedTest.kt:179,185,187-188`
name the folder and the file with strings the app never asks for. Use `BackupFormat.FOLDER_NAME` and
`BackupFormat.PREFIX` so a reader is not misled.

Same ground rules as Item A: no Gradle, no device, no git, only the files Item A already owned
(`DriveClient.kt`, `BackupManager.kt`, `MainViewModel.kt`, `DriveAccessRefusedTest.kt`), and do not
weaken any existing assertion or change any existing Persian string — `DriveReconnectTest` asserts one
verbatim. Report the new pure function's signature, the test names you added, and anything you could
not do.
