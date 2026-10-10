# 🏗️ معماری فنی و مهندسی سیستم (Technical Architecture & Design)
## پروژه: AIChatExporter

---

### ۱. معماری کلی سیستم (Clean Architecture & MVVM)

اپلیکیشن بر پایه اصول **Clean Architecture** و الگوی **MVVM (Model-View-ViewModel)** پیاده‌سازی شده و از سه لایه اصلی تشکیل شده است:

```
┌────────────────────────────────────────────────────────┐
│                   Presentation Layer                   │
│   (Jetpack Compose UI Screens, Components, Theme)      │
└───────────────────────────▲────────────────────────────┘
                            │ StateFlow / Events
┌───────────────────────────┴────────────────────────────┐
│                    ViewModel Layer                     │
│    (MainViewModel, State Management, Coroutines)       │
└───────────────────────────▲────────────────────────────┘
                            │ Domain Calls / Flow
┌───────────────────────────┴────────────────────────────┐
│                      Domain Layer                      │
│   (BidiEngine, Parsers, DistillationEngine, DocGen)   │
└───────────────────────────▲────────────────────────────┘
                            │ Repository / DAO / Network
┌───────────────────────────┴────────────────────────────┐
│                       Data Layer                       │
│  (Room DB, SQLite Entities, Converters, OkHttp Client) │
└────────────────────────────────────────────────────────┘
```

---

### ۲. مدل‌های داده پایه (Core Domain Models)

#### ۲.۱. مدل جلسه گفتگو (`ChatSession`)
```kotlin
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val platform: PlatformPreset,
    val modelName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val tags: List<String> = emptyList(),
    val isBookmarked: Boolean = false,
    val customNotes: String = ""
) {
    val totalWords: Int get() = messages.sumOf { it.content.split(Regex("\\s+")).size }
    val userMessageCount: Int get() = messages.count { it.role == MessageRole.USER }
    val assistantMessageCount: Int get() = messages.count { it.role == MessageRole.ASSISTANT }
    val codeBlockCount: Int get() = messages.sumOf { it.codeSnippets.size }
    val tokenEstimate: Int get() = (totalWords * 1.35).toInt()
}
```

#### ۲.۲. مدل نوبت پیام (`ChatMessage`)
```kotlin
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole, // USER, ASSISTANT, SYSTEM
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val modelTag: String? = null,
    val direction: TextDirection = TextDirection.MIXED,
    val reasoning: String? = null, // محتوای استخراج شده از تگ‌های <think>
    val codeSnippets: List<CodeSnippet> = emptyList()
)

data class CodeSnippet(
    val language: String,
    val code: String
)
```

#### ۲.۳. مدل پلتفرم‌های هوش مصنوعی (`PlatformPreset`)
```kotlin
enum class PlatformPreset(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val iconEmoji: String,
    val brandColorHex: String
) {
    CHATGPT("chatgpt", "ChatGPT", "GPT-4o", "🤖", "#10A37F"),
    CLAUDE("claude", "Claude", "Claude 3.5 Sonnet", "🎭", "#D97706"),
    DEEPSEEK("deepseek", "DeepSeek", "DeepSeek-R1", "🐳", "#0284C7"),
    GEMINI("gemini", "Google Gemini", "Gemini 3.5 Flash", "✨", "#4F46E5"),
    PERPLEXITY("perplexity", "Perplexity", "Sonar Pro", "🔍", "#059669"),
    OLLAMA("ollama", "Ollama / Local", "Llama 3.1", "🦙", "#475569"),
    CUSTOM("custom", "متفرقه / دستی", "LLM", "💬", "#6366F1");
}
```

---

### ۳. لایه پایگاه داده محلی (Room Persistence Architecture)

پایگاه داده برنامه (`aichat_exporter.db`، نسخه ۱) شامل ۴ جدول ارتباطی است. نام ستون‌ها همان نام فیلدهای کاتلین است (Room ستونِ snake_case نمی‌سازد مگر با `@ColumnInfo`، که اینجا استفاده نشده):

1. **`chat_sessions` (جدول جلسات گفتگو):**
   * کلید اصلی: `id` (String UUID)
   * فیلدها: `title`, `platformId`, `modelName`, `createdAt`, `updatedAt`, `tagsJson`, `isBookmarked`, `customNotes`
2. **`chat_messages` (جدول پیام‌ها):**
   * کلید اصلی: `id` (String UUID)
   * کلید خارجی: `sessionId` با `onDelete = CASCADE` و ایندکس روی همان ستون
   * فیلدها: `role`, `content`, `timestamp`, `modelTag`, `direction`, `reasoning`, `codeSnippetsJson`, `orderIndex`
3. **`ai_distillations` (جدول تاریخچه پالایش):**
   * کلید اصلی: `id` (String UUID)، کلید خارجی `sessionId` با CASCADE
   * فیلدها: `type`, `title`, `content`, `modelUsed`, `createdAt`
   * `modelUsed` یا نام مدلی است که پاسخ داده، یا `offline-rule-based` وقتی موتور محلی کار را تمام کرده است
4. **`export_records` (جدول سوابق صدور اسناد):**
   * کلید اصلی: `id` (String UUID)
   * فیلدها: `sessionId`, `sessionTitle`, `fileName`, `format`, `filePath`, `fileSizeFormatted`, `exportedAt`
   * این جدول کلید خارجی ندارد؛ به همین دلیل صدور سندِ مکالمه‌ای که هرگز ذخیره نشده هم بی‌خطر ثبت می‌شود

---

### ۴. ماشین حالت ناوبری مرحله‌ای (Progressive Navigation State Machine)

سیستم ناوبری وضعیت‌های باز شدن تب‌ها را با ساختار State Machine زیر مدیریت می‌کند:

```
[Start App]
   │
   ▼
State 0: Initial Screen
(Unlocked: IMPORT, LIBRARY, SETTINGS)
   │
   │  Event: Ingestion Success (Link or Text parsed)
   ▼
State 1: Workspace Active
(Unlocked: IMPORT, WORKSPACE, LIBRARY, SETTINGS)
   │
   ├──────────────────────────────┐
   │ Event: Click "گام ۳: پالایش"   │ Event: Click "گام ۴: صدور"
   ▼                              ▼
State 2: Distill Active        State 3: Export Active
(Unlocked: IMPORT, WORKSPACE,  (Unlocked: IMPORT, WORKSPACE,
 DISTILL, LIBRARY, SETTINGS)    EXPORT, LIBRARY, SETTINGS)
   │                              ▲
   │ Event: Send to Export        │
   └──────────────────────────────┘
```

* **رویداد بازنشانی (Reset Event):** با فراخوانی متد `resetSession()` در منو یا نوار بالا، ماشین حالت بلافاصله به `State 0` بازگشته و نوار پایین به ۳ تب خلوت اولیه برمی‌گردد.
* **نکتهٔ پیاده‌سازی (تطبیق با کد):** در `MainViewModel` تنها تب `WORKSPACE` با `unlockScreen()` صریح آزاد می‌شود؛ تب‌های `DISTILL` و `EXPORT` از راه `navigateTo()` باز می‌شوند (که خودش پیش از هر ناوبری، مقصد را آزاد می‌کند). نشانهٔ `✓` روی تب‌ها از `stepIndex`ِ صفحهٔ جاری گرفته می‌شود، نه از اتمام واقعی کار.
