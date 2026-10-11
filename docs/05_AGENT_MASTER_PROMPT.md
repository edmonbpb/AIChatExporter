# 🤖 پرامپت جامع بازسازی کامل اپلیکیشن توسط ایجنت هوش مصنوعی (Master Agent Build Prompt)
## پروژه: AIChatExporter — کامل از صفر تا صد

---

> **دستورالعمل ویژه برای ایجنت پیاده‌ساز:**
> شما به عنوان مهندس ارشد نرم‌افزار اندروید، وظیفه دارید اپلیکیشن جامع **AIChatExporter** را با زبان Kotlin، فریم‌ورک Jetpack Compose و معماری Clean Architecture به طور ۱۰۰٪ کامل از صفر تا صد پیاده‌سازی و اجرا کنید. هیچ بخش یا کلاسی نباید به صورت فرضی (Stub) یا نیمه‌کاره رها شود. تمامی لایه‌های پایگاه داده، رندرینگ، انیمیشن موشن و تولید اسناد باید به طور اجرایی و کامل نوشته شوند.

---

### ۱. ساختار دقیق درخت فایل‌های پروژه (Project File Tree)

**درخت واقعیِ همین نسخه** (بررسی‌شده با `find app/src/main -type f` در تاریخ ۲۰۲۶-۱۰-۰۶):

```
app/src/main/
├── AndroidManifest.xml                 // اینترنت + اینتنت SEND + FileProvider
├── java/com/example/
│   ├── MainActivity.kt                 // Edge-to-Edge، NoOp TextToolbar، خروج دو ضربه
│   ├── data/
│   │   ├── local/
│   │   │   ├── AppDatabase.kt          // ۴ جدول، نسخه ۱، fallbackToDestructiveMigration
│   │   │   ├── Daos.kt                 // ChatDao + DistillationDao + ExportDao (یک فایل)
│   │   │   ├── Entities.kt             // هر چهار انتیتی (یک فایل)
│   │   │   └── Converters.kt           // دستی مصرف می‌شود، نه به‌عنوان TypeConverter_room
│   │   ├── model/
│   │   │   ├── ChatMessage.kt          // ChatMessage + MessageRole + TextDirection + CodeSnippet
│   │   │   ├── ChatSession.kt
│   │   │   ├── AIDistillation.kt       // AIDistillation + DistillationType (۷ قالب)
│   │   │   ├── ExportRecord.kt         // ExportRecord + ExportFormat (۷ فرمت)
│   │   │   ├── PlatformPreset.kt
│   │   │   └── UserProfile.kt
│   │   └── remote/
│   │       ├── auth/GoogleAuthManager.kt   // ورود واقعی + GoogleSignInOutcome + validateIdTokenClaims
│   │       └── gemini/{GeminiClient.kt, DistillationPrompts.kt}
│   ├── domain/
│   │   ├── bidi/BidiEngine.kt
│   │   ├── parser/{ChatGptStreamDecoder.kt, SharedLinkExtractor.kt, TranscriptIngestionEngine.kt}
│   │   └── exporter/{DocumentExporter.kt, OpenXmlGenerator.kt, FileShareHelper.kt}
│   └── ui/
│       ├── components/{AppNavigation.kt, AppTopBar.kt, BidiText.kt, CodeBlockView.kt, StatBadge.kt}
│       ├── screens/{Auth, Import, Workspace, Distillation, ExportStudio, Library, Settings}Screen.kt
│       ├── theme/{Color.kt, Theme.kt}
│       └── viewmodel/MainViewModel.kt
└── res/font/{vazirmatn_regular.ttf, vazirmatn_medium.ttf, vazirmatn_bold.ttf}
```

سه تفاوت اصلی با طرح زیر: فایل‌های DAO/Entity به‌جای یک فایل برای هر کلاس در `Daos.kt` و `Entities.kt` جمع شده‌اند؛ `domain/distillation/DistillationEngine.kt` و `domain/export/DocumentGenerator.kt` هرگز ساخته نشدند (کارشان به `GeminiClient.kt` و `exporter/DocumentExporter.kt` + `OpenXmlGenerator.kt` رفته)؛ و `ui/theme/Type.kt` به‌عنوان کد مرده حذف شد.

**طرح اولیهٔ AI Studio** (بایگانی — در چند نقطه با کدِ فعلی نمی‌خواند):

```
app/src/main/
├── AndroidManifest.xml
└── java/com/example/
    ├── MainActivity.kt                      // اکتیویتی اصلی، مدیریت Edge-to-Edge، هندلینگ امن اکشن‌مودها
    ├── data/
    │   ├── local/
    │   │   ├── AppDatabase.kt               // پایگاه داده Room با کانورتورها و نسخه ۱
    │   │   ├── ChatDao.kt                   // کوئری‌های ذخیره، بازخوانی، حذف و بوکمارک جلسات
    │   │   ├── Converters.kt                // تبدیل لیست‌ها و کدهای JSON به ساختار دیتابیس
    │   │   ├── DistillationDao.kt           // ذخیره تاریخچه پالایش‌های متنی
    │   │   ├── ExportDao.kt                 // ذخیره سوابق اسناد صادر شده
    │   │   └── entity/
    │   │       ├── ChatSessionEntity.kt     // انتیتی جلسه چت
    │   │       ├── ChatMessageEntity.kt     // انتیتی پیام‌ها با کلید خارجی CASCADE
    │   │       ├── DistillationEntity.kt    // انتیتی سابقه پالایش
    │   │       └── ExportRecordEntity.kt    // انتیتی سابقه سند تولید شده
    │   ├── model/
    │   │   ├── ChatMessage.kt               // مدل دامین پیام، جهت متن و اسنیپت‌های کد
    │   │   ├── ChatSession.kt               // مدل دامین جلسه با آمار کلمات و پیام‌ها
    │   │   ├── MessageRole.kt               // اینام نقش‌ها: USER, ASSISTANT, SYSTEM
    │   │   ├── PlatformPreset.kt            // مشخصات پلتفرم‌ها: ChatGPT, Claude, Gemini, DeepSeek, Perplexity
    │   │   ├── TextDirection.kt             // اینام جهت متن: RTL, LTR, MIXED
    │   │   └── UserProfile.kt               // مدل کاربر و اتصال گوگل درایو
    │   └── remote/gemini/
    │       └── GeminiClient.kt              // کلاینت RESTful مدل Gemini 3.5 Flash با OkHttp
    ├── domain/
    │   ├── bidi/
    │   │   └── BidiEngine.kt                // تشخیص جهت، ارقام فارسی، نیم‌فاصله و رفعِ حروفِ ناخوانا (درج RLM/LRM فقط در repairDefectsOnly)
    │   ├── distillation/
    │   │   └── DistillationEngine.kt        // ❌ هرگز ساخته نشد (بندِ «سه تفاوت» در همین سند، خط ۴۸); موتور آفلاین در data/remote/gemini/GeminiClient.kt است
    │   ├── export/
    │   │   └── DocumentGenerator.kt         // موتور تولید اسناد Word .docx، PDF استاندارد، MD و HTML
    │   └── parser/
    │       ├── ChatGptStreamDecoder.kt       // رمزگشای قالب تک‌جریانی (turbo-stream) صفحه‌های share چت‌جی‌پی‌تی
    │       ├── SharedLinkExtractor.kt       // موتور استخراج ۵ لایه لینک‌های اشتراک با دور زدن کلودفلر
    │       └── TranscriptIngestionEngine.kt // موتور پارس نوبت‌های گفتگو، کدهای برنامه و تگ‌های <think>
    └── ui/
        ├── components/
        │   ├── AppNavigation.kt             // نوار ناوبری کپسولی شناور با انیمیشن حباب لغزان و فیزیک فنری
        │   ├── AppTopBar.kt                 // نوار بالایی با نشانگر مدل، ذخیره، بوکمارک و جلسه جدید
        │   ├── BidiText.kt                  // کامپوننت متنی دوزبانه با اعمال خودکار نیم‌فاصله و ارقام فارسی
        │   ├── CodeBlockView.kt             // نمایش کادر کدهای برنامه‌نویسی با دکمه کپی اختصاصی
        │   └── StatBadge.kt                 // بج‌های آماری کلمات، توکن‌ها و تعداد پیام‌ها
        ├── screens/
        │   ├── AuthScreen.kt                // صفحه لاگین با گوگل و ورود سریع مهمان (Guest Mode)
        │   ├── ImportScreen.kt              // گام ۱: تب ورود لینک اشتراک‌گذاری، ورود متن یا انتخاب فایل
        │   ├── WorkspaceScreen.kt           // گام ۲: کارگاه نمایش، جستجو، فیلتر کدها و ویرایش دستی
        │   ├── DistillationScreen.kt        // گام ۳: استودیو پالایش هوشمند و پیش‌نمایش گزارش
        │   ├── ExportStudioScreen.kt        // گام ۴: استودیو انتخاب فرمت (Word/PDF)، استایل و صدور سند
        │   ├── LibraryScreen.kt             // بخش سوابق مکالمات ذخیره‌شده محلی و بوکمارک‌ها
        │   └── SettingsScreen.kt            // تنظیمات ناوبری پویا، تایپوگرافی و کلید API
        ├── theme/
        │   ├── Color.kt                     // پالت رنگ‌های آبسیدین، نیلی، فیروزه‌ای و زمردی
        │   ├── Theme.kt                     // پیکربندی تم Material 3 دوزبانه با حالت تاریک/روشن
        │   └── Type.kt                      // استایل‌های تایپوگرافی Vazirmatn
        └── viewmodel/
            └── MainViewModel.kt             // ویومدل اصلی با مدیریت StateFlow، ناوبری مرحله‌ای و اسناد
```

---

### ۲. پیکربندی وابستگی‌های پروژه (`app/build.gradle.kts`)

**پیکربندی واقعیِ همین نسخه** (از `app/build.gradle.kts` و `gradle/libs.versions.toml`):

| مورد | مقدار واقعی | آنچه طرح زیر می‌گفت |
|---|---|---|
| namespace / applicationId | `com.example` / `com.aistudio.aichatexporter.kxrtva` | `com.aistudio.aichatexporter.prod` |
| compileSdk / minSdk / targetSdk | `36.1` / `29` / `36` | `35` / `26` / `35` |
| AGP / Kotlin / KSP | `9.1.1` / `2.2.10` / `2.3.5` | — |
| Compose BOM / Room | `2024.09.00` / `2.7.0` | — |
| OkHttp / Coroutines | `4.10.0` / `1.10.2` | `4.12.0` / `1.8.0` |
| پلاگین‌ها | android-application، kotlin-compose، ksp، secrets، google-services | `org.jetbrains.kotlin.android` (وجود ندارد) |
| احراز هویت | `androidx.credentials:1.5.0` + `credentials-play-services-auth` + `googleid:1.1.1` | اعلام نشده بود |
| کلید API | از `.env` به `BuildConfig` (پلاگین secrets)؛ `GEMINI_API_KEY` و `GOOGLE_CLIENT_ID` | اشاره به `.env.example` بدون مکانیزم |

متن زیر طرح اولیه است و فقط برای سابقه مانده:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistudio.aichatexporter.prod"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Room Database & KSP
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
```

---

### ۳. چک‌لیست و قوانین الزامی پیاده‌سازی (Mandatory Agent Invariants)

1. **انیمیشن حباب لغزنده (Sliding Bubble Animation):**
   * نوار پایین صفحه باید یک کپسول شناور باشد (`RoundedCornerShape(33.dp)`).
   * جابه‌جایی حباب فعال با `graphicsLayer { translationX = ... }` با پارامترهای فیزیکی `dampingRatio = 0.72f` و `stiffness = Spring.StiffnessMediumLow` انجام شود تا مانند یک قطره منعطف با اثر پرتاب و بازگشت نرم روی نوار سر بخورد.
   * هرگز از نشانگرهای ثابت پیش‌فرض Material3 که درجا فید می‌شوند استفاده نکنید.
2. **ناوبری مرحله‌ای و پویا (Progressive Workflow):**
   * هنگام ورود اولیه، فقط ۳ تب پایه (ورود، کتابخانه، تنظیمات) در نوار نمایش یابند.
   * با ورود متن/لینک، تب «محیط کار» آزاد شده و حباب به سمت آن می‌لغزد.
   * دکمه‌های گام ۳ و ۴ در پایین محیط کار به ترتیب تب‌های «پالایش هوشمند» و «صدور اسناد» را آزاد کرده و حباب را به روی آنها هدایت می‌کنند.
   * مراحل قبلی تیک سبز رنگ `✓` می‌گیرند.
   * با زدن «مکالمه جدید»، نوار به حالت ۳ تایی اولیه بازمی‌گردد.
3. **پایداری اکشن‌مود اندروید (Android DecorView Bug Prevention):**
   * در ریشه `MainActivity`، پرووایدر `LocalTextToolbar provides NoOpTextToolbar` اعمال شود تا از تولید هشدارهای خطای سیستمی `Destroying unexpected ActionMode` در شبیه‌ساز کامپوز جلوگیری شود.
4. **استخراج چندلایه‌ای لینک‌ها (Link Ingestion):**
   * نرمال‌سازی خودکار پروتکل `https://` برای تمام آدرس‌های ورودی.
   * اجرای روش آبشاری: اول هدرهای کامل مرورگر Chrome با پارس قطعات RSC چت‌جی‌پی‌تی ➔ دوم موتور رندر Jina Reader (`r.jina.ai`) برای عبور از سد کلودفلر ➔ سوم پراکسی کمکی ➔ چهارم ساختاربندی هوش مصنوعی.
5. **موتور تولید اسناد بومی (Zero Dependency DocGen):**
   * تولید فایل `.docx` با استفاده از فشرده‌سازی `ZipOutputStream` و اسکلت OpenXML استاندارد همراه با استایل راست‌به‌چپ (`<w:bidi/>`).
   * تولید فایل `.pdf` با استفاده از `android.graphics.pdf.PdfDocument` سیستمی با محاسبه دقیق شکست خطوط و سربرگ رسمی.
6. **احراز هویت واقعی، بدون حساب ساختگی:** تنها حالت `SignedIn` نشست می‌سازد. اگر پنجرهٔ حساب بسته شود پیام «لغو شد» و اگر شناسهٔ OAuth تنظیم نشده باشد پیام «پیکربندی نشده» نمایش داده می‌شود؛ هیچ‌گاه نشانی ایمیل از خود برنامه ساخته نمی‌شود و ایمیل/نام از claims تأییدشدهٔ توکن (`email`، `name`، `sub`) خوانده می‌شود. شناسهٔ کلاینت در `BuildConfig.GOOGLE_CLIENT_ID` (از `.env`) است، نه در کد.
7. **نوار پایین با `WindowInsets.navigationBars`:** فاصلهٔ زیر کپسول باید به‌اندازهٔ نوار ناوبری سیستم بعلاوهٔ ۱۰.dp باشد تا با دکمه‌های گوشی تداخل نکند.
8. **کارهای سنگین بیرون از نخ اصلی:** پارس متن (`Dispatchers.Default`)، تولید سند و خواندن همگام دیتابیس (`Dispatchers.IO`). صدور سند روی نخ اصلی باعث ANR می‌شود.
9. **تاریخچه پالایش فقط برای مکالمهٔ ذخیره‌شده:** جدول `ai_distillations` کلید خارجی به `chat_sessions` دارد؛ پیش از درج، وجود جلسه بررسی می‌شود و اگر نبود، نتیجه فقط در حافظه می‌ماند (وگرنه درج با `SQLiteConstraintException` برنامه را می‌اندازد).
