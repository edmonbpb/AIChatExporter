# قراردادِ دورِ سخت‌سازی — ۲۰۲۶-۱۰-۱۰ (فقط‌خواندنی برای همهٔ اجنت‌ها)

این فایل را همهٔ اجنت‌های این دور می‌خوانند. تنها همین فایل مرجعِ مشترک است؛ خواسته را از حافظهٔ خودتان بازبنویسید نه از این متنِ خلاصه‌شده در prompt.

## ۰. قوانینِ بی‌ استثنا

1. **ممنوعه‌ها:** هیچ‌کد `gradle`/`gradlew` اجرا نشود. هیچ `adb`، دستگاه، شبیه‌ساز یا نصبِ APK. هیچ حذفِ فایل یا پوشه. هیچ تغییرِ `AndroidManifest.xml` خارج از lane‌ای که مالکش است.
2. **مالکیتِ فایل:** فقط فایل‌های laneِ خودتان را ویرایش کنید. اگر لازم شد فایلی بیرون از lane دست بخورد، **دست نزنید**؛ در گزارشِ پایانی بنویسید «نیازمندِ فلان lane» و تمام.
3. **فrozen (دست‌نزدنی، حتی برای فرمت‌بندی):** بلوکِ تیترِ چهارشاخه در `SharedLinkExtractor.kt` حدودِ خطوطِ ۲۲۱ تا ۲۵۵ (`val readerKeyAbsent` تا `val remedy = ...`)، توابعِ `cleanShareMarkdown` و `stripIconGlyphs`، و `ReaderRequestBudget` / `READER_TIER_MAX_REQUESTS` / `shouldRetryWithoutKey` / `shouldSendKeylessRetry` / `budgetRefusedOutcome` / `executeFinalResponse`.
4. **متنِ فارسی:** هر رشتهٔ کاربرپسند فارسی و راست‌به‌چپ است. نیم‌فاصله (`U+200C`) را در کدِ جاوا/کوین نه با escape بنویسید بلکه با کاراکترِ واقعیِ خودِ فارسی؛ پیش از نوشتنِ هر جمله، همان جمله را از یک فایلِ موجودِ پروژه بردارید و بازنویسی نکنید. بعد از هر Edit/Write، فایلِ خود را دوباره بخوانید و بگویید چند کاراکترِ بیگانه (Devanagari / CJK / Kana / Hangul) در متنِ فارسی افتاده — باید صفر باشد.
5. **عددِ فارسی در تست:** assertionها با رقمِ لاتین نوشته شوند؛ رقمِ فارسی فقط داخلِ رشته‌ای که کاربر می‌بیند و با `BidiEngine.toPersianDigits` ساخته می‌شود.
6. **گزارشِ پایانی:** سه بخشِ کوتاه: (الف) diff‌هایی که نوشتید با `path:line`، (ب) ادعاهایی که **نتوانستید** تأیید کنید چون بیلد اجرا نکردید، (ج) هر چیزِ بیرون از lane که لامسه شد. گزارش = نیت، نه شاهد؛ کنترلر روی دیسک چک می‌کند.

## ۱. زمینهٔ محصول

اپ «AIChatExporter» گفتگو را از لینکِ اشتراک (ChatGPT/Claude) یا متن/فایل می‌خواند، در «محیط کار» نشان می‌دهد، و به PDF/DOCX/PPTX/HTML/TXT/JSON/Markdown صادر می‌کند. baseline: ۵ کلاسِ تست JVM با ۵۹ متدِ سبز. پروژه git نیست، پس commit ندارد؛ تحویل با diffِ روی دیسک سنجیده می‌شود.

## ۲. lane‌ها

### E1 — صداقتِ داده (مدل + شمارشِ ردشده)
فایل‌ها: `app/src/main/java/com/example/data/model/PlatformPreset.kt`، `.../data/model/ChatSession.kt`، `.../domain/parser/ChatGptStreamDecoder.kt`، `.../domain/parser/TranscriptIngestionEngine.kt`، `.../domain/parser/SharedLinkExtractor.kt`، `app/src/test/java/com/example/ChatGptStreamDecoderTest.kt`، و فایلِ تازه `app/src/test/java/com/example/HonestModelLabelTest.kt`.

۱) **مدلِ صادق.** `defaultModel` را از `PlatformPreset.kt:6` و از آرگومان‌های هر enum entry حذف کنید (نه اینکه مقدارش را عوض کنید — حذف، خطایِ کامپایل می‌سازد و آن خودِ شاهد است). نقاطِ جعلِ موجود که باید درست شوند: `TranscriptIngestionEngine.kt:40` و `:195` و `:280`؛ `ChatSession.kt:9` (پیش‌فرضِ `modelName` شود `platform.displayName`)؛ `SharedLinkExtractor.kt:625` و `:692` و `:780` و `:818`. `ChatMessage.modelTag` همان `String?` می‌ماند و `null` یعنی «منبع اعلام نکرده». در `ChatGptStreamDecoder` یک `modelSlug: String? = null` به `SharedConversationPayload` (فیلدها در `:10-14`) اضافه کنید که در `:104` فقط اسلاگِ واقعاً-اعلام‌شده را بگیرد (`auto`/null → `null`)؛ مقدارِ `modelName` دست‌نخورده بماند تا چهار assertionِ حاضرِ `"ChatGPT"` در تست‌ها سبز بمانند. یک `fun scrubInventedModel(platform: PlatformPreset, stored: String?): String?` هم‌خانوادهٔ `LEGACY_INVENTED_EMAILS` بسازید که **فقط** `CHATGPT` + `"GPT-4o"` را به `null` تبدیل کند (اگر پلتفرمِ دیگری اضافه کنید `ResumeDraftStoreTest.kt:26,45,93` با `"DeepSeek-R1"` قرمز می‌شود).
۲) **شمردنِ ردشده‌ها.** `ChatSession` فیلدِ `val rejectedTurns: Int = 0` بگیرد (`0` یعنی «سنجیده نشد»، نه «کامل»). `SharedConversationPayload` هم `rejectedTurns: Int = 0` بگیرد. شمارش در `ChatGptStreamDecoder` **فقط** در دو نقطه افزایش یابد: جایی که `content_type != "text"` باعثِ `continue` می‌شود (حدودِ `:264`) و جایی که بدنۀ خالی رد می‌شود (حدودِ `:278`) — و هر دو **پس از** دروازه‌های نقش/سیستم/ابزار (حدودِ `:258` و `:261`)، تا نوبتِ ابزارِ لغوشده شمرده نشود. `SharedLinkExtractor.kt:569-575` موقعِ ساختِ session، `rejectedTurns = payload.rejectedTurns` بگذارد.
۳) **تابعِ خالصِ هشدار.** `internal fun partialImportNotice(session: ChatSession): String?` کنارِ `looksLikeConversation` در `SharedLinkExtractor.kt`: اگر `rejectedTurns > 0 || messages.size < 3` باشد یک جملهٔ فارسیِ کامل با عددِ هر دو طرف برگرداند (با `BidiEngine.toPersianDigits`)، وگرنه `null`. این تابع هیچ Compose و هیچ Android API نباید مصرف کند. **کارِ lane بعدی است، نه شما:** `MainViewModel.kt` و `WorkspaceScreen.kt` و `ResumeDraftStore.kt` را دست نزنید.

تست‌های لازم (در `ChatGptStreamDecoderTest.kt` و `HonestModelLabelTest.kt`): `nonTextPartsAreCountedAsRejectedTurns`، `cancelledToolAndSystemTurnsAreNotCounted`، `autoModelSlugIsReportedAsUnstated`، `pastedTranscriptTurnsCarryNoInventedModelTag`، `legacyInventedGptLabelIsScrubbedButRealSlugSurvives`، `droppedTurnsNeverMakeAFullReadClaimComplete`، `threeTurnsWithoutDropsIsNotPartial`، `noticeUsesPersianDigits`.

### E2 — پاک‌سازی
فایل‌ها: `app/src/main/java/com/example/domain/bidi/BidiEngine.kt`، `app/build.gradle.kts`، `gradle/libs.versions.toml`، `app/src/main/java/com/example/domain/exporter/DocumentExporter.kt`.

۱) `BidiEngine.kt:98 applyBiDiIsolation` (صفر فراخوان)، `:139 toStandardDigits` (صفر فراخوان) و `:158 result = result.replace("", "")` (بی‌اثر) حذف شوند. `BidiText.kt:36-37` فقط `normalizePersianTypography` و `toPersianDigits` را صدا می‌زند — پس بعد از حذف، آن فایل باید همچنان کامپایل شود؛ اگر کامپایلِ `BidiText.kt` به چیزی نیاز داشت که در lane شما نیست، گزارش کنید.
۲) از `app/build.gradle.kts:99-104` این پنج سطر حذف شوند: `libs.retrofit`، `libs.converter.moshi`، `libs.moshi.kotlin`، `ksp(libs.moshi.kotlin.codegen)`، `libs.logging.interceptor`. `okhttp` بماند (واقعاً در `SharedLinkExtractor.kt:355-357` مصرف می‌شود). سطرهایِ بی‌مصرفِ معادل در `gradle/libs.versions.toml` هم حذف شوند. مبنای این کار: grep در `app/src/main/java/com/example` برای `retrofit2|Retrofit|moshi|Moshi|HttpLoggingInterceptor` = صفر تطبیق.
۳) دو تابعِ privateِ بی‌فراخوان در `DocumentExporter.kt` حذف شوند: `:92 generateWordDocx` و `:396 generatePresentation`. `when` در `:53-61` هر هفت فرمت را پوشش می‌دهد و به این دو نیازی ندارد. هیچ فرمتِ کاربرپسندی حذف نشود.

### E3 — حریمِ خصوصی + ابزارِ یک‌جا
فایل‌ها: `app/src/main/AndroidManifest.xml`، `app/src/main/res/xml/data_extraction_rules.xml`، `app/src/main/res/xml/backup_rules.xml`، فایلِ تازه `tools/verify.ps1`، و (فقط افزودنِ یک خطِ متنی) `app/src/main/java/com/example/ui/screens/SettingsScreen.kt` — اگر SettingsScreen در laneِ دیگری قفل شد، متن را در گزارش بگذارید و دست نزنید.

۱) `AndroidManifest.xml:9` شود `android:allowBackup="false"`؛ دو فایلِ `res/xml/*` از قالبِ خالیِ TODO دربیایند و قواعدِ واقعی (exclude برای دیتابیس Room، SharedPreferences و پوشهٔ اسناد) بنویسند.
۲) شفاف‌سازیِ مسیرهایِ برون‌رفتِ داده در تنظیمات: لینکِ اشتراک → `r.jina.ai` و `api.allorigins.win`؛ متنِ کامل مکالمه → Gemini. آرشیوِ محلیِ ZIP/JSON که خودِ کاربر می‌زند دست‌نخورده است.
۳) `tools/verify.ps1` با چهار مرحله و خروجیِ PASS/FAILِ عددی: `:app:assembleDebug`، `:app:testDebugUnitTest`، بازرسیِ یکپارچگیِ متنِ فارسی روی `app/src` + `docs` (کاراکترهایِ بیگانه خارج از بازهٔ لاتین/فارسی-عربی/نیم‌فاصله/علائمِ مجاز = باید صفر باشد؛ شمارشِ `U+200C` فقط گزارش شود)، و `aapt dump badging` روی APK با تأییدِ نامِ بسته `com.aistudio.aichatexporter.kxrtva`. اسکریپت باید روی Windowsِ PowerShellِ ۵ کار کند، با `Set-Location $PSScriptRoot/..` شروع شود و هیچ‌چیز را حذف نکند. `tools/uidump.sh` را در خروجی معرفی کنید (دستگاه فقط کنترلر).

### E4 — رابطِ صادق (پس از E1)
فایل‌ها: `app/src/main/java/com/example/ui/viewmodel/MainViewModel.kt`، `app/src/main/java/com/example/ui/screens/WorkspaceScreen.kt`، `app/src/main/java/com/example/data/local/ResumeDraftStore.kt`، `app/src/test/java/com/example/ResumeDraftStoreTest.kt`.

۱) `scrubInventedModel` از E1 در دو نقطهٔ **خواندن** مصرف شود: بازگردانیِ پیش‌نویس و بارگذاریِ کتابخانه (محل‌های دقیق را خودتان در فایل پیدا کنید؛ الگو: `LEGACY_INVENTED_EMAILS` در `MainViewModel.kt:70`). **هیچ مهاجرتِ Room نسازید** — `AppDatabase.kt:16-17,36` روی `version = 1` با `fallbackToDestructiveMigration()` است و افزایشِ نسخه کتابخانهٔ کاربر را پاک می‌کند.
۲) بلاکِ موفقیتِ `MainViewModel.kt:503-515`: Snackbar به یک خطِ کوتاهِ بی‌خطRTF کاهش یابد؛ تکرارِ پیام در مسیرِ خطا (`:519`) حذف شود؛ و یک بنرِ **ماندگار** در `WorkspaceScreen.kt` (بین `:249` و `:252`) از `partialImportNotice(activeSession)` رندر شود — بی‌`maxLines` و بی‌ Snackbar. `urlExtractionError` باید فقط «خطا» بماند تا کارتِ قرمزِ `ImportScreen.kt:465-515` معنایش را از دست ندهد. حباب‌ها (`WorkspaceScreen.kt:431`) وقتی `modelTag == null` پرانتزِ مدل را نشان ندهند.
۳) `ResumeDraftStore.kt`: `rejectedTurns` در `:80` نوشته و در `:117` با `optInt("rejectedTurns", 0)` خوانده شود تا پیش‌نویسِ قدیمی بالا بیاید. تست‌ها: `partialImportCountsSurviveTheDraftRoundTrip` و `oldDraftWithoutCountsStillLoads`؛ مراقبِ `assertEquals(session, restored)` در `ResumeDraftStoreTest.kt:102` باشید.

### E5 — OOXML (موجِ ۲)
فایل‌ها: `app/src/main/java/com/example/domain/exporter/OpenXmlGenerator.kt`، `app/src/test/java/com/example/ExampleUnitTest.kt`.
۱) `buildStylesXml()` (`:110-129`) چهار `<w:style>` بگیرد: `Heading1`، `Heading2`، `Body`، `Code`، با `outlineLvl` برای تیترها؛ پاراگراف‌هایِ تیتر در `:222` و `:244` به‌جای `<w:b/>` + `<w:sz w:val="28"/>` از `<w:pStyle w:val="...">` استفاده کنند. `w:bidi`، `w:jc` و `w:lang fa-IR` حفظ شود. ۲) سقفِ سه اسلاید برداشته شود: `:70-72` فهرستِ partها را از شمارۀ اسلایدها بسازد، `:316-318` و `:328-330` (Content-Types و rels) پویا شوند، و `buildPptxCodeSlideXml` (`:385`) به‌جای برشِ ۵۰۰ کاراکتری، بلوکِ کد را روی چند اسلاید بشکند. ۳) تست‌هایِ zip: XMLهایِ هر part را با یک parserِ واقعی parse کنید و گرافِ part/rel را بشمارید. **صادق:** روی این ماشین PowerPoint/Word اجرا نمی‌شود، پس ادعای «باز شد در آفیس» ننویسید.

### E6 — ناوبری + کتابخانه (موجِ ۲)
فایل‌ها: `app/src/main/java/com/example/MainActivity.kt`، `app/src/main/java/com/example/ui/screens/LibraryScreen.kt`.
۱) `MainActivity.kt:169-190`: بازگشت از LIBRARY/SETTINGS نباید محیطِ کار را بی‌اجازه باز کند (`:171-176` الان همه را به WORKSPACE می‌فرستد). حداقلِ مکانیزم: یک «صفحهٔ قبل» در ViewModel (فیلدها را با E4 هماهنگ کنید؛ اگر E4 تمام شده، آن فیلد موجود است). ۲) دکمهٔ بوکمارک روی کارت‌هایِ کتابخانه از تابعی که در ViewModel آماده است (`toggleBookmark...`)؛ باید per-card id بگیرد نه session فعال. ۳) هیچ‌کدام `verticalScroll` موجود را نشکنند.

### E7 — پالایش + ورود (موجِ ۲)
فایل‌ها: `app/src/main/java/com/example/ui/screens/AuthScreen.kt`، `app/src/main/java/com/example/ui/screens/DistillationScreen.kt`.
۱) ورودِ ایمیل/رمز برچسبِ «نمونه/آزمایشی» بگیرد (Firebase Auth در دامنه نیست). ۲) پالایشِ آفلاینِ محتوامحور **کارِ شما نیست**؛ موتورِ آن در `GeminiClient.kt` است و lane E9 مالک آن فایل است — شما فقط متن/حالتِ رابطِ `DistillationScreen.kt` را روشن کنید. ۳) هر رشتهٔ تازه با `AdaptiveLabel` (`.../ui/components/BidiText.kt:139`) محافظ شود؛ گیتِ متنیِ پروژه می‌گوید هر برچسبِ دکمه باید محافظ داشته باشد (۴۳/۴۳ در دورِ قبل).

### E9 — `GeminiClient.kt` (راستیِ مدل + پالایشِ آفلاین) — موجِ ۲
فایل‌ها: `app/src/main/java/com/example/data/remote/gemini/GeminiClient.kt` و یک فایلِ تستِ تازه `app/src/test/java/com/example/LocalDistillationTest.kt`.
۱) `lastUsedModel` در `:123` و `:241` مقدارِ `modelName`ِ **درخواستی** را می‌گذارد، در حالی که پاسخِ API فیلدِ `modelVersion` دارد؛ این را از بدنهٔ پاسخ بخوانید و همان را نمایش دهید (اندازه‌گیریِ واقعی: درخواستِ `gemini-3.1-flash-lite-preview` با `modelVersion=gemini-3.1-flash-lite` پاسخ داد). اگر پاسخ `modelVersion` نداشت، همان مقدارِ درخواستی بماند.
۲) پالایشِ آفلاینِ محتوامحور: `generateLocalSmartDistillation` (`:324`) در شاخۀ `EXECUTIVE_SUMMARY` (`:360`) امروز `firstUser.take(300)` و نکاتی از پیش نوشته می‌دهد. آن را به استخراجِ واقعیِ محتوایِ همین مکالمه تبدیل کنید: چگالیِ واژگان، جمله‌هایِ آغازینِ نوبت‌ها، الگوهایِ شماره‌دار/بولتِ موجود در متن، و شمارشِ بلوک‌هایِ کد. هیچ عددِ ثابتی در رشته‌ها نماند که به دادهٔ ورودی وابسته نیست. متن‌هایِ فارسیِ تازه را از همین فایل بردارید و بعدش بازرسیِ کاراکتر انجام دهید.
۳) تست‌ها روی تابعِ خالصِ فارسی‌ساز (بدونِ شبکه): یک مکالمۀ سه‌نوبتی با دو بلوکِ کد بسازید و ادعا کنید شمارۀ نوبت، شمارۀ بلوکِ کد و واژۀ پرتکرار در خروجی آمده؛ سپس ادعایِ ثابت‌بودنِ خروجی را با دو ورودیِ متفاوتِ مختلف بسنجید.
**تصیحیحِ یک ادعایِ قبلی:** این سه شناسهٔ موجود در `:25-28` واقعاً در API گوگل هستند و پاسخ دادند (`gemini-3.5-flash` ۲۰۰، `gemini-2.5-flash` ۲۰۰، `gemini-3.1-flash-lite-preview` ۲۰۰؛ `gemini-flash-latest` یک بار ۵۰۳ِ ظرفیت داد). پس شناسه‌ای برای «تعویضِ شناسهٔ جعلی» وجود ندارد؛ کارِ این lane فقط خواندنِ `modelVersion` و مرتب‌کردنِ ترتیبِ تلاش است.


### E8 — استودیو صدور (موجِ ۳، بعد از E5 و E7)
فایل‌ها: `app/src/main/java/com/example/ui/screens/ExportStudioScreen.kt`، `DocumentExporter.kt`، `MainViewModel.kt`. پیش‌نمایشِ زنده از چند خطِ اولِ همان فایلی که تولید شده ساخته شود؛ پذیرش: تغییرِ فرمت یا عنوان، پیش‌نمایش را تکان می‌دهد.

## ۳. گیتِ کنترلر (بعد از هر موج)

1. بیلد: `./gradlew :app:assembleDebug --offline` سپس `./gradlew :app:testDebugUnitTest --rerun-tasks --offline`؛ `FROM-CACHE` سبز حساب نمی‌شود؛ mtimeِ `app/build/test-results/testDebugUnitTest/*.xml` خوانده می‌شود.
2. قرمزِ عمدی (شکَنندۀ عمدی باید همان تست‌های نامیده را قرمز کند؛ اگر سبز ماند، شکَننده جای درست ننشسته): حذفِ `++` شمارش در decoder؛ بردنِ شمارش بالای دروازه‌های `:258/:261`؛ `modelSlug = modelName` در `:104`؛ کوچک‌کردنِ کلیدِ نقشۀ scrub؛ `return null` وقتی `rejectedTurns > 0`؛ حذفِ `put("rejectedTurns")` در `ResumeDraftStore.kt:80`؛ در E5: `pStyle` برگردانده به `<w:b/>` و شمارۀ اسلایدها هاردکدِ ۳.
3. هر ادعایِ عددی در گزارشِ اجنت (تعدادِ تست، تعدادِ تطبیق، درصد) روی دیسک دوباره اندازه گرفته می‌شود. ادعایِ «ندارد/نیست» با کوئریِ شاهد روی هدفِ قطعاً-موجود سنجیده می‌شود.
4. شواهدِ دستگاهی در `docs/reports/raw/` با پیشوندِ `hard-`؛ فقط کنترلر لمس می‌کند.
