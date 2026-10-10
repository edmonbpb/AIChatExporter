# 📐 سند مشخصات فنی و سیستمی (System & Technical Specifications - SPEC)
## پروژه: AIChatExporter

---

### ۱. مشخصات پلتفرم و پیش‌نیازهای محیط اجرا (Platform Specifications)
* **سیستم‌عامل هدف:** Android 7.0 (API Level 24) تا Android 16 (API Level 36)؛ `compileSdk` روی نسخهٔ ۳۶ با سطح جزئی ۱ تنظیم شده است.
* **معماری پردازنده:** `arm64-v8a`, `armeabi-v7a`, `x86_64` (هیچ کتابخانهٔ بومی در APK نیست؛ خروجی `universal` است).
* **فریم‌ورک رابط کاربری:** Jetpack Compose با BOM `2024.09.00` و Material 3.
* **زبان برنامه‌نویسی:** Kotlin `2.2.10` با پلاگین KSP `2.3.5` و AGP `9.1.1` (Gradle 9.3.1).
* **موتور رندرینگ متون:** موتور اختصاصی `BidiEngine` (تشخیص جهت، نیم‌فاصله، ارقام فارسی، اصلاح ی/ک عربی).
* **پایگاه داده محلی:** SQLite مدیریت‌شده توسط AndroidX Room `2.7.0` با پردازشگر KSP.
* **موتور شبکه:** OkHttp `4.10.0` با تایم‌اوت اتصال ۴۵ ثانیه.
* **قلم فارسی:** Vazirmatn (سه وزن) از `res/font`؛ پروانه در `docs/licenses/Vazirmatn-OFL.txt`.
* **رازها:** `GEMINI_API_KEY` و `GOOGLE_CLIENT_ID` از `.env` به `BuildConfig` تزریق می‌شوند (پلاگین secrets). `.env` در گیت نیست.

---

### ۲. مشخصات و قراردادهای API و پروتکل‌ها (API & Interface Contracts)

#### ۲.۱. قرارداد استخراج لینک اشتراک‌گذاری (Ingestion Engine Contract)
```kotlin
interface SharedLinkExtractorContract {
    /**
     * بررسی معتبر بودن URL چه با پروتکل http/https و چه بدون آن
     */
    fun containsUrl(text: String): Boolean

    /**
     * استخراج و افزودن خودکار پروتکل به آدرس خام
     */
    fun normalizeUrl(url: String): String

    /**
     * شناسایی پلتفرم بر اساس نام دامنه (ChatGPT, Claude, Perplexity, DeepSeek, Gemini)
     */
    fun detectPlatformFromUrl(url: String): PlatformPreset

    /**
     * استخراج چندلایه‌ای با گزارش پیشرفت بلادرنگ به رابط کاربری
     */
    suspend fun extractFromUrl(
        url: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<ChatSession>
}
```

#### ۲.۲. قرارداد موتور پالایش هوشمند (Distillation Engine Contract)
```kotlin
interface DistillationEngineContract {
    suspend fun distill(
        session: ChatSession,
        type: DistillationType,
        customInstruction: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<DistillationResult>
}

enum class DistillationType(val titleFa: String, val promptDirective: String) {
    EXECUTIVE_SUMMARY("خلاصه مدیریتی", "Generate a concise executive summary in Persian focusing on decisions and facts."),
    KEY_TAKEAWAYS("نکات کلیدی و اقدامات", "Extract structured bullet points and action items with clear responsibilities."),
    QA_DIGEST("پرسش و پاسخ فشرده", "Consolidate user queries and assistant direct solutions eliminating chit-chat."),
    CLEAN_TONE("پاکسازی زوائد و لحن", "Remove AI pleasantries, disclaimer disclaimers, and reformat into direct technical text."),
    PROMPT_TEMPLATE("استخراج متدولوژی پرامپت", "Reverse engineer the conversation structure into reusable prompt templates.");
}

data class DistillationResult(
    val type: DistillationType,
    val markdownOutput: String,
    val wordCount: Int,
    val generatedAt: Long = System.currentTimeMillis()
)
```

> **تطبیق با کد (۲۰۲۶-۱۰-۰۶):** هیچ‌یک از این سه «قرارداد» به‌صورت `interface` در پروژه وجود ندارد؛ معادل‌های واقعی:
> `SharedLinkExtractor.containsUrl / normalizeUrl / extractUrl / detectPlatformFromUrl / extractFromUrl`،
> `GeminiClient.distillSession / chatRefine / generateRawContent / hasValidApiKey`،
> و `DocumentExporter.exportDocument(context, session, distillationContent, options): Result<ExportRecord>` به‌همراه `OpenXmlGenerator.generateRealDocx` و `OpenXmlGenerator.generateRealPptx`.
> `DistillationType` هم هفت مقدار دارد (`REPAIR_DEFECTS_ONLY`, `FULL_EDITED`, `EXECUTIVE_SUMMARY`, `TECHNICAL_DOC`, `QA_FLASHCARDS`, `PURE_CODE`, `CUSTOM_REWRITE`) و به‌جای `promptDirective` سه فیلد `titleEn`، `descriptionFa` و `iconEmoji` دارد. متن prompts در `DistillationPrompts.kt` نگه داشته می‌شود.
> `ExportFormat` هفت مقدار دارد: `DOCX`, `PDF`, `PPTX`, `MARKDOWN`, `HTML`, `TXT`, `JSON`.

#### ۲.۳. قرارداد موتور تولید اسناد (Document Generator Contract)
```kotlin
interface DocumentGeneratorContract {
    /**
     * صدور به فرمت Microsoft Word استاندارد با فشرده‌سازی ZIP و استایل OpenXML
     */
    fun generateWordDocument(
        session: ChatSession,
        distillation: DistillationResult? = null,
        style: DocumentStylePreset = DocumentStylePreset.OFFICIAL
    ): File

    /**
     * صدور به فرمت PDF استاندارد با ابعاد A4 و سربرگ رسمی
     */
    fun generatePdfDocument(
        session: ChatSession,
        distillation: DistillationResult? = null,
        style: DocumentStylePreset = DocumentStylePreset.OFFICIAL
    ): File

    /**
     * صدور به فرمت Markdown تمیز
     */
    fun generateMarkdown(session: ChatSession, distillation: DistillationResult? = null): String

    /**
     * صدور به فرمت HTML تک‌فایلی با استایل تعبیه‌شده
     */
    fun generateHtml(session: ChatSession, distillation: DistillationResult? = null): String
}
```

---

### ۳. مشخصات کامپوننت‌های رابط کاربری (UI Component Specs)

#### ۳.۱. نوار ناوبری کپسولی شناور (`AppBottomNavBar`)
* **ابعاد و موقعیت:**
  * ارتفاع ظرف: `66.dp`
  * گوشه‌ها: `RoundedCornerShape(33.dp)` (کاملاً گرد)
  * مارجین از لبه‌ها: `horizontal = 14.dp`, `vertical = 6.dp`
  * سایه محفظه: `elevation = 16.dp` با رنگ نوری نیلی شفاف (`#6366F1` با شفافیت ۳۵٪)
* **حباب فعال (Active Sliding Bubble):**
  * ارتفاع حباب: `52.dp`
  * گوشه‌ها: `RoundedCornerShape(26.dp)`
  * گرادیان پس‌زمینه: نیلی الکتریکی (`#6366F1`) ➔ ایندیگو (`#4F46E5`) ➔ فیروزه‌ای زنده (`#06B6D4`)
  * انیمیشن لغزش افقی: `graphicsLayer { translationX = ... }` با فیزیک `Spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)`
* **حالت‌های آیکون فعال:**
  * مقیاس: `1.15x`
  * جابه‌جایی عمودی: `translationY = -2.dp`
  * رنگ: سفید خالص (`#FFFFFF`) با فونت ضخیم (Bold)

#### ۳.۲. ترک پیشرفت بالای نوار (Workflow Breadcrumb Tracker)
* **ابعاد:** ارتفاع `26.dp`، گوشه‌ها `RoundedCornerShape(12.dp)`
* **شاخص گام‌ها:** دایره‌های `14.dp` حاوی شماره مرحله (`۱`، `۲`، `۳`، `۴`) یا تیک تکمیل `✓`
* **رنگ وضعیت:**
  * گام فعال: نیلی (`#6366F1`)
  * گام تکمیل‌شده: سبز زمردی (`#10B981`)
  * گام آینده: خاکستری نیمه‌شفاف با اوپاسیتی ۳۰٪

#### ۳.۳. مشخصات سیستم تم و سفارشی‌سازی ظاهر (Themes & Appearance Engine)
* **انواع پوسته‌ها (`AppThemePreset`):**
  1. `OBSIDIAN`: تم اصلی نئونی تیره با رنگ‌های `#6366F1` و `#06B6D4` و پس‌زمینه `#090D16`.
  2. `MIDNIGHT_NAVY`: سرمه‌ای اقیانوسی با رنگ‌های `#3B82F6` و `#F59E0B` و پس‌زمینه `#0A1128`.
  3. `CYBERPUNK_EMERALD`: ماتریکس سبز زمردی با رنگ‌های `#10B981` و `#14B8A6` و پس‌زمینه `#08140E`.
  4. `CRIMSON_ROSE`: یاقوتی و صورتی با رنگ‌های `#E11D48` و `#A855F7` و پس‌زمینه `#170A12`.
  5. `PEARL_LIGHT`: تم سفید روز با رنگ‌های `#4338CA` و `#0D9488` و پس‌زمینه `#F8FAFC`.
* **موتور تایپوگرافی پویا (`createScaledTypography`):**
  * محاسبه نسبت مقیاس قلم: `val scale = fontScale.coerceIn(0.75f, 1.4f)`
  * اعمال ضریب در مقادیر `fontSize` و `lineHeight` کلیه استایل‌های Material 3.
  * خانواده‌های قلم پشتیبانی‌شده: `Vazirmatn (SansSerif)`, `Serif`, `Monospace`.

#### ۳.۴. مشخصات پروتکل خروج امن (Sign Out Specification)
* دکمه خروج در بخش حساب کاربری با برچسب آزمایشی `testTag("sign_out_btn")`.
* نمایش دیالوگ تاییدیه `AlertDialog` با دو دکمه «تایید خروج» و «انصراف».
* در صورت تایید: فراخوانی `viewModel.signOut()`، تخلیه نشست فعال و انتقال فوری به `AuthScreen`.

#### ۳.۵. مشخصات کنترل دکمه بازگشت فیزیکی (System BackHandler Spec)
* پیاده‌سازی با کامپوز بومی `BackHandler(enabled = true)` در لایه `MainActivity`.
* منطق شرطی:
  * اگر صفحه فعلی غیر از `IMPORT` باشد: هدایت به صفحه قبلی (پاپ استک داخلی).
  * اگر صفحه فعلی `IMPORT` باشد:
    * متغیر ثبت زمان آخرین ضربه: `var lastBackPressTime: Long`.
    * بررسی شرط: `System.currentTimeMillis() - lastBackPressTime < 2000L`.
    * در صورت برقراری شرط: اتمام اکتیویتی `(context as? Activity)?.finish()`.
    * در صورت عدم برقراری: به‌روزرسانی `lastBackPressTime` و نمایش پیام توست: `"برای خروج از برنامه، دوباره دکمه بازگشت را بزنید"`.
