# Pre-launch cleanup — waves W0 to W3 (2026-10-11)

Plan: `~/.qoder/plans/slim-wilderness-gannet.md`. Verdict that started it:
`docs/reports/release-readiness-2026-10-11.md`.

## W0 + W1 — the leaked key (done, measured)

The Gemini key was found verbatim in three archived UI dumps taken off the Settings screen.

| what | evidence |
|---|---|
| old key revoked in AI Studio | `GET /v1beta/models` with the old key returns **401**; with the new key **200** |
| new key in place | `.env` `GEMINI_API_KEY` matches the live key (53 chars, `AQ.` prefix); `GOOGLE_CLIENT_ID` untouched |
| three dumps redacted | `docs/reports/raw/ui-settings-05-up.xml`, `ui-settings-06-keycards.xml`, `plan10-P4-settings-after-swipes.xml` — one hit each, byte delta -32 each (`[REDACTED-GEMINI-KEY]` is 21 chars vs 53) |
| repo sweep | 374 files scanned, **0** still containing the old key |

The key never passed through the chat or any report: it travelled browser → a localhost receiver →
`.env`, and the receiver and the temporary `.env` backup were both deleted afterwards.

**How that transfer worked, because it will be needed again:** the connector's tab cannot be driven
by `uiautomator`-style refs on this Google page (its accessibility tree comes back empty) and
`navigator.clipboard.writeText` is refused (`NotAllowedError`, the tab is not focused), so the copy
button never reaches the clipboard. What works: open the key's details dialog by clicking the
masked-key element, read the key out of `innerText`, and `fetch` it to `http://127.0.0.1:<port>` —
Chrome treats localhost as trustworthy, so an https page may post to it. Keep the receiver's handler
writing straight to a file; a closure variable in node-repl did **not** survive between cells.

## W2 — Android 7/8/9 dropped (done, measured)

`minSdk` 24 → **29**, and the pre-Q branch that could never be tested here was deleted rather than
left in place. `Outcome.NotSupported` is gone from the whole tree; the export note now says plainly
that the document was saved and only the Downloads copy failed. `aapt dump badging` on the built APK
reports `sdkVersion:'29'`.

Consequence to remember: installs on Android 7-9 stop receiving updates. That is the user's decision,
taken with the number in front of him.

## W3 — four UI lanes, parallel, disjoint files (built, not yet seen on a device)

| lane | file(s) | what a user will notice |
|---|---|---|
| W3a | `ExportStudioScreen.kt` | the size on the success card follows the phone's digit switch; a failed export now shows a red card (`export_error_card`) instead of nothing |
| W3b | `DistillationScreen.kt` | history count, row clock and the "attempt 2 of 4" retry banner follow the switch, via one local `shown()` |
| W3c | `AppNavigation.kt`, `CodeBlockView.kt`, `MainActivity.kt`, `WorkspaceScreen.kt` | step badges follow the switch; the code panel header reads «(۱۲ خط)» instead of «(12 lines)» |
| W3d | `LibraryScreen.kt` | exported files get a `تاریخ:` line from the already-stored `exportedAt`, so two same-format PDFs are tellable apart; long names now show an ellipsis |

Gate: `assembleDebug` + `testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL**, **91 tests, 0
failures, 0 errors, 0 skipped**, all seven XMLs 81 s old. `GeminiClient.kt` was deliberately left
untouched (mtime still 2026-10-10 18:13) because `LocalDistillationTest.kt:310-338` scrapes that
source at test time.

## Two corrections the plan itself needed

1. **`TextOverflow.MiddleEllipsis` does not exist here.** Measured on the cached artifact: BOM
   2024.09.00 resolves `ui-text` 1.7.0, whose enum is `Clip/Ellipsis/Visible/Invalid`. The plan's
   remedy was impossible; W3d shipped `Ellipsis`, so the file extension at the end of a long name is
   still not visible. Registered as **B-۲۷** (needs a BOM bump or hand-rolled truncation).
2. **The "badge contradicts its own label" claim was wrong.** `AppScreen.stepLabel` (the «گام ۱…»
   copy) is never rendered by any composable. Registered as **B-۲۸** with the recommendation to either
   wire it or delete it.

## Not done — where to resume

- **W4** gate the DOCX/PPTX dates so the digit switch actually works in the deliverable (row **B-۲۲**).
  Files: `OpenXmlGenerator.kt`, `ExampleUnitTest.kt`.
- **W5** collapse the two hand-built `ExportOptions` and the duplicated size formatter (rows **B-۲۳**,
  **B-۲۴**). Must precede W6.
- **W6** the fifth PPTX choice plus the pre-export slide-count estimate (row **B-۱۹**, user decision
  taken: choice + estimate, no silent ceiling).
- **W7** template leftovers and the monochrome icon; **W8** the stale doc claims (row **DOC-۰۱**,
  including `docs/01_PRD.md:111-112` which still promises the opposite of what the build does);
  **W9** the two instrumented tests (needs the network — the androidx.test aars are not all cached);
  **W10** privacy policy + data-safety answers + store-asset spec; **W11** ignore hygiene and
  `git init`.
- **Nothing has been verified on a device this round.** Every W3 claim is "it compiles". The
  on-screen witnesses (digits flipping with the switch, the two PDFs distinguishable, the error card
  appearing) are still owed, and `uiautomator` is dead on this emulator instance — use screenshots.
- **The three release blockers are untouched**: no signing keystore, the key still gets baked into
  the APK when `.env` has one, and Google sign-in is registered only for the debug SHA-1 with the
  consent screen in Testing.
