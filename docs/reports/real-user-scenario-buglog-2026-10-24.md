# Real-User Scenario Buglog — 2026-10-24 (auto)

Reference link: `https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720`
Device: SM-A155F `R58X30GS9QJ` (Android 16, API 36) — VPN TUN up during the run
Build: `1.0` debug, commit `349e197` + local test edits

## Summary

| ID | Phase | Severity | Type | Module | Title | Repeat | Status |
|---|---|---|---|---|---|---|---|
| BUG-001 | 3 / 6 / 7 | S3 | a11y | General | English accessibility labels inside a Persian-only UI | always | fixed 2026-10-26 |
| BUG-002 | 6 | S3 | Function | Library | Imported conversation is missing from the library right after a successful import | always | fixed 2026-10-26 |
| BUG-003 | 7 | S4 | Look | Settings | Backup date uses Latin digits while the rest of the app uses Persian digits | always | fixed 2026-10-26 |

## Fix evidence (2026-10-26)

- **BUG-001** — all eight UI files now carry Persian `contentDescription` values. On the phone, `grep 'content-desc="[A-Za-z]'` over `docs/reports/raw/_fix1_start.xml` and `_fix3m_digits_on.xml` returns nothing, and the Persian labels `جستجو`, `حذف`, `ذخیره در کتابخانه`, `ذخیرهٔ گفتگو`, `رونوشت`, `نشانه‌گذاری` appear in the hierarchy.
- **BUG-002** — with the 40-turn draft still on disk, the library shows the card: `_fix2_library.xml` holds «گفتگوی باز», «۴۰ پیام — هنوز در کتابخانه ذخیره نشده» and both buttons. Tapping «ذخیره در کتابخانه» produced `_fix2_after_save.xml`: the card is gone and the counter moved from «گفتگوها (۲)» to «گفتگوها (۳)».
- **BUG-003** — the line now follows the app's own digits switch: with the switch on, `_fix3m_digits_on.xml` reads «آخرین پشتیبان: ۲۰۲۶-۱۰-۲۵»; switched off, `_fix3h_digits_off.xml` reads «آخرین پشتیبان: 2026-10-25». The switch was returned to its original on state afterwards.
- **Reviewer follow-ups applied** — the two card buttons got `overflow = TextOverflow.Ellipsis` so «ذخیره در کتابخانه» cannot be cut mid-glyph on a narrow panel at 130% font, and the backup date conversion is now conditional on the user's switch instead of always on.
- **Checks that ran** — `:app:assembleDebug` green, `:app:testDebugUnitTest` green, APK reinstalled on `R58X30GS9QJ` and all device evidence above captured after that install.

## Verified working (not bugs)

- Import of the reference link with the VPN on: 40 messages, 28,850 words, 38,947 tokens, 7 user / 33 assistant turns, 1 code block, **no** "not fully read" banner. Draft `resume_draft.json` = 300,671 bytes.
- Export PDF: `AIChat_جمع‌بندی_ایده_اپلیکیشن_20261025_224944.pdf`, 634,694 bytes, `is_pending=0`, present in both `/sdcard/Download` and app `files/documents`. Success card shows file name, size and the Download copy note.
- Export DOCX: `AIChat_جمع‌بندی_ایده_اپلیکیشن_20261025_225305.docx`, 106,836 bytes, present in `/sdcard/Download`.
- Settings: Google account card shows `sbedmon1@gmail.com`, all five themes render, the manual backup and restore buttons are present.
- Automation: the three scenario classes ran green on the phone (6 + 5 + 6 tests) with the VPN left running.

## BUG Forms

```
BUG-001
فاز / قدم:        فاز ۳، ۶ و ۷ — نوار پایین و نوار بالای همهٔ صفحه‌ها
ورودی:            هیچ؛ فقط دامپ سلسله‌مراتب صفحه
دستگاه / بیلد:    SM-A155F / Android 16 / build 1.0-debug (349e197)
عنوان کوتاه:      برچسب‌های دسترس‌پذیری انگلیسی وسط رابط فارسی
گام‌های بازتولید:
  1. هر صفحه‌ای را باز کن (محیط کار، کتابخانه، تنظیمات)
  2. دامپ سلسله‌مراتب بگیر
انتظار:           همهٔ content-desc ها فارسی باشند تا TalkBack فارسی بخواند
واقعیت:           Bookmark, New Session, Save Session, Sign Out, Search, Copy, Delete,
                  Edit, Save DB, Selected — همه انگلیسی‌اند
شاهد:             docs/reports/raw/_t10.xml و _g1.xml (بخش content-desc)
تکرار:            همیشه
شدت:              S3 — کارکرد اصلی سالم است، ولی کاربر نابینا یا تست‌نویس گمراه می‌شود
نوع:              ظاهر / دسترس‌پذیری
ماژول:            عمومی (ناوبری)
یادداشت تستر:      همین رشته‌ها دلیل شکست اولیهٔ تست‌های فاز ۱ هم بودند
پیشنهاد اولیه:    رشته‌های content-desc را به منابع فارسی ببر یا contentDescription فارسی بده
```

```
BUG-002
فاز / قدم:        فاز ۶ / قدم ۱ — کتابخانه بعد از واردکرد موفق
ورودی:            گفتگوی مرجع ۴۰ نوبتی که همین حالا وارد شده
دستگاه / بیلد:    SM-A155F / Android 16 / build 1.0-debug (349e197)
عنوان کوتاه:      گفتگوی واردشده در کتابخانه نیست و شمارنده «گفتگوها (۲)» می‌ماند
گام‌های بازتولید:
  1. لینک مرجع را وارد کن تا محیط کار ۴۰ پیام نشان دهد
  2. بدون زدن «ذخیره»، به تب کتابخانه برو
انتظار:           یا گفتگو در کتابخانه دیده شود، یا یک کارت «پیش‌نویس باز» با راهنمای ذخیره
واقعیت:           فقط دو گفتگوی قدیمی دیده می‌شود؛ هیچ نشانه‌ای از گفتگوی باز نیست
شاهد:             docs/reports/raw/_l1.xml
تکرار:            همیشه
شدت:              S3 — داده گم نمی‌شود ولی کاربر فکر می‌کند واردکرد ناموفق بوده
نوع:              کارکرد
ماژول:            کتابخانه
یادداشت تستر:      پیش‌نویس عمداً کتابخانه را آلوده نمی‌کند؛ ایراد در نبودِ نشانه است نه در ذخیره‌نشدن
پیشنهاد اولیه:    کارت «در حال کار» بالای کتابخانه با دکمهٔ «ذخیره در کتابخانه»
```

```
BUG-003
فاز / قدم:        فاز ۷ / قدم ۴ — کارت پشتیبان در تنظیمات
ورودی:            باز کردن تب تنظیمات با یک پشتیبان موجود
دستگاه / بیلد:    SM-A155F / Android 16 / build 1.0-debug (349e197)
عنوان کوتاه:      تاریخ پشتیبان با رقم لاتین نوشته می‌شود ولی بقیهٔ برنامه رقم فارسی دارد
گام‌های بازتولید:
  1. تب تنظیمات را باز کن
  2. خط «آخرین پشتیبان» را بخوان و با تاریخ کارت‌های کتابخانه مقایسه کن
انتظار:           هر دو به یک شکل (رقم فارسی)
واقعیت:           تنظیمات: 2026-10-25 — کتابخانه: ۲۰۲۶/۱۰/۰۶
شاهد:             docs/reports/raw/_g1.xml و _l1.xml
تکرار:            همیشه
شدت:              S4 — ظاهری
نوع:              ظاهر
ماژول:            تنظیمات
پیشنهاد اولیه:    همان مبدل ارقامی که کتابخانه استفاده می‌کند روی این تاریخ هم بگذار
```

## Observations (not product bugs)

- **Test harness, fixed by hand:** taps computed from a `TextView` label land on the text node, not on the clickable parent — the extract button's clickable area started one pixel below the text box, so the first tap missed and the next two landed on the soft keyboard and typed `3r` into the URL field. Driving must use the resource ids that the app already exposes: `shared_url_input`, `extract_shared_link_button`, `go_to_distill_btn`, `go_to_export_btn`, `format_card_*`, `execute_export_btn`, `tab_*`. The harness still uses text lookup; that is the next fix.
- **Force-stopping the app from inside its own instrumentation killed the runner** and produced a fake `Process crashed` report. The lifecycle test now only checks navigation; the kill half runs outside the runner in `scripts/real-user-scenario-run.sh`.
- **A green class is not proof the feature ran.** The first green run left no draft and no exported file; the evidence only appeared once the flow was driven with verified coordinates. Every future round must assert the artifact (draft size, file in Download), not just `OK (n tests)`.
