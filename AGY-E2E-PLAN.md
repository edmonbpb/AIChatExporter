# E2E plan — AI Chat Exporter driven as a normal user, by an outside agent

Owner of this document: the Coder session (Qoder). You are an outside agent (Antigravity /
Gemini) being asked to **drive the app like an end user would** and to **report every defect
back to Coder**. Read this file completely before touching the device.

## 0. Your role and its limits

- You are a **user**, not a developer. You use the app through the screen and you judge what a
  normal person would see. You do not know Kotlin and you must not guess about internals.
- **You must not modify any source file, build file, gradle file, or resource under
  `app/src/` or `docs/`.** The Coder session is the only writer of the source tree. If you find a
  bug, you *report* it; you do *not* fix it. A fix applied silently is worse than the bug.
- **You must not** reboot anything, change system settings, change network/VPN/airplane mode,
  run `pm clear`, uninstall the app, or touch any device whose serial is not named in step 1.
- Do not delete files. Do not run `git` commands that mutate state.

## 1. Pin the device first, every time

The dev machine has several Android targets attached and **you must never guess which one**:

```
adb devices -l
```

- The **UFI modem `6517e41c` is forbidden**. Never send it any command.
- If the Samsung phone `R58X30GS9QJ` is present, use it (a real user's phone).
- Otherwise use `emulator-5554` if present.
- If neither is present, write the report `BLOCKER_NO_DEVICE` and stop.
- From here on, **every** adb command must be prefixed `adb -s <serial>`.

## 2. Preconditions to assert, not to assume

| check | command | expected |
|---|---|---|
| app installed | `adb -s S shell dumpsys package com.aistudio.aichatexporter \| grep versionName` | `versionName=1.0` |
| app build age | same command, `grep lastUpdateTime` | today; if older than the newest APK the Coder built, report `STALE_BUILD` and stop |
| screen size | `adb -s S shell wm size` | note it; it changes how much is on screen |

## 3. The link to use

Use a real share link. The Coder session will hand you one at dispatch time; if the brief does
not contain a link, use this one:

```
https://chatgpt.com/share/6ac3c638-36c0-83ed-af49-be9fbbeaeccd
```

Never invent a link. If the link does not import, that is a **result**, not a failure of yours.

## 4. ASSERT BEFORE YOU TAP — the rule that matters most

**Corrected 2026-10-10 by a full rehearsal run. Read this section twice; the first version of it
was wrong and an agent following it would have failed.**

- **You CANNOT use testTags.** The app has many `testTag(...)` call sites in code, but
  `testTagsAsResourceId` is never enabled, so `uiautomator` dumps contain **no resource-id from
  this package at all**. Locate every node by its visible **text**.
- **Text can be ambiguous.** The same words can appear twice on one screen — e.g. «ورود داده» is
  both a breadcrumb step and the bottom-nav tab. Resolve it this way: among the matching nodes take
  the one whose `clickable="true"`; if none is clickable, take the one with the **largest y**
  (the bottom bar), never the first one in document order.
- **Typing a long string loses its tail.** `adb -s S shell input text "<url>"` in one call silently
  dropped 14 of 62 characters in the rehearsal, mid-UUID. Type the URL in ~18-character chunks with a
  short pause between each, then read the field back and compare it to the URL **exactly**. If it
  differs, clear the field (MOVE_END then repeated KEYCODE_DEL) and retype in chunks.
- **Dumps can come back empty.** If a dump yields nothing, wait a second and retry — up to four
  times. A screen that is still animating produces an empty read while the command reports success.
- **Dumps can be dead for the whole session.** If `uiautomator dump` fails with
  `IllegalStateException: UiAutomationService … already registered!` and `adb -s S shell ps -A` shows
  no `uiautomator` process, the registration is stale inside `system_server` — usually left behind by
  a dump that was killed earlier. Only a reboot clears it, and you must not reboot. Switch to the
  screenshot path: `adb -s S exec-out screencap -p > shot.png`, read the screen from the image, and
  tap at bounds copied from the saved dumps in this folder (they are from the same device and
  resolution). Assert every step with a screenshot instead of a dump, and say in your report that you
  had no dumps.

So the loop for every step is:
1. dump: `adb -s S shell uiautomator dump /sdcard/w.xml`, wait, then
   `adb -s S exec-out cat /sdcard/w.xml > dump.xml`
2. find the node by exact text, resolving ambiguity with the rule above
3. only then tap the centre of its `bounds`
4. re-dump and confirm you are on the screen you expected

If the node you need is not in the dump: **scroll one screen and dump again — once.** If it is
still not there, stop and report `BLOCKER_NODE_NOT_FOUND` with the dump path. Do not tap anyway.

## 5. The flow

### Step 1 — cold start
```
adb -s S shell am force-stop com.aistudio.aichatexporter
adb -s S shell am start -n com.aistudio.aichatexporter/com.example.MainActivity
```
Wait 4 s, dump, confirm you are on a real screen (it opens either the workspace or the export studio
wherever the last session left off). Identify it by its title text: «ورود داده…» means the import
screen, the conversation counters mean the workspace, «استودیو صدور اسناد» means the export studio.

### Step 2 — open the share-link import
On the workspace, the bottom-nav tab «ورود داده» is the one to tap (use the clickable/lowest rule).
If the app opened on the export studio, the way back is the «ورود داده» button there instead.
Confirm you landed on the import screen by the title «سامانه استخراج و پردازش مکالمات هوش مصنوعی».

### Step 3 — paste the link
Tap the field (find the `android.widget.EditText` node; there is exactly one on this screen), then
type the link **in ~18-character chunks with a short pause between each**. After typing, read the
field back and compare it character-for-character with the link you were given. If it differs, clear
it and retype in chunks — a truncated URL will import the wrong thing, or nothing, and you would
report a bug that is really a typing accident.

### Step 4 — extract
Tap «استخراج مکالمه» (it may be below the fold: scroll down one screen and dump again if you do not
see it). Then poll every 5 s, up to 90 s, watching for exactly one of:
- the workspace (conversation counters such as «پیامها» plus turn headers) → success
- a red card quoting layer numbers / «کلید» / «لایه» → the app told you why it failed
- nothing changing for 90 s → report `HANG_IMPORT`

If the import fails, **record the app's own words** — the app is designed to say which of its three
read layers answered and why. Those words are the defect report; a paraphrased guess is useless.

### Step 5 — read the workspace as a user would
From the dump, record:
- the session card's model line
- whether the «کامل خوانده نشد» banner is present
- the first two turn headers
- any text that claims something about the conversation you cannot verify by looking

### Step 6 — go to export and choose PDF
Tap «گام ۴: صدور اسناد» on the workspace step rail (or the «صدور اسناد» bottom-nav tab). You are on
the studio when the title «استودیو صدور اسناد چندگانه (Export Studio)» is visible. Then tap
«Adobe PDF (.pdf)». The button «صدور فوری سند …» must then read PDF — that is how you know the
choice took. Both this button and the export button sit below the fold: scroll and re-dump if needed.
Optionally tap «مشاهده پیش‌نمایش زنده ساختار سند» and record whether real document text appears
(for PDF it should show the header lines).

### Step 7 — export
Tap «صدور فوری سند (Adobe PDF (.pdf))», then poll for the success text «با موفقیت صادر شد» (up to
120 s). Record the file name it quotes, verbatim.

### Step 8 — prove the file really left the app
```
adb -s S shell ls -l /sdcard/Download/
adb -s S shell content query --uri content://media/external/downloads --projection _display_name:_size
```
Record the file name and its byte size. **A success card without a file in Downloads is a defect,
not a pass.**

## 6. Behaviours that are EXPECTED — do not file these as defects

- The workspace banner «این گفتگو کامل خوانده نشد؛ ۲۶ نوبت رد شد…» on the ChatGPT link. That is
  the app being honest about a partial read, and 26 of 64 turns really are non-text. It is correct.
- The session card showing the platform name (`ChatGPT`) instead of a model name. There is no model
  in the source page; inventing one was the bug we just removed.
- The live preview showing a plain file name for DOCX and PPTX. Their text lives inside a ZIP
  package and is not reachable as a string, so the app shows the name it will really write.
- A Claude link failing on one network and succeeding on another. The app tries three readers and
  reports each one's answer; a network-dependent outcome is a property of the network, and the
  app's own report of it is what we want.

## 7. How to report a defect back into Coder

Write one Markdown file per defect into **`docs/reports/agy/`**, named
`defect-<NN>-<short-slug>.md`, `<NN>` starting at 001. Use exactly this shape:

```markdown
# DEFECT <NN> — <one line: what the user sees>

- severity: blocker | major | minor
- step: <the step number from this plan>
- device: <serial>, screen <w x h>
- expected: <what a normal user would expect to happen>
- actual: <what actually happened — quote the screen text verbatim, do not translate it>
- evidence:
  - dump: <path to the .xml you saved>
  - command: <the exact adb command>
  - output: <the output you actually got, trimmed to the relevant lines>
- notes: <what you already ruled out, e.g. "retried twice, same result">
```

Severity, honestly: **blocker** = the user cannot get a PDF out at all; **major** = the user gets
a PDF but something visible is wrong or a claim on screen is false; **minor** = cosmetic.

Rules for the report:
- One file per defect. Do not fix anything.
- Quote what the screen said. Never translate Persian text into English inside quotes.
- If you are not sure it is a defect, still write the file and say so in `notes`.
- At the very end, your **final reply must be exactly one line**:
  `AGY-E2E DONE defects=<n> blockers=<n> pdf=<produced|not-produced> reports=<file names>`
  That single line is all the Coder session reads before it starts triaging on disk.

## 8. Where to save your own evidence
Put every `dump.xml`, every command output, and every screenshot you take in
`docs/reports/agy/` with a step prefix, e.g. `step4-before-tap.xml`. Do not put them anywhere else.

## 9. Hard stops
Stop immediately and write the report if: the target serial disappears; the app is not installed;
the app shows a system dialog you cannot dismiss; the device reboots itself; or anything asks you
for a password, a Google login, or a key you do not have.