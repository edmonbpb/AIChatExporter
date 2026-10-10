# Controller device run — link to PDF on the post-F1/F2 build (2026-10-11, emulator-5554)

This is the run the plan in `AGY-E2E-PLAN.md` describes, executed by the Coder session on the build
that landed after F1/F2. The flow passed. Two register rows got device evidence and one new minor
defect was measured.

## Environment, asserted not assumed

| check | measured |
|---|---|
| devices attached | `6517e41c` (UFI modem — untouched, no command sent to it), `emulator-5554` (SH-M24) |
| app | `versionName=1.0`, `lastUpdateTime=2026-10-10 23:36:20` — the build from the fresh gate, not a stale one |
| screen | `1080x1920` |
| focused activity | `com.aistudio.aichatexporter.kxrtva/com.example.MainActivity` |

## What happened, step by step

| step | result | witness |
|---|---|---|
| cold start | workspace restored with the previous ChatGPT conversation still open (P0-1 holds) | `C:/tmp/gate1.png` |
| open import | bottom-nav «ورود داده» → title «سامانه استخراج و پردازش مکالمات هوش مصنوعی» | `C:/tmp/gate2.png` |
| type the link | 62-char ChatGPT share URL typed in 16-char chunks; head, middle and tail each read back on screen | `C:/tmp/gate3.png`, `C:/tmp/gate6.png`, `C:/tmp/gate7.png` |
| extract | «در حال دریافت…» then «تحلیل ساختار داده‌های مکالمه…», then the workspace | `gate-step4-extracting.png` |
| workspace | «مدل: ChatGPT», «پیام‌ها: ۳۸», «کلمات: ۱۶۱», «توکن: ۲۱۸۶۰», banner «این گفتگو کامل خوانده نشد؛ ۲۶ نوبت رد شد و تنها ۳۸ نوبت از آن خوانده شد», chips «فقط کدها (۲۲۶)» and «پرسش‌های کاربر (۱۹)» | `gate-step5-workspace.png` |
| choose PDF | «Adobe PDF (.pdf)» card takes the selection border and the action button re-reads «صدور فوری سند (Adobe PDF (.pdf))» | `gate-step6-pdf-selected.png`, `gate-step6-export-button.png` |
| export | persistent success card «سند با موفقیت آماده شد!» quoting the file name, format and size | `gate-step7-success-card.png` |
| file really left the app | `/sdcard/Download/AIChat_فایل_های_لازم_پروژه_20261011_002024.pdf`, 2,719,942 bytes, also listed by `content://media/external/downloads` as row 7 | command output below |
| file is a real PDF | header `%PDF-1.4`, `startxref 2708840`, trailer `%%EOF`, 174 leaf `/Type /Page` objects (200 `/Type /Page` matches minus 26 `/Type /Pages`) | `gate-exported-20261011.pdf` |

Byte-identical to the rehearsal export: `md5 8afb0c3699d731e75e3023d9e1708459` for both
`gate-exported-20261011.pdf` and `ctrl-exported.pdf`. Same input, same output — the PDF writer is
deterministic, and F1/F2 did not change what it writes.

## Register rows this run settles

- **B-۱۴ closed.** The library conversation card now reads «مدل: Claude | تاریخ: ۲۰۲۶/۱۰/۰۹ ۰۵:۳۳»
  for the row whose stored `modelName` is literally `Claude 3.5 Sonnet` (`aichat_exporter.db`, row
  `65f810dd-…`). The invented-model scrub is live at the third reading site, on the device, not just
  in a unit test. Witness `gate-step8-library-records.png` is the «گفتگوها (۱)» tab of the same run.
- **B-۱۲ gets a new site and a corrected citation.** The success card prints «حجم: 2.6 MB» in Latin
  digits while the «تبدیل اعداد به ارقام فارسی (۰-۹)» switch is ON on the very same screen, and while
  the library's export card prints the same value as «۲.۶ MB». So the same number is rendered two
  ways by two screens. The register pointed at `ExportStudioScreen.kt:339`, which is a `Column(...)`
  with no digits in it — the real line is `ExportStudioScreen.kt:448`.

## New minor defect (B-۲۰)

Two exported PDFs sit in «فایل‌های صادرشده (۴)» as two cards whose visible text is identical: the
name is clipped to «AIChat_فایل_های_لازم_پروژه_20…» and the only other line is «Adobe PDF (.pdf) •
۲.۶ MB». Nothing on the card distinguishes them, so a user cannot tell which one to open or delete.
`ExportRecordCard` (`LibraryScreen.kt:300-354`) renders file name + format + size and no timestamp,
even though the record has one.

Related, same screenshot family: in the success card the file name renders as
«pdf.002024_20261011_فایل_های_لازم_پروژه_AIChat» — the stored string is correct, but the bidi
placement of the trailing `.pdf` makes it read from the wrong end, which matters if a user retypes
the name from the screen.

## Two things that looked like blockers and were not

- **`uiautomator dump` fails all run long** with `IllegalStateException: UiAutomationService …
  already registered!`, thrown from `DumpCommand.run`, and `ps -A` shows no `uiautomator` process.
  The registration is stale inside `system_server` after an earlier `killProcess` of a dump. A
  reboot would clear it and is forbidden here, so this run used `exec-out screencap` for every
  assertion and the rehearsal's saved dumps for coordinates. Consequence for the outside agent: the
  plan's "dump, then tap" loop has a fallback path — screenshot plus previously-verified bounds.
- **No success message after export.** The first export looked silent because the success card sits
  below the fold; scrolling down showed it had been there the whole time. It is a persistent card,
  not a Snackbar, so it does not expire.

## Reproduce

```
adb -s emulator-5554 shell am force-stop com.aistudio.aichatexporter.kxrtva
adb -s emulator-5554 shell am start -n com.aistudio.aichatexporter.kxrtva/com.example.MainActivity
adb -s emulator-5554 shell input tap 904 1824          # «ورود داده»
adb -s emulator-5554 shell input tap 540 1455          # URL field
# type the 62-char link in 16-char chunks, then verify head/middle/tail by swiping the field
adb -s emulator-5554 shell input swipe 780 1465 240 1465 500   # scroll field to read the tail
adb -s emulator-5554 shell input tap 355 1043          # «استخراج مکالمه» after one scroll
adb -s emulator-5554 shell input tap 407 1457          # «گام ۴: صدور…»
adb -s emulator-5554 shell input tap 290 940           # «Adobe PDF (.pdf)»
adb -s emulator-5554 shell input swipe 540 1300 540 500 500    # x2, then
adb -s emulator-5554 shell input tap 605 1482          # «صدور فوری سند (Adobe PDF (.pdf))»
adb -s emulator-5554 shell ls -l /sdcard/Download/
```
