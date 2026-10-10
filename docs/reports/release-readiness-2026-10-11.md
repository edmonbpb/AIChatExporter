# Release readiness audit — 2026-10-11

Verdict: **not releasable today.** Three blockers, all measured on artifacts in this build, not
inferred. Everything else is a checklist, not a wall.

## What was actually run for this audit

| gate | result |
|---|---|
| `./gradlew lintDebug` (online — offline fails, see note) | BUILD SUCCESSFUL in 9m 48s. **0 errors**, 47 warnings. Report: `app/build/reports/lint-results-debug.{html,xml,txt}` |
| `./gradlew bundleRelease --offline` | BUILD SUCCESSFUL in 9m 12s, including `lintVitalAnalyzeRelease`. Artifact: `app/build/outputs/bundle/release/app-release.aab`, 13,052,098 bytes, 601 entries |
| unit tests | 91 tests / 0 failures on the fresh `--rerun-tasks` gate earlier in this round (section 7 of `hardening-round-2026-10-10.md`) |
| end-to-end user flow | link → import → PDF → file proven on disk and structurally valid, today, on `emulator-5554` (`docs/reports/agy/ctrl-device-run-2026-10-11.md`) |

`lintDebug --offline` **fails**, not the code: lint resolves JVM/desktop variants of the Compose and
Lifecycle artifacts (`*-desktop`, `*-jvmstubs`) that are not in the local cache. `dl.google.com` is
reachable from this machine (measured: HTTP 200 in 1.3 s), so lint must run online. Any CI script
that passes `--offline` to lint will report a false red.

## Blocker 1 — no signing key, so nothing can be uploaded

`app/build.gradle.kts:27-37` only creates the `release` signing config when `KEYSTORE_PATH`,
`STORE_PASSWORD` and `KEY_PASSWORD` are all set. None are set, so `signingConfig` resolves to
`null` (`:51`) and the build still succeeds — it just produces an **unsigned** bundle. Measured:
the AAB contains no `META-INF/` signer entries at all. Play rejects unsigned uploads, so the
release pipeline has never been exercised end to end.

Decisions this forces, none of which are mine to make: where the upload keystore lives, whether
Play App Signing is used, and the fact that `applicationId = com.aistudio.aichatexporter.kxrtva`
(`:16`, the `kxrtva` suffix is an AI Studio artifact) becomes permanent the first time a bundle is
uploaded.

## Blocker 2 — the Gemini API key ships inside the release bundle

`GeminiClient.kt:59` reads `BuildConfig.GEMINI_API_KEY`, and the secrets plugin fills that field
from `.env` at build time. Measured on the artifact, not inferred: the 53-byte key from `.env` is
present verbatim in `base/dex/classes2.dex` of `app-release.aab`, and in `classes6.dex` +
`classes8.dex` of the debug APK. Anyone who installs the app can extract it and spend the key
owner's quota.

The runtime behaviour is fine — `getEffectiveApiKey()` prefers a user-entered key, treats the
`MY_...` placeholder as absent, and the UI explains the no-key path. The problem is only what gets
baked in. Building with `GEMINI_API_KEY=` empty (each user pastes their own key, which the app
already supports and stores locally) removes the blocker without touching code.

Side effect: `docs/01_PRD.md:112` states «هیچ‌گونه کلید یا توکن به صورت هاردکد ذخیره نمی‌شود». That
is true of the source tree and false of the shipped artifact.

## Blocker 3 — Google sign-in cannot work in a release build

The wiring is correct (`GoogleAuthManager.kt:38-56`: web client id as `serverClientId`, nonce, a
`NotConfigured` guard when the id is a placeholder, and `GetCredentialException` caught). But the
Google Cloud side is registered for a debug build:

- the Android OAuth client was created from this machine's **debug** keystore SHA-1; a release-signed
  APK has a different SHA-1 and Google will refuse the caller;
- the consent screen is in **Testing** mode: only allow-listed accounts, and a lifetime cap of 100
  users. A public app needs the screen published to "In production".

Neither is fixable from this repository. Both need the console, and both need the release keystore
from blocker 1 to exist first.

## Not blockers, but expected before a real launch

- **Five open register rows** in `docs/09_DEBUG_PLAN.md`: B-۱۲ (Latin digits — now also the export
  studio success card), B-۱۳ (same validation text in two places), B-۱۷ (list-line count capped at
  6), B-۱۹ (a 38-turn chat becomes a 617-slide PPTX — needs a product decision), B-۲۰ (two exported
  PDFs look identical in the library).
- **No instrumented coverage.** `app/src/androidTest` holds only the template
  `ExampleInstrumentedTest.kt`. Room and the PDF writer have never been exercised against a real
  Android runtime in CI — the PDF was validated by hand, twice, on device.
- **Android 7-9 path is honest but untested.** `DownloadsPublisher.kt:26-28` refuses the Downloads
  copy below API 29 with a Persian message instead of crashing, which is the right behaviour; no
  device in that range has ever run the build.
- **Store listing has nothing prepared**: privacy policy URL (none exists anywhere), Play data-safety
  answers (the app sends conversation text to `generativelanguage.googleapis.com`, and the share URL
  plus page text to `r.jina.ai` and `api.allorigins.win` — all three are already disclosed in the
  in-app transparency card, `SettingsScreen.kt:478-512`), content rating, 512px icon, feature
  graphic, screenshots.
- **Template leftovers** that lint and a reviewer will both see: `namespace = "com.example"`,
  `Theme.MyApplication`, 7 unused colour resources, `RedundantLabel` on the activity, and
  `proguard-rules.pro` still the untouched template while `isMinifyEnabled = false`.
- **No version control.** `git rev-parse` says "not a git repository", so there is no commit history,
  no tag to mark what shipped, and no rollback. `.gitignore` already excludes `.env` and
  `debug.keystore`, so initialising the repo is safe to do.
- **No crash reporting** of any kind, which is a choice, not a defect — but it means a field crash
  on a release build is invisible.

## Two lint warnings worth reading, not silencing

- `CredentialManagerMisuse` — `getCredential` without a dedicated `NoCredentialException` branch.
  Not a crash risk: `GetCredentialException` is caught at `GoogleAuthManager.kt:72`. What it does
  mean is that the raw provider message (`e.message`, English) can be shown to a Persian user.
- `OldTargetApi` — `targetSdk = 36` is below the current lint target. Play's minimum is satisfied;
  this is a "keep an eye on it" item.

## Shortest path to "ready"

1. Decide the signing story, create the upload keystore, set the three env vars, re-run
   `bundleRelease`, and confirm `META-INF/` signer entries appear.
2. Rebuild the bundle with `GEMINI_API_KEY` empty; re-run the dex byte check and confirm zero hits.
3. Add the release SHA-1 to the Google Cloud project and publish the OAuth consent screen.
4. `git init`, commit, tag — so the thing that shipped is identifiable.
5. Clear B-۱۲/۱۳/۱۷/۲۰ and take the B-۱۹ decision.
6. Write the privacy policy and fill the data-safety form from the transparency card that already
   exists in the app.
