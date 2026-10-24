# Real-User Scenario Buglog — 2026-10-24 (auto)
Reference link: https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720
Device: emulator-5554 (LDPlayer, API 34) — SM-A155F notes where applicable
Build: (filled at run time from `adb shell dumpsys package com.aistudio.aichatexporter.kxrtva | grep versionName`)

## Summary
| ID | Phase | Severity | Type | Module | Title | Repeat | Status |
|---|---|---|---|---|---|---|---|
| _none yet_ |  |  |  |  |  |  | open |

## BUG Forms
<!-- append below -->

<!--
BUG template (from spec section 4) — copy, uncomment, and fill one per BUG:

```
BUG-007
فاز / قدم:        فاز ۲ / قدم ۲ — استخراج لینک مرجع
ورودی:            https://chatgpt.com/share/6adc95e5-031c-83ea-958d-3a70bfe98720  (سالم)
دستگاه / بیلد:    SM-A155F / Android 16 / build 1.2.3-debug (از Settings → درباره)
عنوان کوتاه:      بنر «کامل خوانده نشد» با لینک سالم آمد
گام‌های بازتولید:
  1. تب ورود → لینک بالا را چسباندم
  2. «استخراج مکالمه» زدم
  3. شمارنده آمد: 12 نوبت
انتظار:           بدون بنر، تب محیط کار باز شود
واقعیت:           بنر قرمز «این گفتگو کامل خوانده نشد…» آمد ولی 12 نوبت هم نمایش داده شد
شاهد:             اسکرین‌شات BUG-007.png + فایل لاگ کوتاه (بدون متن کامل گفتگو)
تکرار:            همیشه / گاهی / یک‌بار  →  همیشه
شدت:              S2  (کارکرد اصلی ناقص)
نوع:              کارکرد / داده / کرش / ظاهر / کارایی  →  کارکرد
ماژول:            ورود داده / محیط کار / پالایش / صدور / کتابخانه / تنظیمات / ناوبری  →  ورود داده
یادداشت تستر:    بنر درست است یا عدد نوبت‌ها؟ کدام را باور کنیم
پیشنهاد اولیه:   —
```
-->
