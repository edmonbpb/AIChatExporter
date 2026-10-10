# Controller rehearsal of the Antigravity E2E plan (2026-10-10, emulator-5554)

I ran `AGY-E2E-PLAN.md` myself, end to end, before handing it to an outside agent. The app did the
job — link in, PDF out. But the rehearsal also broke three instructions in the plan, and those
corrections matter more than the successful run.

## Result of the flow

| step | what happened | witness |
|---|---|---|
| cold start | workspace restored, model line reads `ChatGPT` (platform name, no invented model) | `ctrl-step1-coldstart-afterinstall.xml` |
| import | ChatGPT share link imported, banner «۲۶ نوبت رد شد و تنها ۳۸ نوبت از آن خوانده شد» | `ctrl-step5-poll-01.xml` |
| export | «صدور فوری سند (Adobe PDF (.pdf))» reflects the chosen format | `ctrl-step8-current.xml` |
| result | success card plus a real file: `AIChat_..._20261010_200430.pdf`, 2,719,942 bytes, 20:04 | `ctrl-step9-after-export.xml` |
| file valid | `%PDF-1.4` header, `%%EOF` trailer, 174 page objects | `ctrl-exported.pdf` |

The whole point of the last row: a success card is a claim, the file on disk is the proof, and both
were checked.

## Three defects in the plan itself, found only by running it

1. **The testTags are invisible to the device.** The app has 39 `testTag` call sites, but
   `testTagsAsResourceId` appears nowhere in the source, so `uiautomator` dumps contain no
   `resource-id` from our package at all (measured: `resource-id="com.aistudio…` never appears). An
   agent told to "find the node by its resource-id" would have failed at step 2. Either add
   `testTagsAsResourceId = true` to the root semantics (a one-line app change that makes every
   future outside-agent run deterministic) or rewrite the plan to use text only. Until then, plans and
   briefs must locate nodes by **text**.
2. **Text alone is ambiguous.** «ورود داده» exists twice on the workspace screen: once in the step
   breadcrumb (y=1626) and once in the bottom nav (y=1824). Tapping the first match does nothing.
   The rule that works: take the node that is `clickable="true"`; if none is, take the one with the
   **largest y** (the bottom bar). I hit this and corrected it.
3. **`adb shell input text` silently drops the tail of a long string.** Typing the 62-character URL
   in one call landed only 48 characters, ending mid-UUID at `…fbbeae4`. The app was not truncating;
   the tooling was. Typing in 18-character chunks with short pauses produced an **exact** match.
   Any agent must read the field back and compare byte-for-byte, then clear and retype in chunks.

## Two things that looked like defects and were not

- **Latin digits in the filter chips** (`فقط کدها (226)`). The emulator was running an APK built at
  05:19, before the chip fix landed. After installing the current build the same screen reads
  `فقط کدها (۲۲۶)`. Without the plan's "verify the build is current first" step this would have been
  filed as a defect that does not exist — `lastUpdateTime` on this emulator only exposes the date, so
  the screen text itself is the witness.
- **The dump coming back empty.** `uiautomator dump` sometimes reports success and leaves nothing
  readable when the screen is animating. Retrying with a short pause between the dump and the read is
  enough; three consecutive empties would be the signal to stop.

## What the outside agent must be told that the first draft did not say

- Locate nodes by text, clickable-first then lowest-on-screen; never by testTag.
- Type the URL in ~18-character chunks and assert the field equals the URL exactly before continuing.
- After every tap, re-dump and confirm you are on the screen you expected before the next tap.
- Check the build is current before judging anything on screen.