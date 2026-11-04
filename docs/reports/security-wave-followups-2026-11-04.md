# Security wave — what was deferred, and what is still open

Written when the security hardening wave was merged to `master` (2026-11-04). The wave's own
execution ledger was scratch under `.superpowers/sdd/` (git-ignored, since deleted); this file is
the part of it worth keeping.

The wave: spec `docs/superpowers/specs/2026-11-03-security-hardening.md`, seven tasks, a whole-wave
review, one fix wave, and a device run. Closed findings S1–S8. Every task had an independent
review; every fix had a deliberate break that reddened exactly its named test.

## Not done, by decision — these need the owner, not a commit

| Item | Why it is not in code |
| --- | --- |
| Block screenshots and the recents thumbnail on the login, wait and lock screens (`FLAG_SECURE`) | Loses `screencap` as evidence on those three screens; it is a product choice |
| Encrypt the whole chat database | The Room schema is frozen this release; a rebuild is its own piece of work |
| Delete or rotate the Firebase test account whose password appeared in chat on 2026-11-02 | Console action |
| Restrict the AI key in the Google console (app/API restriction, quotas) | Console action |
| Restrict sign-up to an allow-list of addresses | One check in `FirebaseAccountService.register` plus a test — not started, not scheduled |

## Small things a later pass may want

Code:

- `heldShare` is a bare `MutableStateFlow`; the rest of the view model exposes `asStateFlow()`.
- A held share lives only in memory — a process death before the workspace opens loses it
  (accepted when the task was written).
- `readWithMigration`'s own migration erases the clear copy without the read-back the setters now
  do; `sameStore` already prevents the catastrophic case.
- The setter's read-back confirms the in-memory cache, not the disk (`apply()` is async). Writing
  with `commit()` on the secrets store would close the last window.
- `keyStorageFor` swallows the keystore exception with no class-level log.
- `KeyStorage.backingFile` is a constant per class; it must keep matching the file the class
  actually opens.
- `currentUid()` / `currentEmail()` have no production caller left after the one-snapshot change;
  they were kept on the interface deliberately.
- A verified session carrying a null email falls into the "a different address was confirmed"
  message. Behaviour is safe; only the wording overstates.

Docs and comments that are now stale:

- The KDoc above `authErrorFa` still says `ERROR_USER_NOT_FOUND` means "not in this app's account
  list" — the merged sentence made that false.
- A comment near `MainViewModel.kt:567` still describes a plain `putString`.
- `data_extraction_rules.xml:16` still describes the two key names as if they were in the clear
  preferences file.

Tests:

- `FakeAccountService` gates `currentUid`/`currentEmail` on `verified`, so no test can express
  "session open, mail not yet confirmed".
- A second share held while the gate is up overwrites the first (fail-safe, but last-wins is
  undocumented).

The hand-out gate:

- Check 1 (`no AI key in any dex`) passes vacuously if the APK holds no `classes*.dex` — the glob
  does not expand and `wc -l` reports 0.
- The debuggable check greps for the attribute's presence, so an explicit
  `android:debuggable="false"` would fail it.
- The gate report carries a deliberately fake `AIzaSy…` string as the red-case witness; an
  automated secrets scanner will flag it.

Housekeeping:

- `hs_err_pid18968.log` sits untracked at the repository root (a JVM crash dump from during the
  wave). Delete or ignore it.
