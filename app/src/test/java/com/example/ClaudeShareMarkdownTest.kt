package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.domain.parser.READER_TIER_MAX_REQUESTS
import com.example.domain.parser.ReaderRequestBudget
import com.example.domain.parser.TranscriptIngestionEngine
import com.example.domain.parser.cleanShareMarkdown
import com.example.domain.parser.looksLikeConversation
import com.example.domain.parser.shouldRetryWithoutKey
import com.example.domain.parser.shouldSendKeylessRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for the Claude share-page fix. A real share link produced a fake success (one message of
 * Tailwind class soup, zero user turns) while the rendered markdown of the same link holds a real
 * four-turn conversation. These tests drive the fixture through `cleanShareMarkdown` and then
 * through the parser, and assert the conversation survives untouched.
 *
 * The fixture mirrors the measured structure of a rendered share page: chrome lines, the
 * "Shared by …" banner, `Report`, `## You said: <text on the same line>` markers whose first
 * sentence is repeated on the next line, bare date lines, and bare tool lines.
 */
class ClaudeShareMarkdownTest {

    private val rawMarkdown: String =
        requireNotNull(javaClass.getResourceAsStream(FIXTURE)) { "fixture $FIXTURE missing from the test classpath" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun cleanedClaudeShareMarkdownKeepsFourTurnsAndTheirOrder() {
        val cleaned = cleanShareMarkdown(rawMarkdown)
        val messages = TranscriptIngestionEngine
            .parseTranscript(cleaned, PlatformPreset.CLAUDE)
            .messages

        assertEquals("turn count", TURN_COUNT, messages.size)

        // Role ORDER is asserted explicitly so a parser that scrambles the turns fails.
        assertEquals(
            "roles in order",
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            messages.map { it.role }
        )

        assertEquals("first user turn", FIRST_USER_TURN, messages[0].content.trim())
        assertEquals("second user turn", SECOND_USER_TURN, messages[2].content.trim())
        assertEquals(
            "user turns, in order, without the duplicated first line",
            listOf(FIRST_USER_TURN, SECOND_USER_TURN),
            messages.filter { it.role == MessageRole.USER }.map { it.content.trim() }
        )

        val firstAssistantTurn = messages[1].content.trim()
        assertTrue(
            "first assistant turn must begin with the sentence carried on its `##` marker line, was: $firstAssistantTurn",
            firstAssistantTurn.startsWith(FIRST_ASSISTANT_OPENING)
        )
        assertTrue(
            "first assistant turn must keep the real bullet text of the answer, was: $firstAssistantTurn",
            firstAssistantTurn.contains(GAM_FIRST_STEP)
        )

        assertNoTurnContains(messages, TURN_FORBIDDEN_FRAGMENTS)
    }

    @Test
    fun shareMarkdownCleanerRemovesChromeAndDuplicatedFirstLine() {
        val cleaned = cleanShareMarkdown(rawMarkdown)

        // Non-vacuous duplicate check: the raw fixture really repeats the first question twice
        // (once on the `## You said:` line, once as the repeated body line).
        assertEquals("duplicated question in the raw fixture", 2, countOccurrences(rawMarkdown, FIRST_USER_TURN))
        assertEquals("duplicated question after cleaning", 1, countOccurrences(cleaned, FIRST_USER_TURN))

        for (fragment in CHROME_FRAGMENTS) {
            assertFalse("cleaner must drop the chrome/tool/date fragment \"$fragment\"", cleaned.contains(fragment))
        }

        // Removing chrome may not remove the conversation or the markers the parser reads.
        assertTrue("first question must survive cleaning", cleaned.contains(FIRST_USER_TURN))
        assertTrue("second question must survive cleaning", cleaned.contains(SECOND_USER_TURN))
        assertTrue("answer body must survive cleaning", cleaned.contains(GAM_FIRST_STEP))
        assertTrue("user marker must survive cleaning", cleaned.contains("## You said:"))
        assertTrue("assistant marker must survive cleaning", cleaned.contains("## Claude responded:"))
    }

    @Test
    fun plainUserAssistantTextIsStillParsedTheSameAfterTheCleaner() {
        val plain = "User: $PLAIN_USER_TURN\n\nAssistant: $PLAIN_ASSISTANT_TURN"
        val cleaned = cleanShareMarkdown(plain)

        // Nothing was taken away from healthy input; only blank-line padding may differ.
        assertEquals("every content line of healthy input survives", nonBlankLines(plain), nonBlankLines(cleaned))

        val session = TranscriptIngestionEngine.parseTranscript(cleaned, PlatformPreset.CLAUDE)
        assertEquals("two turns", 2, session.messages.size)
        assertEquals(
            "roles in order",
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            session.messages.map { it.role }
        )
        assertEquals("plain user turn", PLAIN_USER_TURN, session.messages[0].content.trim())
        assertEquals("plain assistant turn", PLAIN_ASSISTANT_TURN, session.messages[1].content.trim())

        // The cleaner must be behaviour-preserving: same parse with or without it.
        val unparsed = TranscriptIngestionEngine.parseTranscript(plain, PlatformPreset.CLAUDE)
        assertEquals(unparsed.messages.map { it.role }, session.messages.map { it.role })
        assertEquals(
            unparsed.messages.map { it.content.trim() },
            session.messages.map { it.content.trim() }
        )
    }

    /**
     * The tier-4/tier-5 gate. `createFallbackSession` invents one user message and one assistant
     * message out of any non-blank text, so a session-shaped gate there is always true and 481
     * characters of scraped Tailwind class names still reach `Result.success`.
     * `looksLikeConversation` is the non-tautological gate: chrome has no turn marker, so it must be
     * rejected with `assertFalse`, and only marker-bearing markdown is accepted.
     */
    @Test
    fun chromeOnlyTextIsNotACludgedAsConversation() {
        assertFalse(
            "Tailwind class soup scraped from the share shell is chrome, not a conversation",
            looksLikeConversation(TAILWIND_CHROME_SAMPLE)
        )
        assertFalse(
            "the multi-line legal footer of a share page is chrome, not a conversation",
            looksLikeConversation(LEGAL_FOOTER_SAMPLE)
        )
        assertFalse(
            "empty text is never a conversation",
            looksLikeConversation("")
        )

        assertTrue(
            "the scraped share markdown does carry `## You said:` and `## Claude responded:` turn markers",
            looksLikeConversation(rawMarkdown)
        )
        assertTrue(
            "marker-bearing markdown built from inline strings is a conversation",
            looksLikeConversation(MARKED_CLAUDE_SAMPLE)
        )
    }

    /**
     * The cleaner swallowed real answer prose: `isShareToolLine` ate any Latin row of six words or
     * fewer whose first verb appeared in the speculative list (`Ran`, `Used`, `Read` ...), and
     * `isShareFrameworkLine` dropped every `title:` line wherever it sat in the document, including
     * inside an answer body. Each of the four lines is asserted on its own, so re-adding one
     * speculative verb to the tool regex cannot pass this test unnoticed.
     */
    @Test
    fun cleanerKeepsShortEnglishAnswerLines() {
        val cleaned = cleanShareMarkdown(SHORT_ENGLISH_ANSWER_RAW)

        assertTrue(
            "\"Read the file first.\" is answer prose and must survive. Cleaned was:\n$cleaned",
            cleaned.contains("Read the file first.")
        )
        assertTrue(
            "\"Ran in 3 seconds.\" is answer prose and must survive. Cleaned was:\n$cleaned",
            cleaned.contains("Ran in 3 seconds.")
        )
        assertTrue(
            "\"Used systems vary.\" is answer prose and must survive. Cleaned was:\n$cleaned",
            cleaned.contains("Used systems vary.")
        )
        assertTrue(
            "\"Title: My great post\" sits after a turn marker and must survive. Cleaned was:\n$cleaned",
            cleaned.contains("Title: My great post")
        )
    }

    /**
     * `SHARE_DATE_LINE_REGEX` made the day optional-ish (`\d{1,2}`) and the year comma-free, so prose
     * that merely opens with a month name read as a chrome date stamp. Only the bare `Sep 19` shape is
     * allowed to go.
     */
    @Test
    fun cleanerKeepsMonthYearProseLine() {
        val cleaned = cleanShareMarkdown(MONTH_YEAR_RAW)

        assertTrue(
            "\"May 2024 was the release.\" is prose, not a date row. Cleaned was:\n$cleaned",
            cleaned.contains("May 2024 was the release.")
        )
        assertTrue(
            "\"Jul 1998\" is a month-year mention in the answer, not a date row. Cleaned was:\n$cleaned",
            cleaned.contains("Jul 1998")
        )
        assertFalse(
            "\"Sep 19\" is the share-page chrome date row and must still be dropped. Cleaned was:\n$cleaned",
            cleaned.contains("Sep 19")
        )
    }

    /**
     * `parseTextTurns` never respected fenced code: any `# <Name>: <text>` line reopened a turn, so a
     * pasted snippet containing `# Model:` and `# Prompt:` comments split one answer into four turns.
     * Lines between ``` fences are body text and the turn count stays at two.
     */
    @Test
    fun fencedCodeBlockLinesDoNotOpenNewTurns() {
        val session = TranscriptIngestionEngine.parseTranscript(
            FENCED_MARKER_RAW,
            PlatformPreset.CLAUDE
        )

        assertEquals(
            "comment lines inside a fence must not open turns. Parsed as:\n" +
                session.messages.joinToString("\n") { "${it.role}: ${it.content}" },
            FENCED_TURN_COUNT,
            session.messages.size
        )
        assertEquals(
            "roles in order",
            listOf(MessageRole.USER, MessageRole.ASSISTANT),
            session.messages.map { it.role }
        )

        val assistantContent = session.messages[1].content
        assertTrue(
            "\"# Model: gpt-4\" must stay inside the assistant body. Was:\n$assistantContent",
            assistantContent.contains("# Model: gpt-4")
        )
        assertTrue(
            "\"# Prompt: hello\" must stay inside the assistant body. Was:\n$assistantContent",
            assistantContent.contains("# Prompt: hello")
        )

        // Non-vacuous: the very same lines with the fence removed do open four turns, so it is the
        // fence that protects them and not an over-tight marker list.
        val unfenced = TranscriptIngestionEngine.parseTranscript(
            FENCED_MARKER_LINES.filterNot { it.startsWith("```") }.joinToString("\n"),
            PlatformPreset.CLAUDE
        )
        assertEquals(
            "outside a fence the same marker-shaped comment lines still open four turns",
            TURN_COUNT_WITHOUT_FENCE,
            unfenced.messages.size
        )
    }

    /**
     * بند R-۱: بدنۀ واقعیِ پوستۀ صفحۀ اشتراک، همان بایت‌هایی که `stripHtmlToText` از صفحۀ Claude
     * بیرون آورده است. خطِ اولش **تنها** `Claude` است و دروازۀ قدیمی دقیقاً به همین دلیل آن را
     * «گفتگو» می‌شمرد. فیکسچر دست‌نخورده خوانده می‌شود (پاورقیِ حقوقیِ کره‌ایِ خودِ صفحۀ Anthropic
     * هم در آن است)، پس اگر این تست سبز شد یعنی دروازۀ جدید دیگر با نامِ تکیِ گوینده گول نمی‌خورد.
     */
    @Test
    fun realClaudeShellChromeIsNotTreatedAsConversation() {
        val fixturePath = "/claude_share_chrome.txt"
        val chrome = requireNotNull(javaClass.getResourceAsStream(fixturePath)) {
            "fixture $fixturePath missing from the test classpath"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

        // بدنۀ پوسته یک‌جا خوانده می‌شود؛ مرتب‌کردنِ آن به یک خط، این تست را قرمز می‌کند.
        val shellLines = chrome.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        assertTrue(
            "the fixture must carry the whole shell body, measured lines: ${shellLines.size}",
            shellLines.size >= MIN_REAL_CHROME_LINES
        )
        assertEquals(
            "the shell really opens with the bare model name, which is the whole trap",
            BARE_MODEL_NAME,
            shellLines.first()
        )

        assertFalse(
            "the real Claude share-page shell is chrome, never an invented conversation",
            looksLikeConversation(chrome)
        )
    }

    /**
     * بند P-۲: یک خطِ لختِ نامِ گوینده دیگر کافی نیست. پذیرش فقط با «نشانه‌ای که متنِ نوبت را هم
     * حمل می‌کند» یا «دو خطِ نشانه‌دار از دو نقشِ متفاوت» رخ می‌دهد. چون دو بندِ قرارداد دربارهٔ زوجِ
     * لختِ کاربر+مدل با هم نمی‌خوانند، آن ورودی اینجا ادعا نشده است (نه راست، نه دروغ).
     */
    @Test
    fun bareSpeakerLinesAloneAreNotAConversation() {
        for (bareSpeakerLine in BARE_SPEAKER_LINES) {
            assertFalse(
                "a lone bare speaker name \"$bareSpeakerLine\" is chrome, not a conversation",
                looksLikeConversation(bareSpeakerLine)
            )
        }

        // `Claude\nUser` آزموده نشد چون P-۲ آن را زوجِ کاربر+مدل می‌داند؛ دو خطِ هم‌نقش بی‌ابهام‌اند.
        assertFalse(
            "two bare model-side markers never form the user+model pair the contract asks for",
            looksLikeConversation("$BARE_MODEL_NAME\nAssistant")
        )

        assertTrue(
            "a `Claude responded:` marker that carries its own turn text is a conversation",
            looksLikeConversation("Claude responded: این پاسخ است")
        )
        assertTrue(
            "a bare model line beside a user marker that carries text is a conversation",
            looksLikeConversation("$BARE_MODEL_NAME\nUser: چرا؟")
        )
        assertTrue(
            "a model marker with its own text is a conversation",
            looksLikeConversation("$BARE_MODEL_NAME: سلام")
        )
        assertTrue(
            "a `User:` + `Assistant:` pair with text is a conversation",
            looksLikeConversation("User: الف\n\nAssistant: ب")
        )
    }

    /**
     * محکم‌کردنِ دروازه نباید مسیرِ سالم را بشکند: Markdownِ درستِ صفحۀ اشتراک پس از پاک‌سازی هنوز
     * باید دقیقاً چهار نوبت با همان دو پرسشِ ثبت‌شده بدهد.
     */
    @Test
    fun theHealthyClaudeSharePathStillYieldsFourTurns() {
        val messages = TranscriptIngestionEngine
            .parseTranscript(cleanShareMarkdown(rawMarkdown), PlatformPreset.CLAUDE)
            .messages

        assertEquals("turn count", TURN_COUNT, messages.size)
        assertEquals(
            "roles in order",
            listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT),
            messages.map { it.role }
        )
        assertEquals("first user turn", FIRST_USER_TURN, messages[0].content.trim())
        assertEquals("second user turn", SECOND_USER_TURN, messages[2].content.trim())
    }

    /**
     * کارِ ۱: آیکون‌فونتِ لایۀ رندر در Markdownِ موتور خواندن به‌صورت نویسه‌های Private Use Area
     * می‌نشیند و نباید در متنِ نوبت بماند. ورودی همان شکلِ اندازه‌گیری‌شده روی شبیۀ LDPlayer است:
     * نوبتِ کاربری که متنش فقط `U+E08F` بود، پاسخی که `U+E056` و `U+E027` در میانش پاشیده بود، و
     * یک سطر که تماماً گلیف است. نشانه‌ها و متنِ پاسخ باید بمانند.
     */
    @Test
    fun iconGlyphsFromTheReaderLayerAreStripped() {
        val cleaned = cleanShareMarkdown(ICON_GLYPH_RAW)

        for (glyph in READER_ICON_GLYPHS) {
            assertFalse(
                "reader icon glyph U+${glyph.code.toString(16).uppercase()} must not survive the cleaner. Cleaned was:\n$cleaned",
                cleaned.contains(glyph)
            )
        }

        // پاک‌سازیِ گلیف نباید نشانه‌ها یا متنِ پاسخ را با خودش ببرد.
        assertTrue(
            "answer text must survive glyph stripping. Cleaned was:\n$cleaned",
            cleaned.contains(GAM_FIRST_STEP)
        )
        assertTrue("user marker must survive cleaning", cleaned.contains("## You said:"))
        assertTrue("assistant marker must survive cleaning", cleaned.contains("## Claude responded:"))

        // سطرِ نشانه لخت می‌ماند و سطرِ تماماً گلیف با سطرِ خالی یکی می‌شود، پس دو سطرِ پُر می‌ماند.
        assertTrue(
            "the user marker line survives bare, with nothing after the colon. Cleaned was:\n$cleaned",
            cleaned.lines().any { it.trim() == "## You said:" }
        )
        assertEquals("blank-collapsed line count", 2, nonBlankLines(cleaned).size)
    }

    /**
     * کارِ ۲: نوبتی که پس از پاک‌سازیِ کارِ ۱ بی‌متن می‌ماند نباید پیام بسازد. دروازۀ این کار
     * از قبل در `flushCurrent` بود و سنجشِ `content.isNotEmpty()` پیش از افزودنِ پیام انجام می‌شود
     * (TranscriptIngestionEngine.kt:84)، پس در کدِ تولیدی چیزی تغییر نکرد. این تست همان رفتار را
     * میخکوب می‌کند: اگر `stripIconGlyphs` بی‌اثر شود، نوبتِ کاربر با متنِ گلیف ساخته می‌شود و شمارۀ
     * نوبت‌ها از ۱ به ۲ می‌رود، یعنی همین تست هم قرمز می‌شود.
     */
    @Test
    fun blankTurnMarkerDoesNotCreateAMessage() {
        val messages = TranscriptIngestionEngine
            .parseTranscript(cleanShareMarkdown(ICON_GLYPH_RAW), PlatformPreset.CLAUDE)
            .messages

        assertTrue(
            "no parsed turn may be blank: " + messages.map { it.content },
            messages.none { it.content.isBlank() }
        )
        assertEquals(
            "the glyph-only user marker must not become an empty question bubble. Parsed as:\n" +
                messages.joinToString("\n") { "${it.role}: ${it.content}" },
            1,
            messages.size
        )
        assertEquals(
            "roles in order",
            listOf(MessageRole.ASSISTANT),
            messages.map { it.role }
        )
        assertEquals("the answer turn keeps its text", GAM_FIRST_STEP, messages[0].content.trim())
    }

    /**
     * کارِ U-۳ (B-۱۱): `tryFetchJinaReader` شبکه را لمس می‌کند و روی JVM آزمون‌پذیر نیست، پس تنها
     * تصمیمِ «آیا یک تلاشِ دوباره بدونِ `Authorization` مجاز است؟» از آن جدا شده
     * (`shouldRetryWithoutKey`). تنها `401`ی که با کلیدِ فرستاده‌شده برمی‌گردد این حق را دارد؛ کلیدِ
     * کهنه نباید راهی را ببندد که بی‌کلید کار می‌کند.
     */
    @Test
    fun rejectedKeyIsTheOnlyCaseThatEarnsAKeylessRetry() {
        assertTrue(
            "a 401 returned while a key WAS sent must earn exactly one keyless retry",
            shouldRetryWithoutKey(httpCode = 401, hadKey = true)
        )
    }

    /**
     * هر پاسخِ دیگری که «کلید» مقصرش نیست، آخرین حرفِ لایۀ ۲ است: `401`ِ بی‌کلید یعنی تنظیمات
     * خالی است (نه کلیدِ غلط)، `2xx` موفقیت است، و `429` یا `5xx` حال‌وخودِ خودِ موتور خواندن، نه
     * ردِّ کلید. تلاشِ بی‌کلید هم سومین درخواست نمی‌سازد.
     */
    @Test
    fun keylessAndNonAuthReaderAnswersGetNoRetry() {
        assertFalse(
            "a 401 with no key sent is the missing-setting case, not a refused key",
            shouldRetryWithoutKey(httpCode = 401, hadKey = false)
        )
        assertFalse(
            "a successful fetch must not send a second request",
            shouldRetryWithoutKey(httpCode = 200, hadKey = true)
        )
        assertFalse(
            "a rate limit is the reader engine talking, not the key",
            shouldRetryWithoutKey(httpCode = 429, hadKey = true)
        )
        assertFalse(
            "a server error is the reader engine failing, not the key",
            shouldRetryWithoutKey(httpCode = 503, hadKey = true)
        )
        assertFalse(
            "a call that never got a status has nothing to retry on",
            shouldRetryWithoutKey(httpCode = 0, hadKey = true)
        )
    }

    /**
     * سقفِ لایه دو درخواست است، حلقه نیست: خودِ تلاشِ بی‌کلید هرگز دوباره تکرار نمی‌شود، چون برای
     * آن `hadKey = false` است. اگر این بند قرمز شد، یعنی درخواستِ سوم سرِ همان کاربر می‌رود.
     */
    @Test
    fun theKeylessAttemptItselfIsNeverRetriedAgain() {
        assertFalse(
            "the keyless retry is the second and last request of the tier",
            shouldRetryWithoutKey(httpCode = 401, hadKey = false)
        )
    }

    /**
     * The counter every real network call of a tier spends from. The reading tier could reach FOUR
     * requests on one import before it existed (`executeFinalResponse` re-sends after a `1xx`, and
     * the keyless retry did the same again), so the default budget of two is the whole point. A
     * refused `spend()` must also leave `used` alone, otherwise a refusal would look like progress.
     */
    @Test
    fun readerTierBudgetIsExhaustedAfterTwoRequests() {
        val budget = ReaderRequestBudget()

        assertEquals("the tier ceiling is two requests", 2, READER_TIER_MAX_REQUESTS)
        assertEquals("a fresh budget has spent nothing", 0, budget.used)
        assertTrue("request 1 of 2 is allowed", budget.spend())
        assertFalse("one request is not the ceiling yet", budget.exhausted)
        assertTrue("request 2 of 2 is allowed", budget.spend())
        assertTrue("the budget is exhausted after two requests", budget.exhausted)

        assertFalse("request 3 must be refused", budget.spend())
        assertFalse("a 4th request must be refused too", budget.spend())
        assertEquals("refused spends must not raise the count", 2, budget.used)
    }

    /**
     * The ceiling is the constructor's argument, not a hardcoded two: a tier that may only send
     * once has to be expressible, and it must refuse on the very next `spend()`.
     */
    @Test
    fun aBudgetOfOneAllowsExactlyOneRequest() {
        val budget = ReaderRequestBudget(max = 1)

        assertTrue("the only request of this tier is allowed", budget.spend())
        assertTrue("a budget of one is exhausted after that one request", budget.exhausted)
        assertFalse("a second request must be refused", budget.spend())
        assertEquals("the refused spend must leave the count at one", 1, budget.used)
    }

    /**
     * The important one, because it measures the function `tryFetchJinaReader` actually calls, not
     * two helpers scored separately. A refused key earns ONE keyless retry, but only while the tier
     * still owns a request: a keyed attempt that already spent two (`103` then `401`) must not be
     * followed by a third send. The decision must not spend either — the send that follows spends
     * for itself, and double-spending here would eat the keyless attempt's own slot.
     */
    @Test
    fun keylessRetryIsRefusedOnceTheTierBudgetIsSpent() {
        val spentBudget = ReaderRequestBudget()
        assertTrue("spending the keyed attempt's two requests", spentBudget.spend() && spentBudget.spend())

        assertTrue(
            "the 401-with-key alone would earn a retry",
            shouldRetryWithoutKey(httpCode = 401, hadKey = true)
        )
        assertFalse(
            "with the tier budget already spent there is no request left for the keyless retry",
            shouldSendKeylessRetry(keyedHttpCode = 401, hadKey = true, budget = spentBudget)
        )

        val freshBudget = ReaderRequestBudget()
        assertTrue(
            "while the tier still owns a request the keyless retry is allowed",
            shouldSendKeylessRetry(keyedHttpCode = 401, hadKey = true, budget = freshBudget)
        )
        assertEquals(
            "deciding is not sending: the retry's own slot is still there for its send to spend",
            0,
            freshBudget.used
        )
        assertFalse(
            "a 401 with no key sent is the missing-setting case and never earns a retry",
            shouldSendKeylessRetry(keyedHttpCode = 401, hadKey = false, budget = ReaderRequestBudget())
        )
    }

    private fun assertNoTurnContains(
        messages: List<ChatMessage>,
        fragments: List<String>
    ) {
        for ((index, message) in messages.withIndex()) {
            for (fragment in fragments) {
                assertFalse(
                    "turn $index (${message.role}) must not contain the chrome fragment \"$fragment\": ${message.content}",
                    message.content.contains(fragment)
                )
            }
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun nonBlankLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private companion object {
        const val FIXTURE = "/claude_share_markdown.md"
        const val TURN_COUNT = 4

        const val FIRST_USER_TURN = "موتور پالایش را چطور بنویسم"
        const val SECOND_USER_TURN = "حالا ابعاد تست را بگو"
        const val FIRST_ASSISTANT_OPENING = "اول باید دادهٔ ورودی را نرمال کنم."
        const val GAM_FIRST_STEP = "گام اول"

        const val PLAIN_USER_TURN = "چطور یک فایل را بخوانیم"
        const val PLAIN_ASSISTANT_TURN = "ابتدا فایل را انتخاب کنید"

        // The seven fragments the contract forbids inside any parsed turn.
        val TURN_FORBIDDEN_FRAGMENTS = listOf(
            "Searched the web",
            "Report",
            "Shared by",
            "Markdown Content",
            "URL Source",
            "Sep 19",
            "Read memory"
        )

        // The full chrome set the cleaner must strip from the markdown itself.
        val CHROME_FRAGMENTS = listOf(
            "Title: Claude",
            "URL Source:",
            "Markdown Content:",
            "Shared by",
            "This is a copy of a chat",
            "Content may include",
            "Report",
            "Sep 19",
            "Nov 3",
            "Searched the web",
            "Read memory"
        )

        // Tailwind class soup scraped off the Claude share shell, in the shape measured in
        // docs/reports/raw/ui-claude-01.xml. That dump has Persian digits inside the attribute values,
        // so the ASCII digits of the live page are restored here. One scraped line, wrapped in code.
        val TAILWIND_CHROME_SAMPLE =
            "svg]:block [&_svg]:scale-[1.333] max-sm:-ms-[0.84em]\" data-layout-home-classes=\"me-[0.45em] " +
                "inline-block size-[0.72em] supports-[height:1cap]:size-[1cap] [&>svg]:block " +
                "[&_svg]:scale-[1.4] max-sm:-ms-[0.84em]\""

        // The legal/boilerplate footer of a share page: several lines of Latin prose, zero speakers.
        val LEGAL_FOOTER_SAMPLE = listOf(
            "Shared by someone.",
            "This is a copy of a chat. Content may include unverified or unsafe content that does not represent the views of Anthropic.",
            "Anthropic PBC | Business registration number 86-196-9045 (Delaware, United States)",
            "Report    Terms of Service    Privacy Policy"
        ).joinToString("\n")

        val MARKED_CLAUDE_SAMPLE = listOf(
            "## You said: How do I write the refine engine?",
            "",
            "## Claude responded: First normalize the input data."
        ).joinToString("\n")

        // Short real answer lines, one per kind of over-eager cleaner rule, placed after a turn marker.
        val SHORT_ENGLISH_ANSWER_RAW = listOf(
            "## You said: What did the import run do?",
            "Read the file first.",
            "Ran in 3 seconds.",
            "Used systems vary.",
            "",
            "## Claude responded: The draft is ready.",
            "Title: My great post"
        ).joinToString("\n")

        // Month-name prose that the date rule mistook for a chrome date row.
        val MONTH_YEAR_RAW = listOf(
            "## Claude responded: The timeline is short.",
            "May 2024 was the release.",
            "Jul 1998",
            "Sep 19"
        ).joinToString("\n")

        // گلیف‌های اندازه‌گیری‌شده روی شبیۀ LDPlayer: `U+E08F` در نشانۀ کاربر، `U+E056` و `U+E027`
        // در پاسخ‌ها و `U+FFF9` از ایزولاتورهای جهتِ رندرِ HTML.
        val READER_ICON_GLYPHS = listOf('\uE08F', '\uE056', '\uE027', '\uFFF9')

        // نوبتِ کاربری که متنش فقط یک آیکون است و پاسخی که گلیف‌ها در میانش نشسته‌اند؛ سطرِ آخر
        // تماماً گلیف است و باید به سطرِ خالی تبدیل شود.
        val ICON_GLYPH_RAW = listOf(
            "## You said: \uE08F",
            "",
            "## Claude responded: \uE056$GAM_FIRST_STEP\uE027",
            "\uE08F\uFFF9"
        ).joinToString("\n")

        // A pasted fenced block whose comment lines are marker-shaped for the transcript parser.
        val FENCED_MARKER_LINES = listOf(
            "User: What does this snippet declare?",
            "Assistant: It declares both inside the block:",
            "```",
            "# Model: gpt-4",
            "# Prompt: hello",
            "```"
        )

        val FENCED_MARKER_RAW = FENCED_MARKER_LINES.joinToString("\n")

        const val FENCED_TURN_COUNT = 2
        const val TURN_COUNT_WITHOUT_FENCE = 4

        // شش خطِ پر در بدنۀ واقعی اندازه‌گیری شده؛ نسخۀ یک‌خطیِ مرتب‌شده این آستانه را نقض می‌کند.
        const val MIN_REAL_CHROME_LINES = 4

        // نامِ لختِ مدل که پوستۀ صفحۀ اشتراک با آن باز می‌شود، و نام‌های لختِ بند P-۲.
        const val BARE_MODEL_NAME = "Claude"
        val BARE_SPEAKER_LINES = listOf("Claude", "User", "Assistant", "پاسخ")
    }
}
