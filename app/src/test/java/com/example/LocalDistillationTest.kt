package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.CodeSnippet
import com.example.data.model.DistillationType
import com.example.data.model.MessageRole
import com.example.data.remote.gemini.GeminiClient
import com.example.data.remote.gemini.buildLocalExecutiveSummary
import com.example.data.remote.gemini.buildLocalRepairDoc
import com.example.data.remote.gemini.buildLocalTechnicalDoc
import com.example.data.remote.gemini.findLocalVersionTokens
import com.example.data.remote.gemini.measureLocalConversation
import com.example.data.remote.gemini.servedModelOf
import com.example.domain.bidi.BidiEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline (no-API-key) executive summary used to print four bullets that were the same for every
 * conversation anybody imported. These tests pin the opposite claim: the numbers, the terms and the
 * listed points of the report have to come out of the text of THIS session. Every assertion about a
 * count is written with latin digits; the Persian digits only appear inside strings the builder itself
 * produced through `BidiEngine.toPersianDigits`.
 *
 * The same rule is pinned for the two offline documents that were still invented rather than measured
 * — the technical document's release number and its security section, and the repair document's
 * promise of perfect preservation — and for the engine name each offline branch reports as the one
 * that answered, so a no-key run cannot leave a stale cloud model on the screen.
 *
 * Two more claims of the same class are pinned here: a count spoken to the reader of an offline document
 * is a count of the whole text and never a count of the capped list that happened to get printed, and
 * every number an offline document says to a Persian reader is written in Persian digits.
 */
class LocalDistillationTest {

    /**
     * `apiKeyOverrideForTests` outranks `BuildConfig` in `getEffectiveApiKey()`, so a throw before its
     * reset would silently force every later test class of this JVM down the offline branch — and a
     * planted `lastUsedModel` would be read by the next class as though it had answered there. Both
     * seams go back to their production defaults after every test of this class, including one that
     * throws, which is why this lives here and not inside a `finally`.
     */
    @After
    fun clearTheGeminiClientTestSeams() {
        GeminiClient.apiKeyOverrideForTests = null
        GeminiClient.lastUsedModel = null
    }

    private val sameDate = "2026-10-10 09:30"

    /** Three turns: one question, two answers, two fenced blocks, two bullets and one numbered line. */
    private val styled: ChatSession = ChatSession(
        id = "local-summary-styled",
        title = "گفتگوی مشترک",
        messages = listOf(
            ChatMessage(
                id = "s1",
                role = MessageRole.USER,
                content = "چطور تایپوگرافی فارسی را در اپ درست کنیم؟"
            ),
            ChatMessage(
                id = "s2",
                role = MessageRole.ASSISTANT,
                content = "برای تایپوگرافی فارسی این سه بند لازم است.\n\n" +
                        "```kotlin\nval direction = \"RTL\"\n```\n\n" +
                        "- تایپوگرافی فارسی با نیم‌فاصله نوشته شود\n" +
                        "- بلوک کد در سند حفظ شود\n\n" +
                        "```zsh\ngrep text file\n```\n\n" +
                        "1) فایل استایل را بازبینی کن"
            ),
            ChatMessage(
                id = "s3",
                role = MessageRole.ASSISTANT,
                content = "نیم‌فاصله در تایپوگرافی فارسی مهم است."
            )
        )
    )

    /** Three turns: two questions, one answer, no code fence and no list line at all. */
    private val plain: ChatSession = ChatSession(
        id = "local-summary-plain",
        title = "گفتگوی مشترک",
        messages = listOf(
            ChatMessage(
                id = "p1",
                role = MessageRole.USER,
                content = "چه زمانی سیب را از درخت برداشت کنیم؟"
            ),
            ChatMessage(
                id = "p2",
                role = MessageRole.ASSISTANT,
                content = "فقط سیب رسیده را برداشت کن. سیب نرسیده روی درخت بماند."
            ),
            ChatMessage(
                id = "p3",
                role = MessageRole.USER,
                content = "ممنون، پس فردا می روم."
            )
        )
    )

    /**
     * One question and one answer carrying eight bullet lines. The question holds neither a digit nor
     * a bullet on purpose, so the two counters can only be reading the answer, and every list line in
     * the fixture is a bullet so `numberedLineCount` has to stay at zero. Deliberately latin-only: the
     * numbers are the whole point of this fixture, and Persian prose here would only add ways for it
     * to stop meaning what it says.
     */
    private val listHeavy: ChatSession = ChatSession(
        id = "local-summary-list-heavy",
        title = "Long list answer",
        messages = listOf(
            ChatMessage(
                id = "l1",
                role = MessageRole.USER,
                content = "List every checkpoint of the release plan."
            ),
            ChatMessage(
                id = "l2",
                role = MessageRole.ASSISTANT,
                content = "- first checkpoint\n" +
                        "- second checkpoint\n" +
                        "- third checkpoint\n" +
                        "- fourth checkpoint\n" +
                        "- fifth checkpoint\n" +
                        "- sixth checkpoint\n" +
                        "- seventh checkpoint\n" +
                        "- eighth checkpoint"
            )
        )
    )

    @Test
    fun offlineSummaryCountsTheTurnsItActuallyRead() {
        val styledMetrics = measureLocalConversation(styled)
        assertEquals(3, styledMetrics.turnCount)
        assertEquals(1, styledMetrics.userTurns)
        assertEquals(2, styledMetrics.assistantTurns)
        assertEquals(0, styledMetrics.systemTurns)

        val plainMetrics = measureLocalConversation(plain)
        assertEquals(3, plainMetrics.turnCount)
        assertEquals(2, plainMetrics.userTurns)
        assertEquals(1, plainMetrics.assistantTurns)

        // The same counts are what the reader sees, in Persian digits, one line at a time.
        val styledReport = buildLocalExecutiveSummary(styled, sameDate)
        assertTrue(styledReport.contains("کل پیام‌های رد و بدل شده: " + BidiEngine.toPersianDigits("3")))
        assertTrue(styledReport.contains("پرسش‌های کاربر: " + BidiEngine.toPersianDigits("1")))
        assertTrue(styledReport.contains("پاسخ‌های دستیار: " + BidiEngine.toPersianDigits("2")))

        val plainReport = buildLocalExecutiveSummary(plain, sameDate)
        assertTrue(plainReport.contains("پرسش‌های کاربر: " + BidiEngine.toPersianDigits("2")))
        assertTrue(plainReport.contains("پاسخ‌های دستیار: " + BidiEngine.toPersianDigits("1")))
    }

    @Test
    fun offlineSummaryCountsCodeBlocksFromTheFencedText() {
        val styledMetrics = measureLocalConversation(styled)
        assertEquals(2, styledMetrics.codeBlockCount)
        assertEquals(listOf("kotlin", "zsh"), styledMetrics.codeLanguages)

        val plainMetrics = measureLocalConversation(plain)
        assertEquals(0, plainMetrics.codeBlockCount)
        assertTrue(plainMetrics.codeLanguages.isEmpty())

        val styledReport = buildLocalExecutiveSummary(styled, sameDate)
        assertTrue(styledReport.contains("بلوک‌های کد استخراج‌شده: " + BidiEngine.toPersianDigits("2") + " قطعه"))
        assertTrue(styledReport.contains("kotlin"))
        assertTrue(styledReport.contains("zsh"))

        // The "no code block" sentence may only be printed for the session that really has none.
        val plainReport = buildLocalExecutiveSummary(plain, sameDate)
        assertTrue(plainReport.contains("هیچ بلوک کد استانداردی"))
        assertFalse(styledReport.contains("هیچ بلوک کد استانداردی"))
        assertFalse(plainReport.contains("بلوک‌های کد استخراج‌شده"))
    }

    @Test
    fun offlineSummaryRanksTermsFromThatSpecificConversation() {
        val styledMetrics = measureLocalConversation(styled)
        assertTrue(
            "term ranking was ${styledMetrics.topTerms}",
            styledMetrics.topTerms.any { it.first == "تایپوگرافی" && it.second == 4 }
        )
        assertFalse(styledMetrics.topTerms.any { it.first == "سیب" })

        val plainMetrics = measureLocalConversation(plain)
        assertTrue(
            "term ranking was ${plainMetrics.topTerms}",
            plainMetrics.topTerms.any { it.first == "سیب" && it.second == 3 }
        )
        assertFalse(plainMetrics.topTerms.any { it.first == "تایپوگرافی" })

        // Vocabulary density is a ratio of the two measured word counts, never a fixed number.
        assertTrue(styledMetrics.distinctWords <= styledMetrics.totalWords)
        assertTrue(styledMetrics.vocabularyDensityPercent in 1..100)
        assertEquals(
            styledMetrics.distinctWords * 100 / styledMetrics.totalWords,
            styledMetrics.vocabularyDensityPercent
        )

        val styledReport = buildLocalExecutiveSummary(styled, sameDate)
        assertTrue(styledReport.contains("تایپوگرافی (" + BidiEngine.toPersianDigits("4") + ")"))
        val plainReport = buildLocalExecutiveSummary(plain, sameDate)
        assertTrue(plainReport.contains("سیب (" + BidiEngine.toPersianDigits("3") + ")"))
    }

    @Test
    fun offlineSummaryIsNotAFixedTemplateAcrossConversations() {
        val styledReport = buildLocalExecutiveSummary(styled, sameDate)
        val plainReport = buildLocalExecutiveSummary(plain, sameDate)

        // Same title, same date: whatever differs here can only come from the conversation itself.
        assertNotEquals(styledReport, plainReport)

        // The bullets the old engine wrote by hand must not survive anywhere.
        for (canned in listOf("تحلیل دقیق", "دوزبانه", "الگوهای استاندارد")) {
            assertFalse("the canned bullet «$canned» is back", styledReport.contains(canned))
            assertFalse("the canned bullet «$canned» is back", plainReport.contains(canned))
        }

        // What the report lists instead is the text this session really contains.
        assertTrue(styledReport.contains("تایپوگرافی فارسی با نیم‌فاصله نوشته شود"))
        assertTrue(styledReport.contains("فایل استایل را بازبینی کن"))
        assertTrue(plainReport.contains("سیب رسیده"))

        // And the absence of a list is said out loud rather than papered over with a template.
        assertTrue(plainReport.contains("پیدا نشد"))
        assertFalse(styledReport.contains("پیدا نشد"))

        // The list count line matches what was measured, per conversation.
        assertEquals(
            3,
            measureLocalConversation(styled).let { it.bulletLines.size + it.numberedLines.size }
        )
        assertEquals(0, measureLocalConversation(plain).let { it.bulletLines.size + it.numberedLines.size })
        assertTrue(
            styledReport.contains("خطوط شماره‌دار یا بولت‌شده در متن: " + BidiEngine.toPersianDigits("3"))
        )
        assertTrue(
            plainReport.contains("خطوط شماره‌دار یا بولت‌شده در متن: " + BidiEngine.toPersianDigits("0"))
        )
    }

    @Test
    fun servedModelVersionWinsOverTheRequestedAlias() {
        // Measured against the live API: a request for the preview alias was answered as the plain id.
        val answered = org.json.JSONObject("""{"modelVersion":"gemini-3.1-flash-lite"}""")
        assertEquals(
            "gemini-3.1-flash-lite",
            servedModelOf(answered, "gemini-3.1-flash-lite-preview")
        )

        // A body that does not name the engine keeps the requested id instead of inventing a third one.
        assertEquals("gemini-3.5-flash", servedModelOf(org.json.JSONObject("{}"), "gemini-3.5-flash"))
        assertEquals(
            "gemini-2.5-flash",
            servedModelOf(org.json.JSONObject("""{"modelVersion":""}"""), "gemini-2.5-flash")
        )
        assertEquals(
            "gemini-flash-latest",
            servedModelOf(org.json.JSONObject("""{"modelVersion":null}"""), "gemini-flash-latest")
        )
    }

    /**
     * Three turns that do say something about a release: one version written down, two lines about
     * privacy and speed, one line about neither. Same title shape as the sessions above, so what
     * differs in the document below can only come from this text.
     */
    private val operational: ChatSession = ChatSession(
        id = "local-doc-operational",
        title = "گفتگوی انتشار",
        messages = listOf(
            ChatMessage(
                id = "o1",
                role = MessageRole.USER,
                content = "اپ را با v2.3.1 منتشر کردیم."
            ),
            ChatMessage(
                id = "o2",
                role = MessageRole.ASSISTANT,
                content = "- حریم خصوصی کاربر با رمزنگاری TLS حفظ می‌شود\n" +
                        "- کارایی با یکدست‌سازی حافظه بهتر می‌شود"
            ),
            ChatMessage(
                id = "o3",
                role = MessageRole.ASSISTANT,
                content = "ممنون، پس فردا می روم."
            )
        )
    )

    /**
     * The Persian label of the list-line statistic, sliced out of the production source instead of
     * typed out here. This file already carries Persian, but Persian is only ever copied into it, and
     * `GeminiClient.kt` is the one place that label is defined: reading it back out of the file the
     * report is actually built from is what keeps the assertion tied to the printed string instead of
     * to a copy of it that could drift. It also fails loudly if the statistic ever stops reading the
     * two counters, because then there is nothing left to slice.
     */
    private fun listLineStatisticLabel(): String {
        val relative = "src/main/java/com/example/data/remote/gemini/GeminiClient.kt"
        var dir: File? = File(".").absoluteFile
        var source: File? = null
        while (dir != null && source == null) {
            val direct = File(dir, relative)
            val nested = File(dir, "app/$relative")
            if (direct.isFile) {
                source = direct
            } else if (nested.isFile) {
                source = nested
            }
            dir = dir.parentFile
        }
        val file: File = source
            ?: error("GeminiClient.kt was not found walking up from ${File(".").absolutePath}")
        val lines = file.readLines()
        val counterLine = lines.indexOfFirst { it.contains("bulletLineCount + metrics.numberedLineCount") }
        check(counterLine > 0) {
            "the list-line statistic no longer reads the two counters of ${file.path}"
        }
        val literal = lines[counterLine - 1]
        val open = literal.indexOf('"')
        val close = literal.lastIndexOf('"')
        check(open >= 0 && close > open) {
            "the list-line label is no longer a plain literal in ${file.path}"
        }
        return literal.substring(open + 1, close)
    }

    /**
     * The count the reader is told is a count of the WHOLE text, never a count of the capped list
     * that happened to get printed. Eight list lines go in, the points section still prints six, and
     * the statistics line has to say eight anyway. The `styled` fixture above cannot witness this: it
     * holds three list lines, which is under the cap, so counting the capped list gives the same
     * answer as counting the text and the two mistakes are indistinguishable.
     *
     * Deliberate break that reddens it: move `bulletLineCount++` and `numberedLineCount++` inside
     * `if (... size < LOCAL_POINT_LIMIT)` in `measureLocalConversation`, and the count becomes 6.
     * Gating only the `add` does NOT redden this test; it is the increment that has to be gated.
     */
    @Test
    fun listLineStatisticCountsTheWholeTextAndNotTheCappedPrint() {
        val metrics = measureLocalConversation(listHeavy)
        assertEquals(8, metrics.bulletLineCount)
        assertEquals(0, metrics.numberedLineCount)
        assertEquals(6, metrics.bulletLines.size)

        val label = listLineStatisticLabel()
        val report = buildLocalExecutiveSummary(listHeavy, sameDate)
        assertTrue(
            "the statistic should read all eight lines of the text, was:\n$report",
            report.contains(label + BidiEngine.toPersianDigits("8"))
        )
        assertFalse(
            "the statistic is reporting the capped print instead of the text",
            report.contains(label + BidiEngine.toPersianDigits("6"))
        )
    }

    /**
     * The technical document used to state a release number no conversation had ever mentioned. A
     * deliberate break that reddens this: put `| **نسخه:** 1.0.0` back into the header of
     * `buildLocalTechnicalDoc` (the value the assertion above rebuilds with `toPersianDigits`), and
     * all three of the first assertions go red. A second break that reddens the last half: make
     * `findLocalVersionTokens` return `emptyList()`, which proves the version line is content-driven
     * and not merely deleted.
     */
    @Test
    fun technicalDocNeverInventsAVersionTheSessionDidNotState() {
        val invented = BidiEngine.toPersianDigits("1.0.0")
        val doc = buildLocalTechnicalDoc(plain, sameDate)
        assertFalse("the invented version «$invented» is back", doc.contains(invented))
        assertFalse("the invented version 1.0.0 is back", doc.contains("1.0.0"))
        assertTrue(doc.contains("هیچ نسخه‌ای پیدا نشد"))

        // The session that does write a release down gets it printed, in Persian digits, nothing else.
        assertEquals(listOf(BidiEngine.toPersianDigits("2.3.1")), findLocalVersionTokens(operational))
        val opsDoc = buildLocalTechnicalDoc(operational, sameDate)
        assertTrue(opsDoc.contains(BidiEngine.toPersianDigits("2.3.1")))
        assertFalse(opsDoc.contains("هیچ نسخه‌ای پیدا نشد"))
    }

    /**
     * The security and performance section is measured or it is silent. Deliberate breaks that redden
     * this test: re-add `- رعایت اصول Clean Architecture ...` or the Coroutines bullet to the offline
     * branch, and the canned-string loop fails; replace the note lookup with `return emptyList()` and
     * the two quotes from `operational` go red.
     */
    @Test
    fun technicalDocSecuritySectionQuotesOnlyWhatTheTextSays() {
        val doc = buildLocalTechnicalDoc(plain, sameDate)
        for (canned in listOf("Clean Architecture", "Separation of Concerns", "Coroutines", "رعایت اصول")) {
            assertFalse("the fixed bullet «$canned» is back", doc.contains(canned))
        }
        assertTrue(doc.contains("هیچ نکتهٔ امنیتی یا کارایی پیدا نشد"))

        val opsDoc = buildLocalTechnicalDoc(operational, sameDate)
        assertTrue(opsDoc.contains("حریم خصوصی کاربر با رمزنگاری TLS حفظ می‌شود"))
        assertTrue(opsDoc.contains("کارایی با یکدست‌سازی حافظه بهتر می‌شود"))
        assertFalse("the empty-section line may not print when the text does speak of them",
            opsDoc.contains("پیدا نشد"))
    }

    /**
     * A no-key refine writes the text locally, so the record of what answered has to say so too. The
     * stale name is planted here by the test itself: the deliberate break is deleting
     * `lastUsedModel = OFFLINE_MODEL_LABEL` from the blank-key branch of `chatRefine`, which leaves
     * the planted cloud name in place and reddens the first assertion.
     */
    @Test
    fun offlineRefineOverwritesTheModelNameTheLastCloudAnswerLeft() {
        GeminiClient.lastUsedModel = "gemini-3.5-flash"
        GeminiClient.apiKeyOverrideForTests = ""
        val result = try {
            runBlocking { GeminiClient.chatRefine("متن نمونه", "خوبش کن") }
        } finally {
            GeminiClient.apiKeyOverrideForTests = null
        }
        val labelAfterOfflineRefine = GeminiClient.lastUsedModel
        GeminiClient.lastUsedModel = null

        assertEquals(GeminiClient.OFFLINE_MODEL_LABEL, labelAfterOfflineRefine)
        val preview = result.getOrThrow()
        assertTrue(preview.contains("حالت محلی"))
        assertTrue(preview.contains("متن نمونه"))
        // The local engine only evens out typography, so it may not call its output an improved text.
        assertFalse(preview.contains("بهبودیافته"))
        assertTrue(preview.contains("متن بدون بازنویسی برگردانده شد"))
    }

    /**
     * The same shape as above for the other no-key entry point, whose assignment was already there:
     * this pins it, so deleting `lastUsedModel = OFFLINE_MODEL_LABEL` from the blank-key branch of
     * `distillSession` reddens the assertion rather than silently passing.
     */
    @Test
    fun offlineDistillationOverwritesTheModelNameTheLastCloudAnswerLeft() {
        GeminiClient.lastUsedModel = "gemini-2.5-flash"
        GeminiClient.apiKeyOverrideForTests = ""
        val result = try {
            runBlocking { GeminiClient.distillSession(styled, DistillationType.EXECUTIVE_SUMMARY) }
        } finally {
            GeminiClient.apiKeyOverrideForTests = null
        }
        val labelAfterOfflineRun = GeminiClient.lastUsedModel
        GeminiClient.lastUsedModel = null

        assertEquals(GeminiClient.OFFLINE_MODEL_LABEL, labelAfterOfflineRun)
        // The summary really was produced by the local engine, from the text of this session.
        assertTrue(result.getOrThrow().contains("تایپوگرافی"))
    }

    /**
     * What `BidiEngine.repairDefectsOnly` provably does is character work: it deletes the broken
     * characters, inserts the invisible marks and joins spaced words, while never adding a line or
     * dropping one. Deliberate break: restore the header promise `حفظ 100٪ لحن و معنای اصلی` and the
     * first assertion goes red on the Persian hundred alone.
     */
    @Test
    fun repairDocPromisesOnlyWhatTheRepairEngineProvablyDoes() {
        val doc = buildLocalRepairDoc(plain, sameDate)
        assertFalse("the perfect-preservation promise is back",
            doc.contains(BidiEngine.toPersianDigits("100")))
        assertTrue(doc.contains("بدون افزودن سطر و بدون توضیحات اضافی"))
        assertTrue(doc.contains("رفع حروف ناخوانا، اصلاح ی/ک عربی و چیدمان BiDi"))

        // Every turn of the session is still in the document, whole and unreworded.
        assertEquals(3, measureLocalConversation(plain).turnCount)
        assertTrue(doc.contains("فقط سیب رسیده را برداشت کن"))
        assertTrue(doc.contains("سیب نرسیده روی درخت بماند"))
        assertFalse(doc.contains("رعایت اصول"))
    }
}
