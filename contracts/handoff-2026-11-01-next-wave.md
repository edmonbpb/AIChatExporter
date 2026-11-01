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

## Open — one real finding, owner's decision

`B-۴۷` in `docs/09_DEBUG_PLAN.md`: **the disconnect does not survive a restart.** Pressing
`قطع اتصال گوگل` drops the card for that session, but after a full close the card rebuilds itself as
connected, because the cold-start path derives the link from Google's own cached `drive.file` grant and
nothing reads the app's `drive_linked` key at start. Two fixes are written in the row; (الف) is the one
I recommend — a stored "the user disconnected this" marker, cleared by `beginDriveConsent()`.

## State of the handset right now

- The in-app account is the test address `qoder.test@example.com` (created while verifying remember-me),
  not the owner's Google account. Nothing of the library was lost.
- The app lock switch is **off** — the owner removed it through the lock screen, which also proves that
  path works end to end.
- Drive still shows connected after the restart described above; the Google consent was never revoked
  and this app cannot revoke it.

## Not done on purpose

- Nothing was pushed to GitHub (the owner has not asked in this round).
- `versionCode` is still `1`; the hand-out rule in `README.md` says bump it for every new hand-out.
- Biometric face/fingerprint were not separately exercised — the owner's handset answered the device
  credential path; the prompt covers all five authenticators in one call.
