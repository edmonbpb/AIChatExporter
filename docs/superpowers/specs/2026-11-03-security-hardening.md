# Security hardening before release — measured findings

Date: 2026-11-03. Basis: a read-only audit of `wave/auth-exit-2026-11-01` at `5ce047e`
plus one measured scan of every git object in the repository.

## What is already true (verified, do not re-fix)

| Check | Evidence |
| --- | --- |
| No live AI key in the repository, including every historical object | `git cat-file --batch-all-objects --batch \| grep -a -oE "AIzaSy[A-Za-z0-9_-]{30,}"` → 0 |
| No live AI key in the release artifact | 3 dex files searched, 0 hits |
| Firebase configuration is inside the release artifact | project id found in `resources.arsc` |
| No secret is written to logcat | no `Log.*` line mentions key, token, email or password |
| App data is excluded from backup and device transfer | `AndroidManifest.xml:10`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |
| FileProvider exposes one folder only | `res/xml/file_paths.xml` → `files-path documents/` |
| Export file names cannot escape their folder | `DocumentExporter.kt:106` strips every character outside `[a-zA-Z0-9\u0600-\u06FF_-]`, then caps at 30 |
| Incoming share text is bounded | `MainActivity.kt:316` requires a URL and `< 500` characters |
| No cleartext traffic configuration exists | manifest has no `usesCleartextTraffic`, no network security config |
| Permissions are minimal | INTERNET, ACCESS_NETWORK_STATE, USE_BIOMETRIC only |

## Findings this plan closes

### S1 — an incoming share can bypass the verification wait (High)

`MainActivity.handleIncomingIntent` (`MainActivity.kt:312`) runs on every `ACTION_SEND`, on a cold
start and on `onNewIntent`, with no look at the gate. While `shouldShowVerification(...)` holds the
root, a share from another app still calls `viewModel.parseAndLoadTranscript()`
(`MainActivity.kt:324`), which loads the workspace behind the wait screen. The wait is a root-screen
decision only; nothing guards the other roads into the workspace.

### S2 — a verified session is keyed by the email, not the provider's id (Medium)

`checkVerificationNow` writes the profile with `uid = address` (`MainViewModel.kt:677`). The comment
says the address "stands in for" the id, but every other road writes the provider's `uid`
(`MainViewModel.kt:581`, `:612`). Two different keys for one account means the library, the Drive
link and the export records can be filed under a second identity for the same person.

### S3 — the verified address is never compared to the pending one (Medium)

`isEmailVerified()` (`FirebaseAccountService.kt:126`) reloads `auth.currentUser` and returns its
flag without asking whether that user is the address the wait screen is about. If the provider's
current user is a different account (a second sign-up in another window, a restored session), a
verification of account B opens the session for account A.

### S4 — both user-entered keys are stored in plain preferences (Medium)

`MainViewModel.kt:1557` (`gemini_api_key`) and `:1578` (`reader_api_key`) are `putString` on
`aichat_auth_prefs`. `allowBackup=false` and the backup rules keep them off Google's servers, but on
a rooted device, in a `run-as` shell on a debuggable build, or in any forensic image the two keys are
readable in clear. The app lock gates the screen, not the bytes.

### S5 — the AI key travels as a URL query parameter (Low)

`GeminiClient.kt:115`, `:232`, `:307` build `...:generateContent?key=$apiKey`. TLS protects the wire,
but a query string is the part of a request that proxies, crash reporters and `logcat`-level network
dumps keep. The provider accepts the same key in `x-goog-api-key`.

### S6 — copied chat text is not marked sensitive (Low)

`FileShareHelper.copyToClipboard` (`FileShareHelper.kt:70`) puts the text on the clipboard with no
`EXTRA_IS_SENSITIVE`, so Android 13+ shows the full preview on the clipboard overlay and other apps
may read it.

### S7 — a build without the Firebase file only warns (Medium)

`app/build.gradle.kts:103` sets `missingGoogleServicesStrategy = WARN`. A hand-out build assembled
without `app/google-services.json` succeeds and ships an app whose account buttons answer
`ACCOUNT_UNAVAILABLE_FA`. Nothing in the build fails, and nothing verifies the artifact afterwards.

### S8 — no release-artifact gate exists (Medium)

There is no script that fails a build when the artifact carries a key, lacks the Firebase project id,
or is debuggable. The checks were done by hand in this session and would have to be repeated by hand
next time.

## Out of scope (decided, recorded)

- Screen-capture blocking (`FLAG_SECURE`): refused. The verification workflow of this project depends
  on `screencap`; the limitation goes into the privacy text instead.
- Encrypting the Room database: refused for this release. The database schema is frozen and a
  migration is forbidden; the two keys (S4) are the only secrets small enough to move safely.
- Firebase console settings (enumeration protection, key restrictions, quotas): owner actions, listed
  in the handoff, not code.
