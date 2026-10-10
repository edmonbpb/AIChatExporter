package com.example.domain.parser

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.remote.gemini.GeminiClient
import com.example.domain.bidi.BidiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SharedLinkExtractor {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        // HTTP/2 is what let claude.ai answer with `103 Early Hints` instead of the page: measured on
        // the phone as «لایه ۱: HTTP ۱۰۳، ۰ بایت», while the same URL pushed over HTTP/1.1 answered
        // `200` with 138,436 bytes. One pinned protocol for all three tiers, since this client is
        // shared by them on purpose.
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /**
     * Optional key for the reading engine (`r.jina.ai`) that renders a client-side share page into
     * Markdown. `MainViewModel` owns the stored value and writes it here; `@Volatile` because the UI
     * thread writes while an IO-thread fetch reads. `extractFromUrl` sends it as `Authorization:
     * Bearer` in tier 2 only. Left empty, a share whose text is built in the browser is unreadable
     * through that tier, so the failure card names this field instead of blaming the network. A key
     * that the engine refuses (`401`) closes nothing: tier 2 spends exactly one more request without
     * the header before it reports the refusal — see `shouldRetryWithoutKey`.
     */
    @Volatile
    var readerApiKey: String? = null

    private val HTTP_URL_REGEX = Pattern.compile("https?://[\\w.-]+(?:\\.[a-zA-Z]{2,})+(?:/[^\\s]*)?", Pattern.CASE_INSENSITIVE)
    private val DOMAIN_REGEX = Pattern.compile("(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[^\\s]*)?", Pattern.CASE_INSENSITIVE)

    /**
     * Checks if the given text contains a valid web URL (with or without https://).
     */
    fun containsUrl(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        return HTTP_URL_REGEX.matcher(trimmed).find() || DOMAIN_REGEX.matcher(trimmed).find()
    }

    /**
     * Extracts the first URL from a string and ensures it has a protocol scheme.
     */
    fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        val httpMatcher = HTTP_URL_REGEX.matcher(trimmed)
        if (httpMatcher.find()) {
            return httpMatcher.group()
        }
        val domainMatcher = DOMAIN_REGEX.matcher(trimmed)
        if (domainMatcher.find()) {
            val domainUrl = domainMatcher.group()
            return if (!domainUrl.startsWith("http://", ignoreCase = true) && !domainUrl.startsWith("https://", ignoreCase = true)) {
                "https://$domainUrl"
            } else {
                domainUrl
            }
        }
        return null
    }

    /**
     * Normalizes a URL ensuring protocol scheme and trimming unnecessary trailing punctuation.
     */
    fun normalizeUrl(url: String): String {
        val clean = extractUrl(url) ?: url.trim()
        return clean.trimEnd(',', '.', ';', ')', ']', '>', '"', '\'')
    }

    /**
     * Detects AI platform preset from a given URL.
     */
    fun detectPlatformFromUrl(url: String): PlatformPreset {
        val lower = url.lowercase()
        return when {
            lower.contains("chatgpt.com") || lower.contains("chat.openai.com") -> PlatformPreset.CHATGPT
            lower.contains("claude.ai") || lower.contains("anthropic.com") -> PlatformPreset.CLAUDE
            lower.contains("deepseek.com") -> PlatformPreset.DEEPSEEK
            lower.contains("gemini.google.com") || lower.contains("aistudio.google.com") -> PlatformPreset.GEMINI
            lower.contains("perplexity.ai") -> PlatformPreset.PERPLEXITY
            else -> PlatformPreset.CUSTOM
        }
    }

    /**
     * Fetches and parses a shared AI link into a full ChatSession with multi-strategy fallbacks.
     */
    suspend fun extractFromUrl(
        url: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<ChatSession> = withContext(Dispatchers.IO) {
        val cleanUrl = extractUrl(url) ?: return@withContext Result.failure(
            IllegalArgumentException("آدرس لینک معتبر نیست. لطفاً یک آدرس وب صحیح وارد کنید.")
        )

        val platform = detectPlatformFromUrl(cleanUrl)

        // What each tier really got back (HTTP code, byte count, failure class). Silent nulls are what
        // made the old failure card an unverifiable claim: this list is pushed live through onProgress
        // AND appended verbatim to the terminal failure message, so the error card says which tier
        // failed how instead of asserting a rendering story we cannot prove.
        val diagnostics = mutableListOf<String>()

        // Tier 1: Direct HTTP fetch with rich Chrome headers
        onProgress?.invoke("در حال برقراری ارتباط با سرور ${platform.displayName}...")
        val directOutcome = tryDirectFetch(cleanUrl, 1, onProgress)
        diagnostics.add(progressLineFor(1, directOutcome))
        val directHtml = directOutcome.body

        if (!directHtml.isNullOrBlank()) {
            onProgress?.invoke("تحلیل ساختار داده‌های مکالمه...")
            val parsed = extractFromHtml(directHtml, platform, cleanUrl)
            if (parsed != null && !usable(parsed)) {
                diagnostics.add("${layer(1)}: ${persian(directOutcome.bytes)} بایت تحلیل شد ولی فقط یک سمت گفتگو در آن بود.")
            }
            val session = parsed?.takeIf { usable(it) }
            if (session != null) {
                onProgress?.invoke("تکمیل و نهایی‌سازی...")
                return@withContext Result.success(session)
            }
        }

        // Tier 2: Headless reader proxy (Jina Reader) which renders JS and bypasses Cloudflare Turnstile
        onProgress?.invoke("بارگیری امن محتوا از طریق موتور هوشمند رندر...")
        val jinaOutcome = tryFetchJinaReader(cleanUrl, 2, onProgress)
        diagnostics.add(progressLineFor(2, jinaOutcome))
        val jinaMarkdown = jinaOutcome.body
        if (!jinaMarkdown.isNullOrBlank()) {
            onProgress?.invoke("تحلیل و استخراج نوبت‌های گفتگو...")
            val parsed = parseMarkdownContent(jinaMarkdown, platform, cleanUrl, diagnostics)
            if (parsed != null && !usable(parsed)) {
                diagnostics.add("${layer(2)}: نوبت داشت ولی گفتگوی کامل (کاربر و مدل هر دو) نبود.")
            }
            val session = parsed?.takeIf { usable(it) }
            if (session != null) {
                onProgress?.invoke("تکمیل و نهایی‌سازی...")
                return@withContext Result.success(session)
            }
        }

        // Tier 3: Secondary open web proxy
        onProgress?.invoke("تلاش با اتصال پشتیبان...")
        val fallbackOutcome = tryFetchAllOrigins(cleanUrl, 3, onProgress)
        diagnostics.add(progressLineFor(3, fallbackOutcome))
        val fallbackHtml = fallbackOutcome.body
        if (!fallbackHtml.isNullOrBlank()) {
            val parsed = extractFromHtml(fallbackHtml, platform, cleanUrl)
            if (parsed != null && !usable(parsed)) {
                diagnostics.add("${layer(3)}: ${persian(fallbackOutcome.bytes)} بایت تحلیل شد ولی فقط یک سمت گفتگو در آن بود.")
            }
            val session = parsed?.takeIf { usable(it) }
            if (session != null) {
                onProgress?.invoke("تکمیل و نهایی‌سازی...")
                return@withContext Result.success(session)
            }
        }

        // The raw-text candidate of the last two tiers: rendered Markdown, else the stripped HTML.
        val rawCandidateText = jinaOutcome.body
            ?: directOutcome.body?.let { stripHtmlToText(it) }
            ?: fallbackOutcome.body?.let { stripHtmlToText(it) }
        val rawCandidateLength = rawCandidateText?.length ?: 0
        // The SAME cleaner the Markdown tier uses, so tiers 4 and 5 never feed page chrome to an API
        // call or wrap it as a conversation.
        val cleanCandidateText = rawCandidateText?.let { cleanShareMarkdown(it) }

        if (rawCandidateLength > 0 && cleanCandidateText.isNullOrBlank()) {
            diagnostics.add("پاک‌ساز، هر ${persian(rawCandidateLength)} کاراکار دریافتی را پوسته دانست و همه را حذف کرد؛ گفتگویی باقی نماند.")
        }

        // Tier 4: AI structuring if text was extracted but formatting was irregular. A minimum length
        // plus the conversation marker gate replace `usable(...)`, which cannot tell a scraped chrome
        // string from a real exchange.
        if (rawCandidateLength > 0 && !cleanCandidateText.isNullOrBlank() && GeminiClient.hasValidApiKey()) {
            when {
                cleanCandidateText.length < AI_STRUCTURING_MIN_CHARS -> diagnostics.add(
                    "${layer(4)}: فقط ${persian(cleanCandidateText.length)} کاراکار پاک‌شده ماند (حداقل ${persian(AI_STRUCTURING_MIN_CHARS)})؛ برای جلوگیری از هزینهٔ بی‌دلیل API فراخوانی نشد."
                )
                !looksLikeConversation(cleanCandidateText) -> diagnostics.add(
                    "${layer(4)}: هیچ نشانهٔ گفتگو در متن نبود؛ پوستهٔ صفحه به Gemini داده نشد."
                )
                else -> {
                    onProgress?.invoke("بازسازی ساختار مکالمه با هوش مصنوعی...")
                    val parsed = structureUnstructuredTextWithAi(cleanCandidateText.take(8000), platform)
                    if (parsed != null && !usable(parsed)) {
                        diagnostics.add("${layer(4)}: ساختاردهی مصنوعی فقط یک سمت گفتگو برگرداند.")
                    }
                    val session = parsed?.takeIf { usable(it) }
                    if (session != null) {
                        return@withContext Result.success(session)
                    }
                }
            }
        }

        // Tier 5: Fallback conversational wrapper. `createFallbackSession` manufactures one user and
        // one assistant row for ANY input, so `usable(fallbackSession)` here was always true and let
        // 481 characters of Tailwind class names through as a success. The gate is the TEXT now.
        if (rawCandidateLength > FALLBACK_MIN_TEXT_CHARS && !cleanCandidateText.isNullOrBlank()) {
            if (looksLikeConversation(cleanCandidateText)) {
                return@withContext Result.success(createFallbackSession(cleanCandidateText, platform, cleanUrl))
            }
            diagnostics.add("${layer(5)}: متن ${persian(cleanCandidateText.length)} کاراکتری نشانهٔ نوبت نداشت؛ پوستهٔ صفحه بود، نه مکالمه.")
        }

        val gotAnyBytes = listOf(directOutcome, jinaOutcome, fallbackOutcome).any { it.bytes > 0 }
        // Three headlines, most specific first, each one a fact a tier actually proved. Tier 2 refusing
        // for the reader key is NOT «the connection failed»: on the phone all three tiers returned
        // ۰ بایت precisely because the reading engine wanted a key, so the old second sentence blamed
        // the network for a setting the app never had. «No key» and «key refused» stay distinguishable
        // in the layer report, where their own two error texts are printed.
        val readerKeyAbsent = jinaOutcome.error == READER_KEY_MISSING_ERROR
        val readerKeyRejected = jinaOutcome.error == READER_KEY_REJECTED_ERROR
        val headline = when {
            readerKeyAbsent ->
                "متن این صفحه در مرورگر ساخته می‌شود و موتور خواندن بدونِ کلید بازش نمی‌کند؛ این برنامه کلید ندارد، پس لینک با کپیِ دستی وارد می‌شود.\n"
            readerKeyRejected ->
                "متن این صفحه در مرورگر ساخته می‌شود و موتور خواندن حتی با کلیدِ واردشده در تنظیمات بازش نکرد (کلید رد شد).\n"
            gotAnyBytes ->
                "متن گفتگو در خودِ این صفحه نیست؛ صفحه در مرورگر ساخته می‌شود و لینک آن برای ابزارهای خواندن متن بی‌محتوا است.\n"
            else ->
                "هیچ‌یک از سه مسیرِ دریافت، حتی یک بایت از سرور نگرفت؛ اتصال برقرار نشد یا سرور پاسخ نداد (شبکه و فیلترشکن را بررسی کنید).\n"
        }
        // The manual copy is the supported route, so it comes first; the key field is named after it,
        // and differently depending on whether a key exists at all.
        val remedy = "💡 راهکار: خودِ گفتگو را از همان صفحه کپی کنید و در تب «ورود متن یا فایل» جایگذاری فرمایید." +
            when {
                readerKeyAbsent ->
                    "\nاختیاری: اگر روزی کلید jina.ai/reader گرفتید، از تنظیمات ← «کلید موتور خواندن» واردش کنید تا لینک مستقیماً باز شود."
                readerKeyRejected ->
                    "\nکلیدِ خود را از تنظیمات ← «کلید موتور خواندن» بررسی کنید؛ تا پیش از آن همین کپیِ دستی کار می‌کند."
                else -> ""
            }
        val report = if (diagnostics.isEmpty()) "" else "\n\nگزارش لایه‌ها:\n" + diagnostics.joinToString("\n")

        Result.failure(
            Exception(headline + remedy + report)
        )
    }

    /** Persian digits for a number, so a diagnostic line reads the way the rest of the UI does. */
    private fun persian(value: Int): String = BidiEngine.toPersianDigits(value.toString())

    /** `لایه ۳` — the tier label used by both the live progress line and the failure report. */
    private fun layer(tier: Int): String = "لایه ${persian(tier)}"

    /**
     * Quality gate for every structured tier: a scrape that produced only one side of the exchange is
     * page chrome (or a prompt with no reply), not a conversation, so the tier must fall through
     * instead of being reported as a success. It is NOT the gate for the raw-text tiers, where the
     * session is fabricated from the text and would pass this check by construction.
     */
    private fun usable(session: ChatSession?): Boolean =
        session != null && session.messages.any { it.role == MessageRole.USER } &&
            session.messages.any { it.role == MessageRole.ASSISTANT }

    /**
     * What one network tier returned. The status code and the byte count used to be discarded on the
     * way out, so "the server said 429", "the proxy returned an empty body" and "the page has no
     * conversation in it" all looked identical to the caller — and to the user.
     */
    private data class FetchOutcome(val body: String?, val httpCode: Int, val bytes: Int, val error: String?)

    /** Raw text below this is not worth wrapping as a fallback session. */
    private const val FALLBACK_MIN_TEXT_CHARS = 50

    /** Page chrome scraped from a rendered share page sits in this range; a real transcript does not. */
    private const val AI_STRUCTURING_MIN_CHARS = 400

    /** A reader-proxy reply shorter than this is a rate-limit or error stub, not content. */
    private const val JINA_MIN_BODY_CHARS = 30

    /** Reported when the retry after an informational response is informational again; nothing arrived. */
    private const val REPEATED_INFORMATIONAL_ERROR = "پاسخ اطلاعاتی تکراری"

    // The two 401s of tier 2 are different facts and must not share a sentence: one is a missing
    // setting the user can fill, the other is a key the reading engine refused.
    private const val READER_KEY_MISSING_ERROR = "موتور خواندن کلید می‌خواهد (کلید موتور خواندن در تنظیمات پر نیست)"
    private const val READER_KEY_REJECTED_ERROR = "کلید موتور خواندن رد شد"

    // خطِ لایه ۲ وقتی کلید رد شد ولی تلاشِ بی‌کلید محتوا را آورد: هر دو واقعیت باید روی صفحه خوانده
    // شود، نه فقط یکی‌شان. همین رشته در فیلدِ `error`ی همان `FetchOutcome` می‌نشیند.
    private const val READER_KEY_REJECTED_OPENED_KEYLESS_ERROR = "کلیدتان رد شد، ولی بی‌کلید باز شد"

    // Said when a tier's own budget ran out. Nothing was sent, so this must NOT blame the network:
    // there was no request to fail. It names the ceiling instead.
    private const val READER_REQUEST_CEILING_ERROR = "سقفِ درخواست پر شد"

    /**
     * Sends one request and returns the first response that is not informational, plus the flag
     * `true` when the retry itself stayed informational. A `1xx` (`103 Early Hints`, measured on
     * claude.ai) carries no body, so it is closed and the SAME request is sent exactly once more —
     * there is no loop, and a second `1xx` is reported as a failure with its code kept visible.
     * The returned response is always open, including in the flagged case, so the caller closes it.
     *
     * [budget] is spent before every `execute()`, so this function can never send more requests than
     * its tier owns. When the budget is empty it returns `null` INSTEAD of a response: nothing went
     * out, so there is nothing for the caller to read and nothing to close.
     */
    private fun executeFinalResponse(request: Request, budget: ReaderRequestBudget): Pair<Response?, Boolean> {
        if (!budget.spend()) return null to false
        val first = httpClient.newCall(request).execute()
        if (first.code !in 100..199) return first to false
        first.close()
        if (!budget.spend()) return null to false
        val second = httpClient.newCall(request).execute()
        return second to (second.code in 100..199)
    }

    /** The outcome a tier returns when its budget ran out: nothing was sent, and that is the fact. */
    private fun budgetRefusedOutcome(): FetchOutcome = FetchOutcome(
        body = null,
        httpCode = 0,
        bytes = 0,
        error = READER_REQUEST_CEILING_ERROR
    )

    /** The outcome every tier shares when the retry proved there is no final response to read. */
    private fun informationalOutcome(response: Response): FetchOutcome {
        response.close()
        return FetchOutcome(
            body = null,
            httpCode = response.code,
            bytes = 0,
            error = REPEATED_INFORMATIONAL_ERROR
        )
    }

    private fun tryDirectFetch(url: String, tier: Int, onProgress: ((String) -> Unit)?): FetchOutcome {
        // Tier 1 owns its own budget: the ceiling is per layer, so a tier-2 refusal can never take
        // the tier-1 `103` retry away — that retry is request 2 of 2 and still fits.
        val budget = ReaderRequestBudget()
        val outcome = try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "fa,en-US;q=0.9,en;q=0.8")
                .header("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("Upgrade-Insecure-Requests", "1")
                .build()

            val (response, repeatedInformational) = executeFinalResponse(request, budget)
            if (response == null) {
                budgetRefusedOutcome()
            } else if (repeatedInformational) {
                informationalOutcome(response)
            } else {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    FetchOutcome(
                        body = body,
                        httpCode = it.code,
                        bytes = body?.length ?: 0,
                        error = if (body == null) "پاسخ خالی یا غیرموفق" else null
                    )
                }
            }
        } catch (e: Exception) {
            FetchOutcome(body = null, httpCode = 0, bytes = 0, error = e.javaClass.simpleName)
        }
        onProgress?.invoke(progressLineFor(tier, outcome))
        return outcome
    }

    /**
     * ONE request to the reading engine for the given key (`null` means the keyless shape, so no
     * `Authorization` header is sent), turned into the tier outcome with the rejection mapping that
     * records why an otherwise readable body was thrown away. [budget] is the tier's, so the
     * informational re-send here is charged to the same ceiling the keyless retry has to fit in.
     */
    private fun fetchJinaReader(url: String, key: String?, budget: ReaderRequestBudget): FetchOutcome {
        val jinaUrl = "https://r.jina.ai/$url"
        val request = Request.Builder()
            .url(jinaUrl)
            .header("Accept", "text/plain")
            .header("X-Return-Format", "markdown")
            .header("User-Agent", "Mozilla/5.0")
            .apply { if (key != null) header("Authorization", "Bearer $key") }
            .build()

        val (response, repeatedInformational) = executeFinalResponse(request, budget)
        if (response == null) return budgetRefusedOutcome()
        if (repeatedInformational) return informationalOutcome(response)
        return response.use {
            val body = if (it.isSuccessful) it.body?.string() ?: "" else ""
            val rejection = when {
                it.code == 401 && key == null -> READER_KEY_MISSING_ERROR
                it.code == 401 -> READER_KEY_REJECTED_ERROR
                !it.isSuccessful -> "پاسخ غیرموفق موتور خواندن"
                body.contains("AbuseAlleviationError") -> "محدودیت نرخ موتور خواندن (AbuseAlleviationError)"
                body.length < JINA_MIN_BODY_CHARS -> "پاسخ موتور خواندن کوتاه‌تر از ${persian(JINA_MIN_BODY_CHARS)} نویسه بود"
                else -> null
            }
            FetchOutcome(
                body = if (rejection == null) body else null,
                httpCode = it.code,
                bytes = body.length,
                error = rejection
            )
        }
    }

    private fun tryFetchJinaReader(url: String, tier: Int, onProgress: ((String) -> Unit)?): FetchOutcome {
        // Read the key once: a settings write between the check and the request must not send a
        // header the caller did not account for.
        val key = readerApiKey?.trim()?.takeIf { it.isNotEmpty() }
        // Tier 2 owns its budget, and that budget IS the layer ceiling: the keyed attempt and the
        // keyless retry both spend from it, so the 1xx re-sends included the layer cannot exceed 2.
        val budget = ReaderRequestBudget()
        val outcome = try {
            val keyedOutcome = fetchJinaReader(url, key, budget)
            // یک کلیدِ کهنه نباید درِ بی‌کلید را ببندد: همان درخواست، دقیقاً یک بار، بدونِ
            // `Authorization`. حلقه و بازگشتِ زمانی ندارد؛ سقفِ این لایه دو درخواست است.
            val mayRetryKeyless = shouldSendKeylessRetry(keyedOutcome.httpCode, key != null, budget)
            val keylessOutcome = if (mayRetryKeyless) fetchJinaReader(url, null, budget) else null
            // تلاشِ بی‌کلید موفق بود، همان بدنه برمی‌گردد و `error` جملۀ صادقِ همان وضعیت می‌شود؛
            // اگر آن هم شکست، نتیجۀ «کلید رد شد» مثلِ امروز می‌ماند.
            if (keylessOutcome != null && keylessOutcome.error == null) {
                keylessOutcome.copy(error = READER_KEY_REJECTED_OPENED_KEYLESS_ERROR)
            } else {
                keyedOutcome
            }
        } catch (e: Exception) {
            FetchOutcome(body = null, httpCode = 0, bytes = 0, error = e.javaClass.simpleName)
        }
        onProgress?.invoke(progressLineFor(tier, outcome))
        return outcome
    }

    private fun tryFetchAllOrigins(url: String, tier: Int, onProgress: ((String) -> Unit)?): FetchOutcome {
        val budget = ReaderRequestBudget()
        val outcome = try {
            val encoded = URLEncoder.encode(url, "UTF-8")
            val proxyUrl = "https://api.allorigins.win/raw?url=$encoded"
            val request = Request.Builder()
                .url(proxyUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val (response, repeatedInformational) = executeFinalResponse(request, budget)
            if (response == null) {
                budgetRefusedOutcome()
            } else if (repeatedInformational) {
                informationalOutcome(response)
            } else {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    FetchOutcome(
                        body = body,
                        httpCode = it.code,
                        bytes = body?.length ?: 0,
                        error = if (body == null) "پاسخ خالی یا غیرموفق" else null
                    )
                }
            }
        } catch (e: Exception) {
            FetchOutcome(body = null, httpCode = 0, bytes = 0, error = e.javaClass.simpleName)
        }
        onProgress?.invoke(progressLineFor(tier, outcome))
        return outcome
    }

    /**
     * One line per network tier, live through `onProgress` and kept for the failure report: the HTTP
     * status the server actually returned, how many characters came with it, and why it was thrown
     * away. `۰` in place of a status code means the call never got a response at all.
     */
    private fun progressLineFor(tier: Int, outcome: FetchOutcome): String = buildString {
        append(layer(tier))
        append(": ")
        append(if (outcome.httpCode > 0) "HTTP ${persian(outcome.httpCode)}" else "بی‌پاسخ")
        append("، ${persian(outcome.bytes)} بایت")
        outcome.error?.let { append("، $it") }
    }

    /**
     * Every strategy inside a structured tier is tried until one yields a COMPLETE exchange: a
     * non-null but one-sided result used to short-circuit the `?:` chain, which killed the whole tier
     * and let a half-parsed real page fall through to the fabricated fallback session.
     */
    private fun extractFromHtml(html: String, platform: PlatformPreset, cleanUrl: String): ChatSession? {
        return when (platform) {
            PlatformPreset.CHATGPT -> {
                ChatGptStreamDecoder.decode(html)?.let { sessionFromTurns(it, cleanUrl) }?.takeIf { usable(it) }
                    ?: extractChatGptFromNextData(html)?.takeIf { usable(it) }
                    ?: extractChatGptFromRsc(html)?.takeIf { usable(it) }
                    ?: extractGenericHtmlContent(html, platform, cleanUrl)?.takeIf { usable(it) }
            }
            PlatformPreset.CLAUDE -> {
                extractClaudeFromHtml(html, cleanUrl)?.takeIf { usable(it) }
                    ?: extractGenericHtmlContent(html, platform, cleanUrl)?.takeIf { usable(it) }
            }
            PlatformPreset.PERPLEXITY -> {
                extractPerplexityFromHtml(html, cleanUrl)?.takeIf { usable(it) }
                    ?: extractGenericHtmlContent(html, platform, cleanUrl)?.takeIf { usable(it) }
            }
            else -> {
                extractGenericNextData(html, platform)?.takeIf { usable(it) }
                    ?: extractGenericHtmlContent(html, platform, cleanUrl)?.takeIf { usable(it) }
            }
        }
    }

    /**
     * The reader-proxy Markdown tier. `diagnostics` is filled instead of returning a bare `null`, so
     * "the cleaner ate the whole reply" and "the reply held no conversation" stay two different words.
     */
    private fun parseMarkdownContent(
        rawMarkdown: String,
        platform: PlatformPreset,
        url: String,
        diagnostics: MutableList<String>
    ): ChatSession? {
        try {
            val titleMatcher = Pattern.compile("(?m)^Title:\\s*(.+)$").matcher(rawMarkdown)
            val extractedTitle = if (titleMatcher.find()) {
                titleMatcher.group(1)?.trim()?.takeIf { it.isNotBlank() }
            } else null

            val cleanMarkdown = cleanShareMarkdown(rawMarkdown)
            if (cleanMarkdown.isBlank()) {
                if (rawMarkdown.isNotBlank()) {
                    diagnostics.add(
                        "${layer(2)}: پاک‌ساز هر ${persian(rawMarkdown.length)} کاراکار را پوسته دانست و حذف کرد؛ چیزی برای تجزیه نماند."
                    )
                }
                return null
            }

            val parsed = TranscriptIngestionEngine.parseTranscript(cleanMarkdown, platform, extractedTitle)
            if (parsed.messages.isNotEmpty()) {
                return parsed
            }
            diagnostics.add(
                "${layer(2)}: ${persian(cleanMarkdown.length)} کاراکار ماند ولی هیچ نوبت گفتگو از آن ساخته نشد (نشانهٔ کاربر/مدل پیدا نشد)."
            )
        } catch (e: Exception) {
            diagnostics.add("${layer(2)}: خطای تجزیه (${e.javaClass.simpleName}).")
        }
        return null
    }

    internal fun sessionFromTurns(payload: SharedConversationPayload, url: String): ChatSession? {
        if (payload.turns.isEmpty()) return null
        val messages = payload.turns.map { createMessage(it.role, it.content, payload.modelSlug) }
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = payload.title,
            platform = PlatformPreset.CHATGPT,
            modelName = payload.modelName,
            messages = messages,
            // The decoder counted every turn it looked at and could not carry as text; the banner needs it.
            rejectedTurns = payload.rejectedTurns
        )
    }

    private fun extractChatGptFromNextData(html: String): ChatSession? {
        try {
            val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">([\\s\\S]*?)</script>")
            val matcher = nextDataPattern.matcher(html)
            if (matcher.find()) {
                val jsonStr = matcher.group(1) ?: ""
                val root = JSONObject(jsonStr)
                val props = root.optJSONObject("props")
                val pageProps = props?.optJSONObject("pageProps")
                val serverResponse = pageProps?.optJSONObject("serverResponse")
                val data = serverResponse?.optJSONObject("data") ?: pageProps?.optJSONObject("sharedConversation")

                if (data != null) {
                    val title = data.optString("title", "گفتگوی ChatGPT")
                    // `default_model_slug` is the only model this page is allowed to speak of; `auto`
                    // and a missing key both mean the source never named one.
                    val statedSlug = data.optString("default_model_slug")
                        .takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
                    val linearConv = data.optJSONArray("linear_conversation")
                    if (linearConv != null && linearConv.length() > 0) {
                        val messages = mutableListOf<ChatMessage>()
                        for (i in 0 until linearConv.length()) {
                            val item = linearConv.getJSONObject(i)
                            val msg = item.optJSONObject("message") ?: item
                            val author = msg.optJSONObject("author")
                            val roleStr = author?.optString("role", "user")?.uppercase() ?: "USER"
                            val role = if (roleStr == "ASSISTANT") MessageRole.ASSISTANT else MessageRole.USER
                            val contentObj = msg.optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")

                            val textBuilder = StringBuilder()
                            if (parts != null) {
                                for (p in 0 until parts.length()) {
                                    val part = parts.optString(p, "")
                                    if (part.isNotBlank()) textBuilder.append(part).append("\n")
                                }
                            } else {
                                val text = contentObj?.optString("text", "") ?: ""
                                textBuilder.append(text)
                            }

                            val cleanText = textBuilder.toString().trim()
                            if (cleanText.isNotBlank()) {
                                messages.add(createMessage(role, cleanText, statedSlug))
                            }
                        }
                        if (messages.isNotEmpty()) {
                            return ChatSession(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                platform = PlatformPreset.CHATGPT,
                                modelName = statedSlug ?: PlatformPreset.CHATGPT.displayName,
                                messages = messages
                            )
                        }
                    }

                    if (data.has("mapping")) {
                        return TranscriptIngestionEngine.parseTranscript(data.toString(), PlatformPreset.CHATGPT)
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractChatGptFromRsc(html: String): ChatSession? {
        try {
            val rscPattern = Pattern.compile("self\\.__next_f\\.push\\(\\[1,\"([\\s\\S]*?)\"\\]\\)")
            val matcher = rscPattern.matcher(html)
            val combined = StringBuilder()
            while (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                combined.append(raw.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\"))
            }
            val text = combined.toString()
            if (text.contains("linear_conversation") || text.contains("\"role\":\"assistant\"")) {
                val parsed = TranscriptIngestionEngine.parseTranscript(text, PlatformPreset.CHATGPT)
                if (parsed.messages.isNotEmpty()) return parsed
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractClaudeFromHtml(html: String, fallbackUrl: String): ChatSession? {
        try {
            val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">([\\s\\S]*?)</script>")
            val matcher = nextDataPattern.matcher(html)
            if (matcher.find()) {
                val jsonStr = matcher.group(1) ?: ""
                val root = JSONObject(jsonStr)
                val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps")
                val chatMessages = pageProps?.optJSONArray("chat_messages")
                    ?: pageProps?.optJSONObject("sharedConversation")?.optJSONArray("messages")

                if (chatMessages != null && chatMessages.length() > 0) {
                    val title = pageProps?.optString("title", "گفتگوی Claude") ?: "گفتگوی Claude"
                    val messages = mutableListOf<ChatMessage>()

                    for (i in 0 until chatMessages.length()) {
                        val item = chatMessages.getJSONObject(i)
                        val sender = item.optString("sender", item.optString("role", "human")).lowercase()
                        val role = if (sender.contains("assistant") || sender.contains("claude")) {
                            MessageRole.ASSISTANT
                        } else {
                            MessageRole.USER
                        }
                        val text = item.optString("text", item.optString("content", ""))
                        if (text.isNotBlank()) {
                            messages.add(createMessage(role, text))
                        }
                    }

                    if (messages.isNotEmpty()) {
                        return ChatSession(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            platform = PlatformPreset.CLAUDE,
                            // The Claude page shell never states a model slug; the platform name is all
                            // this reader is allowed to claim.
                            modelName = PlatformPreset.CLAUDE.displayName,
                            messages = messages
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractPerplexityFromHtml(html: String, fallbackUrl: String): ChatSession? {
        try {
            val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            val title = if (titleMatcher.find()) titleMatcher.group(1)?.replace(Regex("- Perplexity.*"), "")?.trim() ?: "جستجوی Perplexity" else "جستجوی Perplexity"
            val nextData = extractGenericNextData(html, PlatformPreset.PERPLEXITY)
            if (nextData != null) return nextData.copy(title = title)
        } catch (_: Exception) {}
        return null
    }

    private fun extractGenericNextData(html: String, platform: PlatformPreset): ChatSession? {
        try {
            val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">([\\s\\S]*?)</script>")
            val matcher = nextDataPattern.matcher(html)
            if (!matcher.find()) return null

            val jsonStr = matcher.group(1) ?: return null
            return TranscriptIngestionEngine.parseTranscript(jsonStr, platform)
        } catch (_: Exception) {
            return null
        }
    }

    private fun extractGenericHtmlContent(html: String, platform: PlatformPreset, url: String): ChatSession? {
        try {
            val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            val title = if (titleMatcher.find()) {
                titleMatcher.group(1)?.replace(Regex("(?i)(ChatGPT|Claude|Perplexity|DeepSeek|AI|Chat|Share).*"), "")?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "مکالمه اشتراک‌گذاری‌شده"
            } else {
                "مکالمه اشتراک‌گذاری‌شده"
            }

            val text = stripHtmlToText(html)
            if (text.isNotBlank()) {
                val parsed = TranscriptIngestionEngine.parseTranscript(text, platform, title)
                if (parsed.messages.isNotEmpty()) {
                    return parsed
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun stripHtmlToText(html: String): String {
        return html
            .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?i)<nav[\\s\\S]*?</nav>"), "")
            .replace(Regex("(?i)<header[\\s\\S]*?</header>"), "")
            .replace(Regex("(?i)<footer[\\s\\S]*?</footer>"), "")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</p>"), "\n\n")
            .replace(Regex("</div>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun createFallbackSession(text: String, platform: PlatformPreset, url: String): ChatSession {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val title = lines.firstOrNull()?.take(50) ?: "مکالمه استخراج‌شده از لینک"
        val body = lines.drop(1).joinToString("\n\n").takeIf { it.isNotBlank() } ?: text

        val messages = listOf(
            createMessage(MessageRole.USER, "محتوای استخراج شده از لینک:\n$url"),
            createMessage(MessageRole.ASSISTANT, body)
        )

        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            platform = platform,
            // Nothing in a scraped shell names a model, so the shell is labelled with its platform only.
            modelName = platform.displayName,
            messages = messages
        )
    }

    private suspend fun structureUnstructuredTextWithAi(text: String, platform: PlatformPreset): ChatSession? {
        return try {
            val prompt = """
                Extract the conversational dialog turns from the following web page content.
                Format the result strictly as a JSON object with:
                {
                  "title": "short informative title in Persian",
                  "messages": [
                    { "role": "user", "content": "..." },
                    { "role": "assistant", "content": "..." }
                  ]
                }
                Do not include markdown backticks or any preamble. Only the JSON object.
                
                Content:
                $text
            """.trimIndent()

            val aiResponse = GeminiClient.generateRawContent(prompt).getOrNull() ?: return null
            val cleanJson = aiResponse.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            TranscriptIngestionEngine.parseTranscript(cleanJson, platform)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `modelSlug` is the model the share page itself named. It stays `null` for every reader that only
     * knows the platform, so a bubble can never show a model nobody stated.
     */
    private fun createMessage(role: MessageRole, content: String, modelSlug: String? = null): ChatMessage {
        val (clean, snippets) = BidiEngine.extractCodeBlocks(content)
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            role = role,
            content = content,
            direction = BidiEngine.detectDirection(content),
            modelTag = if (role == MessageRole.ASSISTANT) modelSlug else null,
            codeSnippets = snippets
        )
    }
}

/**
 * The whole decision behind the second request of tier 2, kept away from the network so it is testable.
 * A `401` that came back WHILE a key was sent is the only fact that proves the key — not the link — is
 * what closed the door, so asking the same engine once more without `Authorization` can open a path
 * that plainly works keyless (measured on 2026-10-09: the same Claude share link imports four real
 * turns with no key at all). Every other answer is already the last word: `401` with no key is the
 * missing-setting case, `2xx` is a success, `429` is the rate limit and `5xx` the engine itself, and a
 * keyless attempt that failed has no third try left — the ceiling of the tier is two requests, so this
 * is called for the keyed attempt and never for the retry.
 */
internal fun shouldRetryWithoutKey(httpCode: Int, hadKey: Boolean): Boolean = httpCode == 401 && hadKey

/**
 * Hard ceiling on the network requests ONE tier may spend, measured per layer and not per program:
 * `executeFinalResponse` answers a `1xx` by re-sending the same request, and tier 2 may then send a
 * keyless retry, so without a counter the reading tier could reach FOUR requests on one import
 * (measured shape: `103` then `401` on the keyed call, `103` then another send on the keyless one).
 */
internal const val READER_TIER_MAX_REQUESTS = 2

/**
 * The counter every real network call of a tier spends from. Pure arithmetic, no Android and no
 * socket: a tier spends BEFORE `execute()`, so a refused `spend()` means no request goes out at all
 * rather than one more that has to be walked back. `max = 1` is a tier allowed a single request.
 */
internal class ReaderRequestBudget(val max: Int = READER_TIER_MAX_REQUESTS) {
    var used: Int = 0
        private set

    val exhausted: Boolean get() = used >= max

    /** `true` when the caller may send now; `false` leaves [used] untouched. */
    fun spend(): Boolean {
        if (exhausted) return false
        used++
        return true
    }
}

/**
 * The final decision `tryFetchJinaReader` makes, kept away from the network for the same reason
 * [shouldRetryWithoutKey] is. The refusal alone is not enough: a refused key earns ONE keyless
 * retry, and only while the tier still has a request left to spend, so a keyed attempt that had
 * already spent two (`103` then `401`) leaves the tier nothing to re-send and the keyed refusal
 * stands as the last word. This does not spend — the send that follows spends for itself.
 */
internal fun shouldSendKeylessRetry(
    keyedHttpCode: Int,
    hadKey: Boolean,
    budget: ReaderRequestBudget
): Boolean = shouldRetryWithoutKey(keyedHttpCode, hadKey) && !budget.exhausted

/**
 * Strips the page chrome the reader layer wraps around a shared-chat Markdown body, before that body
 * reaches the transcript parser: framework lines, tool-only rows, icon-font glyphs left over from the
 * rendered page, the first paragraph of a turn repeated from its own marker line, and runs of blank
 * lines. Pure text work with no Android calls.
 */
internal fun cleanShareMarkdown(rawMarkdown: String): String {
    val kept = mutableListOf<String>()
    var repeatedText: String? = null
    // Chrome is only recognisable as chrome BEFORE the first turn marker. After that any line can be
    // part of the real answer — a model-written `Title: My great post`, for instance — so the prefix
    // rules switch themselves off and only the measured bare-date/tool rows keep being dropped.
    var sawMarker = false

    for (line in rawMarkdown.lines()) {
        // گلیف‌های آیکون‌فونت پیش از هر تصمیمی از سطر می‌افتند، چون سطرِ نگه‌داشته‌شده همین
        // `cleanLine` است و پاک‌کردنِ یک‌بارِ آن هم `trimmed` و هم خروجی را تمیز نگه می‌دارد.
        val cleanLine = stripIconGlyphs(line)
        val trimmed = cleanLine.trim()

        if (trimmed.isEmpty()) {
            // A single blank line is the paragraph break the parser needs; the rest collapse into it.
            if (kept.lastOrNull()?.isEmpty() != true) kept.add("")
            continue
        }

        val markerLine = isShareMarkerLine(trimmed)
        if (!markerLine && isShareFrameworkLine(trimmed, beforeFirstMarker = !sawMarker)) continue
        if (!markerLine && isShareToolLine(trimmed)) continue

        if (trimmed == repeatedText) {
            repeatedText = null
            continue
        }

        if (markerLine) sawMarker = true

        repeatedText = SHARE_TURN_MARKER_REGEX.find(trimmed)
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

        kept.add(cleanLine)
    }

    return kept.joinToString("\n").trim()
}

/**
 * The conversation gate for the raw-text tiers. A text is a conversation only when a turn marker
 * carries its own turn text on the same line (`## You said: …`, `Claude responded: …`, `User: …`), or
 * when TWO bare marker lines of DIFFERENT roles are present — one user-side, one model-side. A single
 * bare speaker name is never enough: the real Claude share shell opens with the lone line `Claude`
 * followed by Tailwind class soup and a Korean corporate footer, and that is chrome, not a turn. Two
 * bare names of the SAME side (`Claude` + `Assistant`) are still chrome for the same reason.
 * Conversation-shaped JSON (`"role"`/`"author"` alongside `"content"`) stays accepted.
 */
internal fun looksLikeConversation(text: String): Boolean {
    if (text.isBlank()) return false
    if (text.lineSequence().any { SHARE_TURN_MARKER_REGEX.matches(it.trim()) }) return true

    val rolesSeen = text.lineSequence()
        .map { it.trim() }
        .filter { SHARE_BARE_MARKER_REGEX.matches(it) }
        .mapNotNull { shareSpeakerRoleOf(it) }
        .toSet()
    if (ShareSpeakerRole.USER in rolesSeen && ShareSpeakerRole.MODEL in rolesSeen) return true

    val hasSpeakerKey = text.contains("\"role\"") || text.contains("\"author\"")
    return hasSpeakerKey && text.contains("\"content\"")
}

/**
 * Model labels this app INVENTED in earlier builds and wrote into stored sessions. ChatGPT and Claude
 * are listed because `PlatformPreset.defaultModel` used to stamp exactly these two strings on every
 * imported turn, whatever the page said. A label a source really named (`DeepSeek-R1` written by the
 * user, `gpt-4o-2024-08-06` read from a page) is a fact and must survive — so the entries are the exact
 * old constants, never a family of names. Measured on the emulator (2026-10-10) a restored Claude draft
 * from the previous build still displayed «Claude 3.5 Sonnet» in the bubble header with nothing in the
 * share page stating it, which is why CLAUDE is in here and not only CHATGPT.
 */
private val LEGACY_INVENTED_MODELS: Map<PlatformPreset, Set<String>> = mapOf(
    PlatformPreset.CHATGPT to setOf("GPT-4o"),
    PlatformPreset.CLAUDE to setOf("Claude 3.5 Sonnet")
)

/** Below this many turns an import is a fragment even when nothing was measurably dropped. */
private const val PARTIAL_IMPORT_MIN_TURNS = 3

/**
 * The reading half of the honesty fix — the sibling of `LEGACY_INVENTED_EMAILS` in `MainViewModel`.
 * Old library rows and old drafts still hold the invented «GPT-4o»; nothing rewrites them (Room is on
 * `version = 1` with destructive fallback, so a migration would delete the user's library), so every
 * path that LOADS a stored label passes it here first. `null` means «the source never stated a model»,
 * which the bubbles render without a model bracket.
 */
internal fun scrubInventedModel(platform: PlatformPreset, stored: String?): String? {
    if (stored == null) return null
    val invented = LEGACY_INVENTED_MODELS[platform] ?: return stored
    return if (stored.trim() in invented) null else stored
}

/**
 * The one honest sentence about an import that did not read everything. Exactly two cases, and each
 * speaks only what was measured:
 * (a) the decoder COUNTED refusals on the page, so the drop count is named beside the count that
 * arrived — a drop figure alone tells the user nothing about how much did come through;
 * (b) nothing was ever counted (`rejectedTurns` is `0`, which `ChatSession` defines as «never counted»,
 * NOT as «zero rejected») and the read came out with so few turns that a whole side of the exchange is
 * obviously missing. This case names NO drop figure: `rejectedTurns` has no Room column
 * (`data/local/Entities.kt`), so a share page that was written to the library and reloaded comes back
 * with `0`, and printing a zero drop count for it would be a measurement nobody took — the same class
 * of fabricated number this round deletes elsewhere.
 * Pure: no Compose, no Android API. The banner in `WorkspaceScreen` renders this string and the
 * one-line toast in `MainViewModel` is gated on this function returning `null`.
 */
internal fun partialImportNotice(session: ChatSession): String? {
    val dropped = session.rejectedTurns
    val kept = session.messages.size
    if (dropped <= 0 && kept >= PARTIAL_IMPORT_MIN_TURNS) return null

    val keptText = BidiEngine.toPersianDigits(kept.toString())

    // (b) few turns, never counted: the short read is said out loud, the drop figure is not invented.
    if (dropped <= 0) {
        return "این گفتگو کامل خوانده نشد؛ تنها ${keptText} نوبت از آن خوانده شد."
    }

    // (a) a measured drop: both numbers the reader of the banner needs.
    val droppedText = BidiEngine.toPersianDigits(dropped.toString())
    return "این گفتگو کامل خوانده نشد؛ ${droppedText} نوبت رد شد و تنها ${keptText} نوبت از آن خوانده شد."
}

/** Which side of the exchange a speaker name belongs to. */
private enum class ShareSpeakerRole { USER, MODEL }

/**
 * User-side names, in the order the alternation below needs: the longer phrases before the shorter
 * ones they start with, so `You said:` never binds as `You:`.
 */
private val SHARE_USER_SPEAKER_NAMES = listOf(
    "You said", "Your prompt", "Prompt", "Query", "Human", "کاربر", "پرسش", "User", "You"
)

/** Model-side names, likewise longest first: `Claude responded` must not bind as `Claude`. */
private val SHARE_MODEL_SPEAKER_NAMES = listOf(
    "Claude responded", "Claude replied", "Claude said", "ChatGPT said", "هوش مصنوعی", "DeepSeek",
    "Perplexity", "Assistant", "ChatGPT", "Claude", "Gemini", "Ollama", "Model", "پاسخ", "AI"
)

/**
 * The speaker names this pipeline accepts: the union of the two role lists above, so the cleaner, the
 * conversation gate and `TranscriptIngestionEngine.parseTextTurns` never drift into four different
 * versions of the same list. The membership is unchanged from the single-list version — only the
 * grouping is new, and only `looksLikeConversation` reads the roles.
 */
private val SHARE_SPEAKER_NAMES = SHARE_USER_SPEAKER_NAMES + SHARE_MODEL_SPEAKER_NAMES

private val SHARE_SPEAKER_ALTERNATION = SHARE_SPEAKER_NAMES.joinToString("|")

/** A marker that carries its own text, like `## You said: …`, `Claude responded: …` or `User: …`. */
private val SHARE_TURN_MARKER_REGEX = Regex(
    """^#{0,6}\s*(?:$SHARE_SPEAKER_ALTERNATION)\s*:\s*(.+)$""",
    RegexOption.IGNORE_CASE
)

/** A marker with nothing on its line: `## User`, `User:`, `پاسخ:`. */
private val SHARE_BARE_MARKER_REGEX = Regex(
    """^#{0,6}\s*(?:$SHARE_SPEAKER_ALTERNATION)\s*:?\s*$""",
    RegexOption.IGNORE_CASE
)

/** Any speaker line, with or without the turn text on it. */
private fun isShareMarkerLine(line: String): Boolean =
    SHARE_TURN_MARKER_REGEX.matches(line) || SHARE_BARE_MARKER_REGEX.matches(line)

/**
 * Which role a bare marker line names, or `null` when it is not a speaker line. The comparison is
 * exact on the trimmed name (never on the regex), so `Claude` can only ever be MODEL-side.
 */
private fun shareSpeakerRoleOf(line: String): ShareSpeakerRole? {
    val name = line.trim().trimStart('#').trim().trimEnd(':', ' ').trim()
    if (name.isEmpty()) return null
    return when {
        SHARE_USER_SPEAKER_NAMES.any { it.equals(name, ignoreCase = true) } -> ShareSpeakerRole.USER
        SHARE_MODEL_SPEAKER_NAMES.any { it.equals(name, ignoreCase = true) } -> ShareSpeakerRole.MODEL
        else -> null
    }
}

/** Chrome lines of the rendered share page, with or without a value after them. */
private val SHARE_CHROME_PREFIXES = listOf(
    "title:",
    "url source:",
    "markdown content",
    "shared by",
    "this is a copy of a chat",
    "content may include"
)

/**
 * A bare date ROW of the share page (`Sep 19`, `Nov 3`, `Sep 19, 2024`). The day number is required
 * and a year only counts when a comma introduces it, so prose like `May 2024 was the release.` or a
 * plain `Jul 1998` is not mistaken for the date stamp of a turn.
 */
private val SHARE_DATE_LINE_REGEX = Regex(
    """^(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|""" +
        """Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\s+\d{1,2}""" +
        """(?:st|nd|rd|th)?(?:,\s*\d{4})?\.?$""",
    RegexOption.IGNORE_CASE
)

/**
 * Only the tool traces measured on real share pages: `Searched the web`, `Searched the web …`,
 * `Read memory…`, `Thought for …`, `Viewed …`, `Fetched …`. The verbs that were guessed at but never
 * observed (`Ran`, `Used`, `Analyzed`, `Explored`, `Listed`, `Opened`, `Retrieved`, `Thinking`,
 * `Searches`, a bare `Read` or `Thought`) are gone, because they ate answer lines such as
 * `Ran in 3 seconds.` and `Used systems vary.`
 */
private val SHARE_TOOL_LINE_REGEX = Regex(
    """^(?:Searched(?:\s+the\s+web)?|Read\s+memory|Thought\s+for|Viewed|Fetched)\b.*$""",
    RegexOption.IGNORE_CASE
)

/** How many words a tool trace may have; above that it is prose and stays in the transcript. */
private const val SHARE_TOOL_LINE_MAX_WORDS = 6

/**
 * Share-page framework. The prefix rules apply only ahead of the first turn marker (see
 * [cleanShareMarkdown]); a bare date row is chrome wherever it sits, because a turn never begins with
 * one on its own line.
 */
private fun isShareFrameworkLine(line: String, beforeFirstMarker: Boolean): Boolean {
    if (SHARE_DATE_LINE_REGEX.matches(line)) return true
    if (!beforeFirstMarker) return false
    val lower = line.lowercase()
    return SHARE_CHROME_PREFIXES.any { lower.startsWith(it) } ||
        lower == "report" || lower == "report:"
}

/**
 * A tool trace is a short Latin row with no sentence-ending period: `Searched the web`. A real answer
 * line that merely starts with one of those verbs keeps its prose and is left alone.
 */
private fun isShareToolLine(line: String): Boolean {
    if (isShareMarkerLine(line)) return false
    val bare = line.trimStart('*', '+', '-', ' ')
    if (bare.endsWith(".")) return false
    if (!bare.all { it.code < 128 }) return false
    return SHARE_TOOL_LINE_REGEX.matches(bare) && bare.split(' ').count { it.isNotBlank() } <= SHARE_TOOL_LINE_MAX_WORDS
}

/**
 * گلیف‌های آیکون‌فونتِ لایۀ رندر را از یک سطرِ Markdown بیرون می‌کشد. اندازه‌گیری‌شده روی شبیۀ
 * LDPlayer: نوبتِ «پرسش کاربر» تنها یک نویسه داشت، `U+E08F` از خانۀ Private Use Area، که در رابط
 * حبابِ خالی می‌ساخت و شمارۀ پرسش‌ها را بزرگ‌تر از واقع نشان می‌داد؛ شش گلیفِ دیگر از همان خانه
 * (`U+E056` و `U+E027`) در پاسخ‌ها پاشیده بود و ایزولاتورهای جهتِ `U+FFF9..U+FFFB` هم از رندرِ
 * HTML می‌آمدند. بازه‌ها روی code point سنجیده می‌شوند و regex ساخته نمی‌شود.
 */
private fun stripIconGlyphs(line: String): String {
    return line.filterNot { it.code in 0xE000..0xF8FF || it.code in 0xFFF9..0xFFFB }
}
