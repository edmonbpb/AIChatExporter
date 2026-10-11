# دورِ سخت‌سازی — ۲۰۲۶-۱۰-۱۰ (گزارشِ زنده)

پلن: `C:\\Users\\edmon\\.qoder\\plans\\golden-hill-drum.md` · قراردادِ تیم: `contracts/hardening-round-2026-10-10.md`
قاعدهٔ کار: تیمِ موازیِ ساب‌اجنت، حداکثر سه قلم در هر فراخوان، فایل‌های جدا؛ بیلد و دستگاه فقط کنترلر؛ هر ادعایِ اجنت روی دیسک دوباره اندازه گرفته می‌شود؛ هر رفتارِ تازه یک شکَنندۀ عمدی دارد که باید همان تست را قرمز کند.

این فایل به‌تدریج پر می‌شود؛ بخش‌های «در انتظار» تا پایانِ دور باز می‌مانند.

## ۱. موجِ ۱ — سه اجنتِ موازی (E1 صداقتِ داده، E2 پاک‌سازی، E3 حریم + ابزار)

### گیت‌های اندازه‌گیری‌شده

| گیت | عددِ اندازه‌گیری‌شده | شاهد |
|---|---|---|
| بیلدِ دیباگ | `BUILD SUCCESSFUL in 28m 29s`، ۳۹ تسک، همه executed (بی‌کش) | لاگِ بیلد، `app/build/outputs/apk/debug/app-debug.apk` mtime 03:09 |
| اثرِ حذفِ وابستگی | APK از ۲۱٬۴۹۸٬۷۷۴ به **۲۰٬۰۷۰٬۶۷۱** بایت (-۱٬۴۲۸٬۱۰۳) | `ls -la app/build/outputs/apk/debug/` |
| تستِ JVM (تازۀ بی‌کش) | **۶۷ تست، ۰ شکست، ۰ خطا** در ۶ کلاس؛ mtime همه ۰۳:۱۷:۰۸ | `app/build/test-results/testDebugUnitTest/*.xml` |
| نام presence (نه مقایسۀ فهرست) | `defaultModel` در `app/src`: **۰** تطبیق (پیش از کار: ۷ تعریف+فراخوان) | `grep -rn "defaultModel" app/src` |
| نام presence | `applyBiDiIsolation` / `toStandardDigits` / `generateWordDocx` / `generatePresentation` در `app/src`: **۰** | `grep -rn …` |
| نام presence | Retrofit/Moshi/logging-interceptor در گریدل و کاتالوگ: **۰**؛ `okhttp` همچنان: ۲ سطرِ اعلام + ۲ سطرِ کاتالوگ | `grep -rn -iE "retrofit\|moshi\|logging.interceptor" …` |
| دروازۀ متنِ فارسی | `tools/verify.ps1 -Stage 3` → PASS، **findings=0**، ZWNJ=۳۴۶۰، ۷۳ فایل اسکن + ۷۳ فایلِ شاهدِ دست‌نویس جدا | اجرایِ کنترلر ۰۳:۲۰ |
| دروازۀ بسته | `tools/verify.ps1 -Stage 4` → `package=com.aistudio.aichatexporter.kxrtva`، minSdk 24 / targetSdk 36 | همان اسکریپت |
| حریم خصوصی | `AndroidManifest.xml:9` = `android:allowBackup="false"`؛ `data_extraction_rules.xml` ۱۸ `<exclude>`، `backup_rules.xml` ۱۱ `<exclude>`، هر دو parse می‌شوند | `xml.dom.minidom` |

نام‌هایِ واقعیِ داده که در قواعدِ بکاپ نشست (از کد، نه حدس): `aichat_exporter.db` (`AppDatabase.kt:35`)، `aichat_auth_prefs.xml` (`MainViewModel.kt:79`)، `files/documents/` (`DocumentExporter.kt:43`)، `files/resume_draft.json` (`ResumeDraftStore.kt:53`).

## ۲. دفترِ قرمزِ عمدی (mutation ledger)

قاعده: شکَننده‌ای که سبز بماند یعنی جای درست ننشسته — آن‌وقت این **شکستِ شاهد** است، نه سبزیِ کد. موردِ ۴ دقیقاً همین شد و در همان جدول ثبت شده است.

```
mutation: `modelSlug = stated` → `modelSlug = modelName`   at ChatGptStreamDecoder.kt:122
expected: صفحه‌ای که مدل اعلام نکرده (auto) نباید برچسبِ مدل بگیرد
result:   red -> com.example.ChatGptStreamDecoderTest.autoModelSlugIsReportedAsUnstated
run:      fresh  tests=16 (2 classes) failures=1   artifact mtime 03:2x
restore:  md5 80e2d1c6… byte-identical
```

```
mutation: حذفِ `rejectedTurns++` از دروازۀ content_type   at ChatGptStreamDecoder.kt:293
expected: نوبتِ ردشده باید شمرده شود تا «کامل خوانده نشد» گفته شود
result:   red -> ChatGptStreamDecoderTest.nonTextPartsAreCountedAsRejectedTurns
               ChatGptStreamDecoderTest.cancelledToolAndSystemTurnsAreNotCounted
               HonestModelLabelTest.droppedTurnsNeverMakeAFullReadClaimComplete
run:      fresh  failures=3   (سه شاهد با یک شکَننده؛ یعنی هر سه روی همین مسیرند)
restore:  byte-identical
```

```
mutation: نقشۀ پاک‌سازی از CHATGPT به CUSTOM کلید خورد   at SharedLinkExtractor.kt:966
expected: برچسبِ جعلیِ قدیمیِ چت‌جی‌پی‌تی پاک شود ولی اسلاگِ واقعیِ صفحه بماند
result:   red -> HonestModelLabelTest.legacyInventedGptLabelIsScrubbedButRealSlugSurvives
run:      fresh  failures=1
restore:  byte-identical
```

```
mutation: قطعِ اتصالِ شمارش به مکالمه (`rejectedTurns = payload.rejectedTurns` → `= 0`)
          at SharedLinkExtractor.kt:576   — پیش از اصلاحِ تست
expected: ادعایِ اجنت: همان تستِ بالا قرمز می‌شود
result:   **NOTHING (witness failure)** — BUILD SUCCESSFUL، صفر شکست
run:      fresh  tests=16 failures=0
تشخیص:   تستِ «end to end» خودش دستی یک ChatSession می‌ساخت؛ کارخانۀ واقعیِ
          `sessionFromTurns` را صدا نمی‌زد، پس مسیرِ تولیدیِ برنامه هیچ شاهدی نداشت.
```

```
mutation: همان شکَنندۀ ۵۷۶، پس از اینکه تست به `SharedLinkExtractor.sessionFromTurns(payload, url)
          وصل شد (sessionFromTurns از private به internal)
expected: بنرِ ناقص‌بودن باید از عددِ واقعیِ همان مکالمۀ ساخته‌شدۀ برنامه بخواند
result:   red -> HonestModelLabelTest.droppedTurnsNeverMakeAFullReadClaimComplete
run:      A (سالم) BUILD SUCCESSFUL ۰۳:۳x · B (شکَنده) BUILD FAILED · C (بازگشت) BUILD SUCCESSFUL
restore:  md5 4d5e270f2a7a23befdd1d09fc4da037a — identical to pre-run copy
```

```
mutation: تراکنشِ جای‌گذاری‌شدۀ متن، برچسبِ مدلِ ساختگی بگیرد   at TranscriptIngestionEngine.kt:200
          (`modelSlug` → literal `"GPT-4o"`)
expected: متنِ کپی‌شده هرگز نباید مدلِ جعلی بگیرد
result:   red -> com.example.HonestModelLabelTest.pastedTranscriptTurnsCarryNoInventedModelTag
run:      fresh  classes=6 tests=67 failures=1   artifact mtime 04:09:23
restore:  byte-identical
```

```
mutation: آستانۀ «ناقُص» از `kept >= PARTIAL_IMPORT_MIN_TURNS` به `kept > …`   at SharedLinkExtractor.kt:995
expected: سه نوبتِ کامل باید «کامل» بماند و بنرِ ناقص نگیرد
result:   red -> com.example.HonestModelLabelTest.threeTurnsWithoutDropsIsNotPartial
run:      fresh  classes=6 tests=67 failures=1   artifact mtime 04:11:48
restore:  byte-identical
```

**جمعِ دفترِ قرمزِ عمدی برای E1:** شش شکَنندۀ جدا، شش قرمزِ اندازه‌گیری‌شده — اما فقط پس از اینکه یکی از آزمون‌ها که «پایان‌به‌پایان» نام داشت و شیء را خودش می‌ساخت، به کارخانۀ واقعیِ `sessionFromTurns` وصل شد. اجرایِ پایانیِ کلِ مجموعه با `--rerun-tasks`: **۶۷ تست، ۰ شکست** (mtime 04:15:24)، و فایل‌ها بایت‌به‌بایت با پیش از آزمون یکی‌اند. هیچ ادعایی در این دور با اجرایِ فیلترشده بسته نشده؛ دو اجرایِ پایانیِ فیلترشده فقط برای سرعت بودند و سبزیِ کل مجموعه جدا سنجیده شد.

> **تصحیحِ عدد (بازبین، ۲۰۲-۱۰-۱۰ ۰۵:۴۰):** شمارۀ بالا متعلق به انتهای موجِ ۱ بود و در جدولِ §۱ به‌عنوان عددِ دور نوشته شده بود. عددِ پایانیِ موجِ ۲: **۸۸ تست، ۰ شکست در ۷ کلاس** (mtime 05:26:58، `--rerun-tasks`). هر دو عدد نگه داشته شده‌اند تا ترتیبِ رشدِ پوشش گم نشود.

## ۳. چیزهایی که در همین دور تصحیح شد (نه اضافه، بلکه باطل‌کردنِ ادعا)

1. **ادعای «`gemini-3.5-flash` در فهرست مدل‌های گوگل نیست» باطل شد.** با کلیدِ خودِ پروژه: `GET /v1beta/models` → ۲۰۰ با ۶۲ مدل (۴۵ تای `gemini`)؛ `generateContent` برای `gemini-3.5-flash`، `gemini-2.5-flash` و `gemini-3.1-flash-lite-preview` هر سه ۲۰۰ (آخرین ۲۰۰ با `modelVersion=gemini-3.1-flash-lite`)؛ فقط `gemini-flash-latest` یک بار `503` ظرفیت داد. نقصِ واقعیِ جای دیگر است: `lastUsedModel` شناسۀ **درخواستی** را ثبت می‌کند نه آنچه سرویس شده (`GeminiClient.kt:123`، `:241`).
2. **ادعای «PPTX و HTML مرده‌اند» باطل شد:** هر دو از رابطِ صدور زنده‌اند (`ExportStudioScreen.kt:189-191` و `:223-225` → `DocumentExporter.kt:56`/`:58`). مرده‌ها دو تابعِ قدیمیِ HTML-مبنا بودند که حالا حذف شدند.
3. **۶۶ کاراکترِ سیریلیکِ همگلیف در چهار سندِ فارسی** (واژۀ «سوایپ» با `0432 0430 0439`) — بازرسی‌هایِ قبلیِ من فهرستِ سیاهِ باریک داشت و آن را ندید؛ اسکریپتِ تازه‌ای که با **بازۀ مجاز** کار می‌کند گرفتش. حالا: `findings=0` در ۱۴۶ فایل.
4. **پنج سندِ ادعایِ دروغِ BiDi داشتند** («درجِ RLM/LRM در مرزهای همۀ متون»): این کار فقط در `repairDefectsOnly` و فقط از مسیرِ پالایش اتفاق می‌افتد؛ `BidiText.kt` هرگز نشانگر درج نمی‌کند. `docs/03`، `docs/05`، `docs/07`، `docs/08` به همان محدودت تقلیل یافتند و خطِ درختِ `docs/05` که `DistillationEngine.kt` را موجود نشان می‌داد علامتِ «هرگز ساخته نشد» گرفت.

## ۵. لحظۀ خاموش‌شدنِ میزبان (2026-10-10 06:05) — وضعیتِ واقعیِ درخت

کاربر دستورِ خاموش‌کردن داد؛ دو اجنتِ اصلاحی (F1، F2) در میانهٔ کار متوقف شدند. این دفترچهٔ بازگشت است.

### چه چیزی سبز و تأییدشده است
- موجِ ۱ و ۲ (E1..E9b + اصلاحِ برچسبِ Claude): بیلدِ سبز و اجرایِ تازهٔ تست با **۸۸ تست، ۰ شکست** در ۷ کلاس، ساعت ۰۵:۲۶:۵۸ (`--rerun-tasks`).
- شواهدِ دستگاهیِ همان بیلد در `docs/reports/raw/hard-*`: بنرِ ناقص‌بودن با شمارشِ واقعی (۲۶ رد / ۳۸ خوانده) روی لینکِ زنده، صفرِ «GPT-4o»/«AI Assistant»/«Claude 3.5 Sonnet» در دامپ، بازگشت از تنظیمات به کتابخانه، DOCX و PPTXِ تولیدشده روی دستگاه که هر دو با پارسرِ واقعی خوانده شدند (۴ استایل، `pStyle`، ۶۱۷ اسلاید، صفر part بی‌قاعده)، و صفر خط در بافر کرش.

### چه چیزی تأییدنشده است (مهم)
ویرایش‌هایِ F1 و F2 **پس از** آن بیلد نشسته‌اند و هیچ بیلدِ سبزی پشتِ سرشان نیست. شمارشِ ساختاریِ خام (بدونِ کامنت/رشته) در پنج فایل عدم‌توازن نشان می‌دهد: `ImportScreen.kt`، `MainViewModel.kt`، `GeminiClient.kt`، `OpenXmlGenerator.kt`، `ExampleUnitTest.kt`. این شمارش با الگوهایِ درونِ رشته‌هایِ کوتلین (`${…}`، `"""`) خطا دارد، پس **نمی‌توان** از آن نتیجه گرفت که فایل‌ها شکسته‌اند؛ فقط می‌توان گفت کامپایل هنوز سنجیده نشده است.

### اول‌ین دستورِ session بعدی (به‌همین ترتیب)
1. `./gradlew.bat :app:assembleDebug --offline --console=plain` — اگر خطایِ کامپایل آمد، فایل و خط را با `path:line` بخوانید و همان تکهٔ نیمه‌کاره را تمام یا برگردانید (F1 روی ادعاهایِ «۱۰۰٪ محلی» و ذخیره‌کردنِ کلید Gemini کار می‌کرد؛ F2 روی `partialImportNotice`، شمارشِ لیست‌هایِ آفلاین، و دو ادعایِ کامنتیِ `OpenXmlGenerator`).
2. `./gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline` و خواندنِ `app/build/test-results/testDebugUnitTest/*.xml` (mtime + شمارش). `FROM-CACHE` سبز حساب نمی‌شود.
3. قرمزِ عمدیِ F2 (که هنوز اجرا نشده): آستانۀ `PARTIAL_IMPORT_MIN_TURNS`، شمارشِ لیست بالای سقفِ ۶، و حذفِ `lastUsedModel` در `chatRefine`.

### کارهایِ بازِ ثبت‌شده
- Task ۳۶: نشتِ رقمِ لاتین به رابط (فایل‌ها در گزارشِ بازبین فهرست شده‌اند) و تکرارِ پیامِ اعتبارسنجی در `MainViewModel.kt:485-486`.
- Task ۳۷: سومین نقطۀ خواندن — `LibraryScreen.kt:265` رشتهٔ «مدل: …» را از `entity.modelName` خام می‌سازد؛ روی دستگاه ثابت شد کارتِ کتابخانه هنوز «Claude 3.5 Sonnet» نشان می‌دهد.
- بازبینِ فقط‌خواندن (گزارش در گفتگو): ادعاهایِ «۱۰۰٪ آفلاین/محلی» در پنج نقطه، «کلید Gemini ذخیره شد» در حالی که فقط در حافظه بود، «حفظ ۱۰۰٪ لحن» روی کارتِ انتخابِ پالایش، «۰ نوبت رد شد» برای داده‌ای که هرگز شمرده نشده، سقفِ ۶ که شمارشِ لیست را هم trim می‌کرد، نبودِ ستونِ `rejectedTurns` در Room (کتابخانه آن را صفر بازمی‌گرداند)، و سند‌هایِ بیات (`docs/04`، `docs/09`، `docs/02`، `ALL_DOCS_COMBINED`).
- ماندۀ پلن: E8 (پیش‌نمایشِ زندهٔ استودیو صدور)، `androidTest` برای Room/PDF، `assembleRelease` (ساختِ keystore پیش از اجازه نیست)، بازتولیدِ `docs/ALL_DOCS_COMBINED.md`، و بستنِ ردیف‌های B-03/B-04 در `docs/09` (شواهدِ دستگاهی‌شان حالا موجود است).
- تصمیمِ بازِ رفتار: PPTXِ بدونِ سقف برای یک گفتگوی ۳۸ نوبتی **۶۱۷ اسلاید** می‌سازد (۱۴۱٬۸۴۶ کاراکتر، بدونِ حذفِ محتوا). باگ نیست؛ ولی برای ارائه قابل‌استفاده نیست و باید سقفِ هوشمند یا گزینه‌ای برایش تعیین شود.
- یادآوری: شمارۀ گوشیِ سامسونگِ متصل `R58X30GS9QJ` بود؛ شاخۀ «۴۰۱ِ بی‌کلید» هنوز روی گوشی شاهد نشده. `emulator-5554` پیش‌فرض است.


## ۴. ماندۀ ابتدایِ دور (بیشترش در همین دور بسته شد؛ برای تاریخچۀ ادعاها نگه داشته شده)

- E4: پاک‌سازیِ دادهٔ قدیمی در دو نقطۀ *خواندن*، بنرِ ماندگارِ «ناقُص» در `WorkspaceScreen` (بین `:249` و `:252`)، و حذفِ برچسبِ «AI Assistant» برای `modelTag == null` (`WorkspaceScreen.kt:431`) — تا این نباشد B-03 و B-04 تمامِ راه بسته نمی‌شوند.
- E4: `rejectedTurns` در `ResumeDraftStore.kt:80/:117` (بنرِ ناقص‌بودن باید از کشتنِ پروسه جان سالم به در ببرد).
- E5 استایلِ واقعی Word و سقفِ سه اسلاید PPTX · E6 دکمهٔ بازگشت و بوکمارک · E7 برچسبِ «نمونه» · E9 `modelVersion` و پالایشِ محتوامحور · E8 پیش‌نمایشِ زنده.
- دستگاه: `emulator-5554` (بنرِ ناقص، صفر «GPT-4o» در دامپ، سنجشِ دوبارهٔ Downloads، بازگشت از تنظیمات) و یک شاهد روی `SM-A155F` برای شاخۀ «۴۰۱ِ بی‌کلید».
- `androidTest` برای Room و PDF؛ `assembleRelease` (ساختِ keystore پیش از اجازه نیست)؛ بازتولیدِ `docs/ALL_DOCS_COMBINED.md`؛ بستنِ ردیف‌های B-03/B-04 در `docs/09`.
---

## 6. Wave 3 — team round, and a correction of my own measurement

Three specialists ran in parallel over disjoint file sets (contract in `.qoder/experts/contract.md`,
status board in `.qoder/experts/expert-team-board.html`). Row 11 (regenerating `docs/ALL_DOCS_COMBINED.md`)
was done by the controller with a new generator, `tools/make_combined.py`, so the fold is now one command
instead of a hand-paste.

### Landed and verified by reading the bytes
- **G2** `LibraryScreen.kt:270` now passes the stored model through `scrubInventedModel`;
  `MainViewModel.kt:508` and `:516` write a validation message to `urlExtractionError` only, not to the
  Snackbar as well. Both confirmed by direct read.
- **G4** all three filter chips in `WorkspaceScreen.kt:227/237/246` route the count through the
  `usePersianDigits` guard, matching the existing badge at `:177`. Zero foreign-script characters in the file.
- **G3** split each text exporter into a `build*Text` function plus a thin writer, so the studio preview and
  the written file now share one source; PDF/Markdown/HTML/JSON/TXT numbers route through `shownNumber`
  / `shownText`. DOCX and PPTX are ZIP containers and PDF is drawn on a canvas, so `previewLines` returns
  `null` for them and the card shows the real filename instead of a mock.
- **G1b** added `listLineStatisticCountsTheWholeTextAndNotTheCappedPrint` to `LocalDistillationTest.kt`,
  corrected the `slideChunks` KDoc to the measured round-trip behaviour, and deleted the unreachable
  `chunks.isEmpty()` branch.

### The correction: my own brace count was wrong
I measured `DocumentExporter.kt` as 80 open / 82 close braces and concluded it could not compile. I was
about to dispatch a specialist to fix a non-bug. `./gradlew :app:compileDebugKotlin --offline` returned
**BUILD SUCCESSFUL in 6m 37s**, so the file is valid. The fault was in my measuring script: it treated a
lambda brace inside a string template (for example `joinToString { ... }` within `${...}`) and the CSS
inside the multi-line `HTML_STYLE` raw string as stray braces. The CSS block is balanced on its own
(28 open / 28 close).

Lesson recorded: a hand-rolled brace/paren counter is not a substitute for the compiler, and a balance
mismatch deserves a compile before a fix. This is the second time in this session that a crude structural
check of mine produced a false alarm (the earlier one flagged five files before the first green build).

### Controller measurements that specialists could not make
Compiled and executed the real `slideChunks` with the cached Kotlin 2.2.10 compiler, splicing the function
verbatim out of the repo file: `"".lines().size == 1`, `" ".lines().size == 1`, and over 32 calls
`chunks.isEmpty()` never fired (so the deleted branch was genuinely dead) while the rejoin differed from the
input in **24 of 32** cases even with no over-wide line. That refuted the first specialist's reasoning that
`maxLines` does not cost the round trip, and the corrected KDoc was written from the measurement instead.
---

## 7. Gates actually run for wave 3 (controller, numbers from the run artifacts)

| gate | result |
|---|---|
| `gradlew :app:compileDebugKotlin --offline` | BUILD SUCCESSFUL in 6m 37s |
| `gradlew :app:testDebugUnitTest --rerun-tasks --offline` | BUILD SUCCESSFUL, **91 tests, 0 failures, 0 errors** in 7 classes, artifact mtime 18:02:22 (26 s old at read time) |

### Mutation ledger line for the new >6-list-lines test

```
mutation: gate both counters behind the print cap
         (numberedLineCount++ -> if (numbered.size < LOCAL_POINT_LIMIT) numberedLineCount++
          and the same for bulletLineCount++) at GeminiClient.kt:544 and :551
expected: an eight-line conversation is still reported as eight, only the printed points stay capped
result:   red -> com.example.LocalDistillationTest.listLineStatisticCountsTheWholeTextAndNotTheCappedPrint
          (11 tests completed, 1 failed)
run:      gradlew :app:testDebugUnitTest --tests com.example.LocalDistillationTest --offline
          BUILD FAILED in 4m 17s
restore:  md5 d8f346d7e38b76c1051cc099ead2fdff before and after -> byte-identical
control:  gradlew :app:testDebugUnitTest --offline -> BUILD SUCCESSFUL, 91 tests, 0 failures
          (artifact mtime 18:14:23)
```

### A falsification that failed for the wrong reason, and what it taught me

My first attempt at this break spliced the increment one line *after* the guard instead of inside it,
so the counters were still uncapped, the test passed, and I recorded BUILD SUCCESSFUL. That is the
hollow-witness signature: a break that does not land proves nothing about the test. I re-read the file,
found the real increment/guard line order (the increments come *before* the guard lines), applied the
break correctly, and only then got the red above. Two lessons recorded for next time: confirm the break
actually changed the guarded behaviour before believing a green run, and never name a red test you did not
read out of a result file.
---

## 8. Device round on SM-A155F (controller, adb dumps saved as hard3-*)

- `:app:assembleDebug --offline` -> BUILD SUCCESSFUL, APK 19.48 MB at 18:19:26.
- `adb -s R58X30GS9QJ install -r` -> Success. The UFI modem `6517e41c` was never addressed.
- Cold start restored the export studio (`hard3-phone-coldstart.xml`).
- **Live preview is real**: tapping the preview row flipped its label to «مخفیسازی پیشنمایش زنده»
  and rendered the actual first lines of the document that would be written — «مدل: ChatGPT • پلتفرم: ChatGPT»
  (`hard3-phone-preview-html.xml`). That is the HTML header built by the same function the exporter writes.
- The session card shows `مدل: ChatGPT`, the platform name, with no invented model anywhere in the dump.
- `previewLines` was then read from source to explain the branches: MARKDOWN/HTML/TXT/JSON each call their own
  `build*Text`, PDF prints its two header lines, and DOCX/PPTX return `null` on purpose because their text lives
  inside a ZIP package — so the card shows the real filename instead of a mock.

### Not verified on device
- Switching the format and re-reading the preview. The scroll state shifted between dumps and a tap landed on the
  navigation bar instead of the format card, so the before/after pair for a format change is still missing.
- The library card label and the partial-import banner were exercised on the emulator earlier in this round, not on
  the phone, and this phone has no Claude/ChatGPT draft loaded right now.

## 9. Post-F1/F2 device gate — link to PDF on `emulator-5554` (2026-10-11)

The tree that landed after F1/F2 was re-run end to end as a normal user, on the emulator, against
`lastUpdateTime=2026-10-10 23:36:20` (the build from section 7, not a stale one). Full write-up:
`docs/reports/agy/ctrl-device-run-2026-10-11.md`.

Result: the flow passes. ChatGPT share link in → 38 turns read → PDF out → file proven on disk and
structurally valid (`%PDF-1.4`, `startxref 2708840`, `%%EOF`, 174 leaf page objects, 2,719,942 bytes,
also visible through `content://media/external/downloads`). The exported bytes are md5-identical to
the pre-F1/F2 rehearsal export (`8afb0c3699d731e75e3023d9e1708459`), so the PDF writer is
deterministic and those two fixes changed nothing in the document.

What this gate settled or added in `docs/09_DEBUG_PLAN.md`:
- **B-۱۴ closed** with a live witness: the library card renders «مدل: Claude» for a Room row whose
  stored `modelName` is `Claude 3.5 Sonnet`.
- **B-۱۲ grew a site and lost a wrong citation**: the export studio's success card prints
  «حجم: 2.6 MB» in Latin digits with the Persian-digit switch ON, while the library card prints the
  same value as «۲.۶ MB». The register had pointed at `ExportStudioScreen.kt:339`, which is a
  `Column(...)` with no digits — the real line is `:448`.
- **B-۲۰ opened**: two exported PDFs are indistinguishable in «فایل‌های صادرشده» — the name is
  clipped to the same prefix and `ExportRecordCard` shows no timestamp.

Two things that looked like blockers and were not: the missing success message (the card is
persistent but sits below the fold — scrolling found it), and the whole run being driven without
`uiautomator` (see the next line).

Tooling note worth keeping: `uiautomator dump` fails for the rest of the emulator's life once a
dump process is killed — `IllegalStateException: UiAutomationService … already registered!` with no
`uiautomator` process in `ps -A`, i.e. a stale registration inside `system_server`. Rebooting is not
an option here, so the run asserted every screen with `exec-out screencap -p` and tapped at bounds
recovered from the rehearsal's saved dumps. `AGY-E2E-PLAN.md` should carry that fallback.
