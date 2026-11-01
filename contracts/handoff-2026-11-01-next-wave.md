# Hand-off — next wave (2026-11-01)

Read this first if you are continuing this app. The requirement text is
`contracts/next-wave-plan-2026-11-01.md`; the API seam and file ownership that the wave was built to is
`contracts/next-wave-contract-2026-11-01.md` (it carries one correction marked in place — see the
`B-۴۶` row).

## What shipped

Two commits on `master`:

- `a2f3b68` — the ten requirements: smaller signup (no name, no password repeat), tab and button texts,
  Google account creation moved to the signup tab, remember-me (email only), the two calibration
  switches hidden from Settings while their defaults stay on, `قطع اتصال گوگل` replacing the account
  card's sign-out, guest mode retired from the auth path, `همه پیام‌ها` chip, real exit through
  `finishAndRemoveTask()` with a pure double-press rule, and the cold-start app lock
  (`androidx.biometric` + `FragmentActivity`, pattern/PIN/password/face/fingerprint in one prompt).
- `ee8bdd6` — the defect only the handset caught (`B-۴۶`): a signed-in user was kept on the login form.
  The root-screen choice is now `shouldShowAuth(signedIn, manualSignInRequested)` with four tests.

## Verification actually run

- JVM suite **212 green** (baseline was 168; 44 new tests), forced with `--rerun` so nothing was read
  off a cache.
- **14 deliberate breaks**, each turning only its own named test red: remember-me write, Google profile
  persist, signup name derivation, Google button tab gate, Persian-digit consumer, disconnect clearing
  the account, guest/consent sentence, all-messages chip, exit window edge, lock gate, cold-start
  unlocked flag, lock-screen button wiring, quick-unlock wiring, root-screen inversion.
- `tools/verify.ps1 -Stage 3` (Persian integrity, findings=0) and `-Stage 4` (aapt badging,
  package `com.aistudio.aichatexporter`) both PASS.
- Signed keyless release built with `KEYSTORE_PATH/STORE_PASSWORD/KEY_PASSWORD` plus
  `-PpublicBuild=true --no-configuration-cache`: `app/build/outputs/apk/release/app-release.apk`,
  46,660,953 bytes, signer SHA-1 `b7075e5e…1b9b`, AI key present in **0 of 3** dex files.
- On `R58X30GS9QJ` (SM-A155F, API 36), installed with `install -r` so the library stayed: cold start
  lands in the workspace with the owner's real Claude conversation (۵ turns, ۷۳۷ words); the lock
  screen appears on a cold start once the switch is on; cancelling the credential prompt keeps it
  locked; manual sign-in reveals the login form with the quick-unlock button; the remembered address
  prefills with an empty password; the signup tab alone carries the Google button and the divider;
  the settings page shows neither calibration card; `همه پیام‌ها` clears a search that had hidden every
  turn; double back removes the task from recents (mentions of this package 12 → 0, back after a
  relaunch). Dumps: `docs/reports/raw/phone101-*.xml` (git-ignored, controller disk only).

## Closed on the handset — the disconnect now survives a restart

`B-۴۷` in `docs/09_DEBUG_PLAN.md`: **the disconnect did not survive a restart.** Pressing
`قطع اتصال گوگل` dropped the card for that session, but after a full close the card rebuilt itself as
connected, because the cold-start path derives the link from Google's own cached `drive.file` grant.

Two commits close it: `5263978` stores the user's decision (`drive_disconnected`) and honours it in the
card's opening value — **that was not enough, and only the handset proved it**: opening Settings asks
Google again, and the cached grant answered. `872f6b4` makes the same stored decision gate the probe
itself (`driveSignedOut` starts from it), and only `beginDriveConsent()` lifts it. The new test renders the
real settings screen and counts Google token calls, asserting zero; restoring the old line turns exactly
that test red and leaves the other four Drive classes green.

On `R58X30GS9QJ` with `versionCode=3`: the card showed `اتصال به گوگل درایو` before any press, and after
`am force-stop` plus a relaunch it was still the connect button, with no backup/restore buttons
(`docs/reports/raw/phone101-v3-card-start.xml`, `phone101-v3-afterrestart.xml`). Suite is **218 green**.

## Also closed — the retired model label in the library list

`B-۴۸`: a conversation imported before the honesty fix still read «مدل: Universal LLM». The read-point
scrub knew only two retired labels; the CUSTOM platform's invented one is now on the list, so the card
falls back to the platform name. Two tests in `HonestModelLabelTest`, and the draft-store round-trip
fixture moved to a genuinely stated model so its own assertion keeps its meaning. On the handset with
`versionCode=4`: both 2026/10/06 cards read «مدل: سفارشی / عمومی», the string appears nowhere, and the
ChatGPT card is untouched (`docs/reports/raw/phone101-v4-library.xml`). Suite is **220 green**.

## State of the handset right now

- `versionCode=4` is installed; the version line reads `نسخهٔ برنامه: 1.0 (4)`.
- The owner signed back in with their own Google account (`sbedmon1@gmail.com`, role «کاربر گوگل») and
  reconnected Drive; the card shows the connected sentence with backup and restore available.
- The library holds 3 conversations and 13 exported files, unchanged through every install.
- The app lock switch is **off** — the owner removed the lock through the lock screen, which also proves
  that path works end to end.

## Not done on purpose

- Biometric face/fingerprint were not separately exercised — the owner's handset answered the device
  credential path; the prompt covers all five authenticators in one call.
