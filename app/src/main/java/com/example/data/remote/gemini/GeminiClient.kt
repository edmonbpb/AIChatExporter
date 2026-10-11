package com.example.data.remote.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ChatSession
import com.example.data.model.DistillationType
import com.example.domain.bidi.BidiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiClient {

    private const val TAG = "GeminiClient"
    private const val API_ENDPOINT_PREFIX = "https://generativelanguage.googleapis.com/v1beta/models/"

    // Modern candidate models in order of priority for high resilience
    private val CANDIDATE_MODELS = listOf(
        "gemini-3.5-flash",
        "gemini-3.1-flash-lite-preview",
        "gemini-2.5-flash",
        "gemini-flash-latest"
    )

    /** Which engine answered the last request, so a saved record can say where its text came from.
     *  The setter is `internal` only so a JVM test can plant a stale cloud name and prove that every
     *  offline branch overwrites it; production code still writes it from inside this object alone. */
    @Volatile
    var lastUsedModel: String? = null
        internal set

    const val OFFLINE_MODEL_LABEL = "offline-rule-based"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    var customApiKey: String? = null

    /**
     * Test seam. A JVM test has to reach the offline branches with no key and no network, and the key
     * of the machine it runs on is not something a test may depend on. Nothing in the app ever sets
     * this, so while it is `null` the behaviour is exactly the one before.
     */
    internal var apiKeyOverrideForTests: String? = null

    fun getEffectiveApiKey(): String {
        apiKeyOverrideForTests?.let { return it }
        customApiKey?.takeIf { it.isNotBlank() }?.let { return it }
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (_: Exception) {
            ""
        }
        return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
    }

    fun hasValidApiKey(): Boolean {
        return getEffectiveApiKey().isNotBlank()
    }

    suspend fun distillSession(
        session: ChatSession,
        type: DistillationType,
        customPrompt: String? = null,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        val prompt = DistillationPrompts.buildPrompt(session, type, customPrompt)

        if (apiKey.isBlank()) {
            // High quality local fallback generation so the user is never blocked
            val localResult = generateLocalSmartDistillation(session, type, customPrompt)
            lastUsedModel = OFFLINE_MODEL_LABEL
            return@withContext Result.success(localResult)
        }

        var lastErrorMsg = "خطای ناشناخته در ارتباط با هوش مصنوعی"

        for (attemptIndex in CANDIDATE_MODELS.indices) {
            val modelName = CANDIDATE_MODELS[attemptIndex]
            val attemptNumber = attemptIndex + 1
            val maxAttempts = CANDIDATE_MODELS.size

            try {
                val jsonBody = JSONObject().apply {
                    val contentsArray = JSONArray()
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray()
                        partsArray.put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put("parts", partsArray)
                    }
                    contentsArray.put(contentObj)
                    put("contents", contentsArray)

                    val genConfig = JSONObject().apply {
                        put("temperature", 0.7)
                        put("topP", 0.95)
                        put("topK", 40)
                    }
                    put("generationConfig", genConfig)
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val url = "$API_ENDPOINT_PREFIX$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val respJson = JSONObject(responseString)
                    val candidates = respJson.optJSONArray("candidates")
                    val firstCandidate = candidates?.optJSONObject(0)
                    val content = firstCandidate?.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    val text = parts?.optJSONObject(0)?.optString("text")

                    if (!text.isNullOrBlank()) {
                        // The id the API says it served, not the alias that was asked for.
                        lastUsedModel = servedModelOf(respJson, modelName)
                        return@withContext Result.success(BidiEngine.normalizePersianTypography(text))
                    }
                }

                // If not successful, analyze error
                val extractedMessage = try {
                    JSONObject(responseString).getJSONObject("error").optString("message", "")
                } catch (_: Exception) {
                    ""
                }
                lastErrorMsg = if (extractedMessage.isNotBlank()) extractedMessage else "کد پاسخ ${response.code}"
                Log.w(TAG, "Attempt $attemptNumber with model $modelName failed: ${response.code} $lastErrorMsg")

                val isOverloaded = response.code in listOf(429, 503, 500, 504) ||
                        lastErrorMsg.contains("overload", ignoreCase = true) ||
                        lastErrorMsg.contains("busy", ignoreCase = true) ||
                        lastErrorMsg.contains("quota", ignoreCase = true) ||
                        lastErrorMsg.contains("resource", ignoreCase = true) ||
                        lastErrorMsg.contains("demand", ignoreCase = true) ||
                        lastErrorMsg.contains("rate", ignoreCase = true)

                if (attemptNumber < maxAttempts) {
                    val nextAttempt = attemptNumber + 1
                    val retryMsg = if (isOverloaded) {
                        "سرورهای گوگل شلوغ است، در حال تلاش مجدد هوشمند هستیم... (تلاش $nextAttempt از $maxAttempts)"
                    } else {
                        "در حال تلاش مجدد با مدل کمکی هوش مصنوعی... (تلاش $nextAttempt از $maxAttempts)"
                    }
                    onStatusUpdate?.invoke(retryMsg)
                    kotlinx.coroutines.delay(attemptNumber * 1200L)
                }

            } catch (e: Exception) {
                Log.w(TAG, "Network or timeout exception on attempt $attemptNumber", e)
                lastErrorMsg = e.localizedMessage ?: "خطای زمان اتصال یا شبکه"
                if (attemptNumber < maxAttempts) {
                    val nextAttempt = attemptNumber + 1
                    onStatusUpdate?.invoke("سرورهای گوگل شلوغ است، در حال تلاش مجدد هوشمند هستیم... (تلاش $nextAttempt از $maxAttempts)")
                    kotlinx.coroutines.delay(attemptNumber * 1200L)
                }
            }
        }

        // All cloud models were busy/overloaded: smoothly fall back to local smart distillation
        Log.i(TAG, "All cloud models busy. Falling back smoothly to local smart distillation engine.")
        onStatusUpdate?.invoke("ترافیک سرورهای گوگل بالاست؛ تکمیل با موتور پردازش محلی AIChatExporter...")
        val fallbackResult = generateLocalSmartDistillation(session, type, customPrompt)
        lastUsedModel = OFFLINE_MODEL_LABEL
        Result.success(fallbackResult)
    }

    suspend fun chatRefine(
        currentContent: String,
        userInstruction: String,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        if (apiKey.isBlank()) {
            // This text came out of this object, not out of the cloud. Without the label the screen
            // keeps showing whichever model answered the previous, different request.
            lastUsedModel = OFFLINE_MODEL_LABEL
            return@withContext Result.success(buildLocalRefinePreview(userInstruction, currentContent))
        }

        val prompt = """
شما دستیار ویراستاری هوشمند در AIChatExporter هستید.
کاربر درخواست اصلاح و بازنویسی زیر را برای متن ارائه داده است:

[درخواست کاربر]:
$userInstruction

[متن اصلی]:
$currentContent

لطفاً تغییرات خواسته‌شده را با بالاترین کیفیت نگارشی، حفظ فرمت‌های کد و ساختار Markdown و تایپوگرافی روان فارسی اعمال و نسخه نهایی را برگردانید.
""".trimIndent()

        for (attemptIndex in CANDIDATE_MODELS.indices) {
            val modelName = CANDIDATE_MODELS[attemptIndex]
            val attemptNumber = attemptIndex + 1
            val maxAttempts = CANDIDATE_MODELS.size

            try {
                val jsonBody = JSONObject().apply {
                    val contentsArray = JSONArray()
                    val contentObj = JSONObject().apply {
                        val partsArray = JSONArray()
                        partsArray.put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put("parts", partsArray)
                    }
                    contentsArray.put(contentObj)
                    put("contents", contentsArray)
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val url = "$API_ENDPOINT_PREFIX$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val respJson = JSONObject(responseString)
                    val text = respJson.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")

                    if (!text.isNullOrBlank()) {
                        // Same rule for the refiner: what was served, not what was asked.
                        lastUsedModel = servedModelOf(respJson, modelName)
                        return@withContext Result.success(BidiEngine.normalizePersianTypography(text))
                    }
                }

                if (attemptNumber < maxAttempts) {
                    val nextAttempt = attemptNumber + 1
                    onStatusUpdate?.invoke("سرورهای گوگل شلوغ است، در حال تلاش مجدد هوشمند هستیم... (تلاش $nextAttempt از $maxAttempts)")
                    kotlinx.coroutines.delay(attemptNumber * 1000L)
                }
            } catch (e: Exception) {
                if (attemptNumber < maxAttempts) {
                    val nextAttempt = attemptNumber + 1
                    onStatusUpdate?.invoke("سرورهای گوگل شلوغ است، در حال تلاش مجدد هوشمند هستیم... (تلاش $nextAttempt از $maxAttempts)")
                    kotlinx.coroutines.delay(attemptNumber * 1000L)
                }
            }
        }

        // Local refinement fallback
        val localRefined = if (userInstruction.contains("ایموجی") || userInstruction.contains("حروف") || userInstruction.contains("ناخوانا")) {
            BidiEngine.repairDefectsOnly(currentContent)
        } else {
            BidiEngine.normalizePersianTypography(currentContent)
        }
        lastUsedModel = OFFLINE_MODEL_LABEL
        Result.success(localRefined)
    }

    suspend fun generateRawContent(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey()
        if (apiKey.isBlank()) {
            // Nothing was produced here, so the label of the last answer is left as it was: a failed
            // call has no engine of its own to report.
            return@withContext Result.failure(Exception("کلید API تعریف نشده است."))
        }

        val requestModel = "gemini-3.5-flash"

        try {
            val jsonBody = JSONObject().apply {
                val contentsArray = JSONArray()
                val contentObj = JSONObject().apply {
                    val partsArray = JSONArray()
                    partsArray.put(JSONObject().apply {
                        put("text", prompt)
                    })
                    put("parts", partsArray)
                }
                contentsArray.put(contentObj)
                put("contents", contentsArray)
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$API_ENDPOINT_PREFIX$requestModel:generateContent?key=$apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseString = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("خطا از مدل هوش مصنوعی: کد ${response.code}"))
            }

            val respJson = JSONObject(responseString)
            val text = respJson.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")

            if (!text.isNullOrBlank()) {
                // Same rule as the other two entry points: the id that answered, never the alias that
                // was asked for and never the name an older request left behind.
                lastUsedModel = servedModelOf(respJson, requestModel)
                Result.success(text)
            } else {
                Result.failure(Exception("پاسخ دریافت نشد."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * High quality heuristic local distillation engine for offline mode.
     */
    private fun generateLocalSmartDistillation(
        session: ChatSession,
        type: DistillationType,
        customPrompt: String?
    ): String {
        // The date is a number the reader of the exported document sees, so it goes through the one
        // helper every other number in these documents already uses.
        val dateStr = BidiEngine.toPersianDigits(
            java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(session.createdAt))
        )

        return when (type) {
            DistillationType.REPAIR_DEFECTS_ONLY -> buildLocalRepairDoc(session, dateStr)

            DistillationType.FULL_EDITED -> buildString {
                appendLine("# 📄 مستند رسمی ویراستاری‌شده: ${session.title}")
                appendLine("**پلتفرم مبدأ:** ${session.platform.displayName} | **مدل:** ${session.modelName} | **تاریخ:** $dateStr")
                appendLine("**تعداد پیام‌ها:** ${localDigits(session.messages.size)} | **کلمات کلیدی:** ${session.tags.joinToString(", ")}")
                appendLine("\n---\n")

                session.messages.forEachIndexed { idx, msg ->
                    val senderName = if (msg.role.name == "USER") "👤 پرسش کاربر" else "🤖 پاسخ هوش مصنوعی (${msg.modelTag ?: session.modelName})"
                    appendLine("### $senderName:")
                    appendLine(BidiEngine.normalizePersianTypography(msg.content))
                    appendLine()
                }
            }

            DistillationType.EXECUTIVE_SUMMARY -> buildLocalExecutiveSummary(session, dateStr)

            DistillationType.TECHNICAL_DOC -> buildLocalTechnicalDoc(session, dateStr)

            DistillationType.QA_FLASHCARDS -> buildString {
                appendLine("# 💡 بانک پرسش و پاسخ و فلش‌کارت‌های کلیدی")
                appendLine("## گفتگوی مرجع: ${session.title}\n")

                var qaCount = 1
                var lastUser: String? = null
                session.messages.forEach { msg ->
                    if (msg.role.name == "USER") {
                        lastUser = msg.content
                    } else if (lastUser != null) {
                        appendLine("### ❓ سوال ${localDigits(qaCount)}: ${lastUser?.take(120)}...")
                        appendLine("- **پاسخ کلیدی:** ${BidiEngine.normalizePersianTypography(msg.content.take(350))}...")
                        appendLine("- **تعداد خطوط پاسخ کامل:** ${localDigits(msg.content.lines().size)} خط")
                        appendLine("\n---\n")
                        qaCount++
                        lastUser = null
                    }
                }
            }

            DistillationType.PURE_CODE -> buildString {
                appendLine("# 💻 استخراج خالص کدها و ماژول‌های نرم‌افزاری")
                appendLine("## پروژه: ${session.title}\n")

                // A number a Persian reader is spoken to goes through `localDigits`; the suggested file
                // name `Snippet_N.lang` keeps latin digits on purpose, because a name the user may type
                // is not a count said to them.
                var snippetCount = 0
                session.messages.forEachIndexed { msgIdx, msg ->
                    msg.codeSnippets.forEachIndexed { codeIdx, code ->
                        snippetCount++
                        appendLine("### 📦 فایل #${localDigits(snippetCount)}: Snippet_${snippetCount}.${code.language}")
                        appendLine("- **زبان:** ${code.language.uppercase()}")
                        appendLine("- **تعداد خطوط:** ${localDigits(code.lineCount)} خط")
                        appendLine("- **موقعیت:** پیام شماره ${localDigits(msgIdx + 1)} (${msg.role.name})")
                        appendLine("\n```${code.language}")
                        appendLine(code.code)
                        appendLine("```\n")
                    }
                }

                if (snippetCount == 0) {
                    appendLine("⚠️ هیچ بلوک کد استانداردی (با سه بک‌تیک) در این گفتگو شناسایی نشد.")
                }
            }

            DistillationType.CUSTOM_REWRITE -> buildString {
                appendLine("# ✨ خروجی پردازش سفارشی")
                appendLine("**درخواست شما:** ${customPrompt ?: "پالایش و خلاصه سازی"}\n")
                appendLine(BidiEngine.normalizePersianTypography(session.messages.joinToString("\n\n") { it.content }))
            }
        }
    }
}

/**
 * The id the response says actually answered. Google does not always serve the alias that was asked
 * for: a request for `gemini-3.1-flash-lite-preview` was measured answering `modelVersion` =
 * `gemini-3.1-flash-lite`. The record has to carry the served id, and a body without the field keeps
 * the requested alias rather than guessing a third name.
 */
internal fun servedModelOf(responseJson: JSONObject, requestedModel: String): String {
    val served = responseJson.optString("modelVersion").trim()
    return if (served.isEmpty() || served == "null") requestedModel else served
}

/**
 * Everything the offline summary is allowed to say about a conversation, measured from the text of
 * that same conversation. Counts are plain ints so a test can assert them with latin digits.
 * [proseLines] is the very lines of that text with the code fences taken out, so another section can
 * pick sentences out of it without re-reading the session.
 *
 * [bulletLines] and [numberedLines] are what the document may PRINT, so they stop at
 * [LOCAL_POINT_LIMIT]; [bulletLineCount] and [numberedLineCount] are what the text HAS, counted over
 * every matching line. A count of the printed list would be a wrong count of the text, so the two
 * concerns are kept apart here rather than rederived by whichever section reads the metrics.
 */
internal data class LocalConversationMetrics(
    val turnCount: Int,
    val userTurns: Int,
    val assistantTurns: Int,
    val systemTurns: Int,
    val codeBlockCount: Int,
    val codeLanguages: List<String>,
    val totalWords: Int,
    val distinctWords: Int,
    val vocabularyDensityPercent: Int,
    val topTerms: List<Pair<String, Int>>,
    val bulletLines: List<String>,
    val numberedLines: List<String>,
    val bulletLineCount: Int,
    val numberedLineCount: Int,
    val assistantOpenings: List<String>,
    val proseLines: List<String>
)

/**
 * Function words of Persian. The list describes the language, never one conversation, so the ranking
 * it feeds stays content-driven instead of template-driven.
 */
private val LOCAL_STOP_TERMS = setOf(
    "و", "در", "به", "از", "با", "که", "این", "آن", "هر", "خود", "را", "برای", "یک", "دو", "من",
    "ما", "شما", "تو", "او", "ایشان", "یا", "اگر", "هرچند", "بنابراین", "هم", "تا", "چون", "اما",
    "ولی", "پس", "بله", "نه", "باید", "می", "نمی", "است", "هست", "هستم", "هستند", "بود", "شد",
    "شود", "شده", "کرد", "کردم", "کند", "کنم", "کنید", "دارد", "ندارد", "همین", "تمام", "بیشتر",
    "کمتر", "چیزی", "وقتی", "چطور", "چرا", "کجا", "اینکه", "استفاده", "اینها", "آنها"
)

private const val LOCAL_TOP_TERM_LIMIT = 5
// Only what is PRINTED stops at this constant; what is COUNTED is the whole text and is never
// truncated by it, so the statistics line may not derive its count from the capped lists.
private const val LOCAL_POINT_LIMIT = 6
private const val LOCAL_EXCERPT_LIMIT = 300

private val LOCAL_PERSIAN_WORD = Regex("[\\u0600-\\u06FF\\u200C]{2,}")
private val LOCAL_CODE_FENCE = Regex("```[\\s\\S]*?```")
private val LOCAL_BULLET_LINE = Regex("^\\s*[-*\\u2022]\\s*(.+)$")
private val LOCAL_NUMBERED_LINE = Regex("^\\s*(?:\\d{1,3}|[\\u06F0-\\u06F9]{1,3})[.)]\\s*(.+)$")
private val LOCAL_SENTENCE_END = Regex("[.!?\\u061F\\u06D4\n]+")
private val LOCAL_LINE_LEAD = Regex("^[\\s>#*\\-\\u2022\\d.\\u06F0-\\u06F9)]+")

/** Persian digits for the user, from the one helper the project already has. */
private fun localDigits(value: Int): String = BidiEngine.toPersianDigits(value.toString())

/** One line of prose without the markdown decoration that wrapped it. */
private fun plainClause(raw: String): String {
    val stripped = LOCAL_LINE_LEAD.replace(raw, "")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return BidiEngine.normalizePersianTypography(stripped)
}

/** The first complete sentence of a turn, with code blocks left out of the running. */
private fun firstPlainSentence(content: String): String? {
    val masked = LOCAL_CODE_FENCE.replace(BidiEngine.normalizePersianTypography(content), " ")
    for (raw in masked.split(LOCAL_SENTENCE_END)) {
        val clause = plainClause(raw)
        if (clause.isNotBlank()) return clause
    }
    return null
}

/** Counts, terms and list items lifted out of this session's own text. */
internal fun measureLocalConversation(session: ChatSession): LocalConversationMetrics {
    val messages = session.messages
    val joined = messages.joinToString("\n") { BidiEngine.normalizePersianTypography(it.content) }
    val prose = LOCAL_CODE_FENCE.replace(joined, "\n")

    val words = joined.split(Regex("\\s+")).filter { it.isNotBlank() }
    val termCounts = LinkedHashMap<String, Int>()
    for (match in LOCAL_PERSIAN_WORD.findAll(prose)) {
        val term = match.value.trim('\u200C')
        if (term.length < 3 || term in LOCAL_STOP_TERMS) continue
        termCounts[term] = (termCounts[term] ?: 0) + 1
    }

    val snippets = messages.flatMap { BidiEngine.extractCodeBlocks(it.content).second }

    val bullets = mutableListOf<String>()
    val numbered = mutableListOf<String>()
    // Every matching line is counted; only the printed lists are capped. Counting the capped lists is
    // what used to report a conversation of eleven list lines as having six of them.
    var bulletLineCount = 0
    var numberedLineCount = 0
    for (line in prose.lines()) {
        if (line.isBlank()) continue
        val numberedMatch = LOCAL_NUMBERED_LINE.matchEntire(line)
        if (numberedMatch != null) {
            numberedLineCount++
            val text = plainClause(numberedMatch.groupValues[1])
            if (text.isNotBlank() && numbered.size < LOCAL_POINT_LIMIT) numbered.add(text)
            continue
        }
        val bulletMatch = LOCAL_BULLET_LINE.matchEntire(line)
        if (bulletMatch != null) {
            bulletLineCount++
            val text = plainClause(bulletMatch.groupValues[1])
            if (text.isNotBlank() && bullets.size < LOCAL_POINT_LIMIT) bullets.add(text)
        }
    }

    val assistantOpenings = messages.filter { it.role.name == "ASSISTANT" }
        .mapNotNull { firstPlainSentence(it.content) }
        .take(LOCAL_POINT_LIMIT)

    val distinctWords = words.toSet().size
    return LocalConversationMetrics(
        turnCount = messages.size,
        userTurns = messages.count { it.role.name == "USER" },
        assistantTurns = messages.count { it.role.name == "ASSISTANT" },
        systemTurns = messages.count { it.role.name == "SYSTEM" },
        codeBlockCount = snippets.size,
        codeLanguages = snippets.map { it.language }.distinct(),
        totalWords = words.size,
        distinctWords = distinctWords,
        vocabularyDensityPercent = if (words.isEmpty()) 0 else distinctWords * 100 / words.size,
        topTerms = termCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(LOCAL_TOP_TERM_LIMIT)
            .map { it.key to it.value },
        bulletLines = bullets,
        numberedLines = numbered,
        bulletLineCount = bulletLineCount,
        numberedLineCount = numberedLineCount,
        assistantOpenings = assistantOpenings,
        proseLines = prose.lines().filter { it.isNotBlank() }
    )
}

/**
 * The offline executive summary. Every line either quotes this conversation or counts it. The four
 * bullets this function used to print are gone on purpose: they were identical for every conversation
 * a user ever imported, whatever the text said.
 *
 * Only what is PRINTED stops at `LOCAL_POINT_LIMIT`; what is COUNTED is the whole text. The statistics
 * line reads the two counts of `measureLocalConversation`, never the size of the capped lists, so a
 * conversation with more list lines than the cap does not get reported as a shorter one.
 */
internal fun buildLocalExecutiveSummary(session: ChatSession, dateStr: String): String {
    val metrics = measureLocalConversation(session)
    val points = (metrics.bulletLines + metrics.numberedLines).take(LOCAL_POINT_LIMIT)

    val firstUserText = session.messages.firstOrNull { it.role.name == "USER" }?.content
    val openingSource = firstUserText ?: session.title
    val opening = plainClause(LOCAL_CODE_FENCE.replace(BidiEngine.normalizePersianTypography(openingSource), " "))
    val excerpt = if (opening.length > LOCAL_EXCERPT_LIMIT) {
        opening.take(LOCAL_EXCERPT_LIMIT).trimEnd() + "..."
    } else {
        opening
    }

    val roleLine = buildString {
        append("- پرسش‌های کاربر: ${localDigits(metrics.userTurns)}")
        append(" | پاسخ‌های دستیار: ${localDigits(metrics.assistantTurns)}")
        if (metrics.systemTurns > 0) append(" | پیام‌های سیستم: ${localDigits(metrics.systemTurns)}")
    }

    val codeLine = if (metrics.codeBlockCount > 0) {
        val languages = metrics.codeLanguages.joinToString(", ")
        "- بلوک‌های کد استخراج‌شده: ${localDigits(metrics.codeBlockCount)} قطعه" +
                (if (languages.isNotBlank()) " ($languages)" else "")
    } else {
        "- در متن هیچ بلوک کد استانداردی (با سه بک‌تیک) شناسایی نشد"
    }

    val termLine = if (metrics.topTerms.isNotEmpty()) {
        "- کلمات پرتکرار متن: " + metrics.topTerms.joinToString("، ") { "${it.first} (${localDigits(it.second)})" }
    } else {
        "- در متن هیچ کلمهٔ فارسیِ پرتکراری یافت نشد"
    }

    val steps = mutableListOf<String>()
    if (metrics.codeBlockCount > 0) {
        steps.add("بازبینی ${localDigits(metrics.codeBlockCount)} قطعه کد استخراج‌شده")
    } else {
        steps.add("این گفتگو قطعه کدی ندارد؛ کد را در ادامهٔ گفتگو بیفزایید")
    }
    if (points.isNotEmpty()) {
        steps.add("مرور ${localDigits(points.size)} نکته‌ای که از همین متن استخراج شد")
    } else {
        steps.add("نکتهٔ فهرست‌شده‌ای در متن نبود؛ خواستهٔ خود را در «دستور سفارشی» بنویسید")
    }
    steps.add("صدور سند رسمی با فرمت Word یا PDF جهت اشتراک‌گذاری")

    return buildString {
        appendLine("# 📊 خلاصه اجرایی و نکات راهبردی: ${session.title}")
        appendLine("**سامانه:** AIChatExporter | **تاریخ گزارش:** $dateStr\n")

        appendLine("## ۱. هدف و صورت مسئله")
        appendLine(excerpt.ifBlank { "*(نخستین پیام کاربر بدون نویسه بود)*" })
        if (firstUserText == null) {
            appendLine("*(در متن هیچ پیام کاربر پیدا نشد؛ عنوان گفتگو نشان داده شد)*")
        } else if (opening.length > LOCAL_EXCERPT_LIMIT) {
            appendLine(
                "*(نخستین پیام کاربر بریده شد: نمایش ${localDigits(LOCAL_EXCERPT_LIMIT)} از " +
                        "${localDigits(opening.length)} کاراکتر)*"
            )
        } else {
            appendLine("*(نخستین پیام کاربر: ${localDigits(opening.length)} کاراکتر)*")
        }

        appendLine("\n## ۲. مهم‌ترین نکات گفتگو (استخراج‌شده از متن)")
        if (points.isNotEmpty()) {
            for (point in points) appendLine("- $point")
        } else if (metrics.assistantOpenings.isNotEmpty()) {
            appendLine("*(در متن هیچ خطِ شماره‌دار یا بولت‌شده پیدا نشد؛ جمله‌های آغازینِ پاسخ‌ها آمده است)*")
            for (openingLine in metrics.assistantOpenings) appendLine("- $openingLine")
        } else {
            appendLine("*(این گفتگو نه خطِ شماره‌دار یا بولت‌شده دارد و نه پاسخِ غیرخالی)*")
        }

        appendLine("\n## ۳. آمار و مشخصات فنی")
        appendLine("- کل پیام‌های رد و بدل شده: ${localDigits(metrics.turnCount)}")
        appendLine(roleLine)
        appendLine(
            "- تعداد کلمات متن: ${localDigits(metrics.totalWords)} | کلمات مستقل: " +
                    "${localDigits(metrics.distinctWords)} | چگالی واژگان: ${localDigits(metrics.vocabularyDensityPercent)}٪"
        )
        appendLine(codeLine)
        appendLine(termLine)
        appendLine("- تخمین توکن‌های مصرفی: ${localDigits(session.tokenEstimate)} توکن")
        appendLine(
            "- خطوط شماره‌دار یا بولت‌شده در متن: " +
                    localDigits(metrics.bulletLineCount + metrics.numberedLineCount)
        )

        appendLine("\n## ۴. گام‌های اجرایی پیشنهادی (Next Steps)")
        steps.forEachIndexed { index, step ->
            appendLine("${localDigits(index + 1)}. $step")
        }
    }
}

/**
 * Topic words for the security and performance section of the technical document. The list names the
 * subject and never one conversation: which sentences end up under that heading is decided only by
 * what this session itself wrote. Every term is long enough not to hide inside an unrelated word.
 */
private val LOCAL_OPERATIONS_TERMS = listOf(
    "امنیت", "امنیتی", "رمزنگاری", "رمز", "احراز", "اعتبار", "حریم", "گذرواژه", "دسترسی", "مجوز",
    "آسیب‌پذیری", "تأیید", "تایید", "کارایی", "بهینه", "حافظه", "مصرف", "سرعت", "همزمانی",
    "security", "tls", "ssl", "oauth", "token", "secret", "cache", "memory", "thread", "latency"
)

/**
 * A release number the conversation itself wrote down. A number is read as a version only when the
 * word «نسخه» or a `v` stands in front of it, so an address, a price or a bare decimal is never
 * turned into a claim about a release. The digits come back in Persian, like every number the
 * exported document shows.
 */
private val LOCAL_VERSION_TOKEN = Regex(
    "(?:نسخه|[vV])[\\s\\u0654\\u200C:=\\-]*((?:[\\u06F0-\\u06F9]{1,4}\\.){1,2}[\\u06F0-\\u06F9]{1,4})"
)

/** The versions this session names, in the order the text writes them. Never an invented one. */
internal fun findLocalVersionTokens(session: ChatSession): List<String> {
    val text = BidiEngine.toPersianDigits(
        BidiEngine.normalizePersianTypography(
            session.title + "\n" + session.messages.joinToString("\n") { it.content }
        )
    )
    return LOCAL_VERSION_TOKEN.findAll(text)
        .map { it.groupValues[1] }
        .distinct()
        .take(LOCAL_POINT_LIMIT)
        .toList()
}

/** The lines of this conversation that speak of security or performance, as the text wrote them. */
private fun localOperationsNotes(metrics: LocalConversationMetrics): List<String> =
    metrics.proseLines
        .filter { line -> LOCAL_OPERATIONS_TERMS.any { term -> line.contains(term, ignoreCase = true) } }
        .map { plainClause(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(LOCAL_POINT_LIMIT)

/**
 * The offline technical document. The branch this function replaces printed one invented release
 * number and two architecture bullets that were written for every conversation anybody ever
 * imported, whatever the text said. A version now appears only when the session writes one down, the
 * security and performance section quotes sentences this conversation really contains, and a
 * conversation that speaks of neither is told that plainly instead of being papered over.
 */
internal fun buildLocalTechnicalDoc(session: ChatSession, dateStr: String): String {
    val metrics = measureLocalConversation(session)
    val versions = findLocalVersionTokens(session)
    val notes = localOperationsNotes(metrics)

    return buildString {
        appendLine("# ⚙️ مستندات فنی و راهنمای پیاده‌سازی")
        appendLine("## موضوع: ${session.title}")
        appendLine("**مدل معمار:** ${session.modelName} | **تاریخ:** $dateStr")
        if (versions.isNotEmpty()) {
            appendLine("**نسخه‌های نوشته‌شده در متن:** ${versions.joinToString("، ")}\n")
        } else {
            appendLine("*(در متن هیچ نسخه‌ای پیدا نشد)*\n")
        }

        appendLine("## ۱. نمای کلی سیستم و معماری")
        appendLine(
            "این مستند از گفتگوی پلتفرم ${session.platform.displayName} در ${localDigits(metrics.turnCount)} نوبت " +
                    "و ${localDigits(metrics.totalWords)} واژه ساخته شده و تنها آنچه در همین متن هست را نشان می‌دهد."
        )

        appendLine("\n## ۲. قطعات کد و پیاده‌سازی فنی")
        var codeIdx = 1
        session.messages.forEach { msg ->
            msg.codeSnippets.forEach { snippet ->
                appendLine("### قطعه کد ${localDigits(codeIdx)} (${snippet.language}):")
                appendLine("```${snippet.language}")
                appendLine(snippet.code)
                appendLine("```\n")
                codeIdx++
            }
        }

        if (codeIdx == 1) {
            appendLine("*(قطعه کد ساختاریافته در متن یافت نشد. متن کامل در بخش مستندات ذخیره گردید)*")
        }

        appendLine("\n## ۳. نکات امنیتی و کارایی (Performance & Security)")
        if (notes.isNotEmpty()) {
            for (note in notes) appendLine("- $note")
            appendLine("*(این نکات از همین متن استخراج شده‌اند)*")
        } else {
            appendLine("*(در متن هیچ نکتهٔ امنیتی یا کارایی پیدا نشد)*")
        }
    }
}

/**
 * The offline repair document. The header it replaces promised that the tone and the meaning of the
 * original were preserved in full, which `BidiEngine.repairDefectsOnly` cannot keep: inside that
 * function the broken characters are deleted, the invisible marks RLM and LRM are inserted, spaced
 * words are joined with a half-space and the feminine ha is rewritten. What the function provably never
 * does is INSERT a line: every rule rewrites inside the text of one line, and `lines()` / `joinToString`
 * hand back exactly one output line per input line. Two of its rules match whitespace across a break, so
 * a broken line can be pulled UP into the line before it (measured against the same patterns: a line
 * opening with a comma after a Persian word, and a line ending with the verb prefix, each come back as
 * one line instead of two). A line can be merged, never added — so that, and not «nothing was deleted»,
 * is what this header promises now.
 */
internal fun buildLocalRepairDoc(session: ChatSession, dateStr: String): String = buildString {
    appendLine("# 🩹 متن پالایش‌شده و رفع ایرادات کاراکتری (بدون افزودن سطر)")
    appendLine("**عنوان:** ${session.title}")
    appendLine("**پلتفرم:** ${session.platform.displayName} | **مدل:** ${session.modelName} | **تاریخ:** $dateStr")
    appendLine(
        "**ویژگی:** رفع حروف ناخوانا، اصلاح ی/ک عربی و چیدمان BiDi | بدون افزودن سطر و بدون توضیحات اضافی\n---\n"
    )

    session.messages.forEach { msg ->
        val senderName = if (msg.role.name == "USER") "👤 کاربر" else "🤖 دستیار هوش مصنوعی (${msg.modelTag ?: session.modelName})"
        appendLine("### $senderName:")
        appendLine(BidiEngine.repairDefectsOnly(msg.content))
        appendLine()
    }
}

/**
 * What the refiner answers with when no key is configured: the request quoted back and the text as it
 * stands. This engine only evens out the typography, it rewrites nothing, so the line that used to
 * call the result an improved version now says which of the two actually happened.
 */
internal fun buildLocalRefinePreview(userInstruction: String, currentContent: String): String =
    "✨ [حالت محلی] درخواست شما: «$userInstruction»\n\n" +
            "متن بدون بازنویسی برگردانده شد (فقط تایپوگرافی یکدست شد):\n" +
            BidiEngine.normalizePersianTypography(currentContent) +
            "\n\n*(جهت بازنویسی زنده با مدل هوش مصنوعی Gemini 3.5، کلید API را در تنظیمات وارد کنید)*"
