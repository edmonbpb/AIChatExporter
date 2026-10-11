package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.CodeSnippet
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import com.example.data.model.UserProfile
import com.example.domain.bidi.BidiEngine
import com.example.domain.exporter.DocumentExporter
import com.example.domain.exporter.ExportOptions
import com.example.domain.exporter.OpenXmlGenerator
import com.example.domain.parser.TranscriptIngestionEngine
import com.example.data.remote.auth.IdTokenClaims
import com.example.data.remote.auth.validateIdTokenClaims
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class ExampleUnitTest {

    private val nowSeconds = 1_760_000_000L
    private val clientId = "1044281816491-testclient.apps.googleusercontent.com"
    private val hashedNonce = "a".repeat(64)

    private fun tokenClaims(
        audience: List<String> = listOf(clientId),
        issuer: String = "https://accounts.google.com",
        email: String = "someone@example.com",
        expiresAtSeconds: Long = nowSeconds + 600,
        nonce: String = hashedNonce
    ) = IdTokenClaims(
        audience = audience,
        issuer = issuer,
        subject = "117654712854019016309",
        name = "Edmon Sb",
        email = email,
        picture = "",
        expiresAtSeconds = expiresAtSeconds,
        nonce = nonce
    )

    @Test
    fun idTokenWithOurOwnClaimsIsAccepted() {
        assertNull(validateIdTokenClaims(tokenClaims(), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun idTokenAudienceArrayHoldingOurClientIsAccepted() {
        val audience = listOf("1044281816491-other.apps.googleusercontent.com", clientId)
        assertNull(validateIdTokenClaims(tokenClaims(audience = audience), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun expiredIdTokenIsRejected() {
        assertNotNull(validateIdTokenClaims(tokenClaims(expiresAtSeconds = nowSeconds), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun idTokenIssuedForAnotherAppIsRejected() {
        assertNotNull(validateIdTokenClaims(tokenClaims(audience = listOf("someone-else")), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun idTokenFromUnknownIssuerIsRejected() {
        assertNotNull(validateIdTokenClaims(tokenClaims(issuer = "https://identity.example"), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun idTokenWithoutTheRequestedNonceIsRejected() {
        assertNotNull(validateIdTokenClaims(tokenClaims(nonce = "b".repeat(64)), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun idTokenWithoutAnEmailIsRejected() {
        assertNotNull(validateIdTokenClaims(tokenClaims(email = "   "), clientId, hashedNonce, nowSeconds))
    }

    @Test
    fun htmlExportEscapesMarkupComingFromChatContent() {
        val hostile = "<script>alert(1)</script> <img src=\"x\" onerror=\"alert(2)\">"
        val escaped = DocumentExporter.escapeHtml(hostile)
        assertFalse(escaped.contains("<script"))
        assertFalse(escaped.contains("<img"))
        assertTrue(escaped.contains("&lt;script&gt;"))
        assertTrue(escaped.contains("&quot;"))
    }

    @Test
    fun htmlParagraphsWrapEachLineAndEscapeIt() {
        assertEquals(
            "<p>first &lt;b&gt;bold&lt;/b&gt;</p>\n<p>second line</p>",
            DocumentExporter.htmlParagraphs("first <b>bold</b>\n\nsecond line")
        )
    }

    @Test
    fun testBidiDirectionDetection() {
        val persianText = "سلام این یک متن آزمایشی به زبان فارسی است"
        val englishText = "Hello this is a test text in English language"
        val mixedText = "این یک تست با متد ViewModel و کتابخانه Retrofit است."

        assertEquals(TextDirection.RTL, BidiEngine.detectDirection(persianText))
        assertEquals(TextDirection.LTR, BidiEngine.detectDirection(englishText))
        assertEquals(TextDirection.MIXED, BidiEngine.detectDirection(mixedText))
    }

    @Test
    fun testPersianTypographyNormalization() {
        val raw = "من می روم و کتاب ها را می خوانم"
        val normalized = BidiEngine.normalizePersianTypography(raw)
        assertTrue(normalized.contains("می\u200Cروم"))
        assertTrue(normalized.contains("کتاب\u200Cها"))
    }

    @Test
    fun testPersianDigitConversion() {
        val input = "Room 2.7.0 and 12345"
        val persianDigits = BidiEngine.toPersianDigits(input)
        assertEquals("Room ۲.۷.۰ and ۱۲۳۴۵", persianDigits)
    }

    @Test
    fun testTranscriptParserChatGPT() {
        val transcript = """
            User:
            چطور در اندروید کش طراحی کنم؟

            Assistant:
            برای طراحی کش از Redis استفاده کنید:
            ```kotlin
            val client = Redis()
            ```
        """.trimIndent()

        val session = TranscriptIngestionEngine.parseTranscript(transcript, PlatformPreset.CHATGPT)
        assertEquals(2, session.messages.size)
        assertEquals(MessageRole.USER, session.messages[0].role)
        assertEquals(MessageRole.ASSISTANT, session.messages[1].role)
        assertEquals(1, session.messages[1].codeSnippets.size)
        assertEquals("kotlin", session.messages[1].codeSnippets[0].language)
    }

    @Test
    fun testDeepSeekReasoningExtraction() {
        val transcript = """
            User:
            سوال من این است.

            Assistant:
            <think>
            این بخش استدلال مدل است.
            </think>
            پاسخ نهایی مدل به کاربر.
        """.trimIndent()

        val session = TranscriptIngestionEngine.parseTranscript(transcript, PlatformPreset.DEEPSEEK)
        assertEquals(2, session.messages.size)
        val aiMessage = session.messages[1]
        assertNotNull(aiMessage.reasoning)
        assertEquals("این بخش استدلال مدل است.", aiMessage.reasoning?.trim())
        assertEquals("پاسخ نهایی مدل به کاربر.", aiMessage.content.trim())
    }

    @Test
    fun testUserProfile() {
        val profile = UserProfile(
            id = "u123",
            name = "علی محمدی",
            email = "ali@example.com",
            isGuest = false,
            isGoogleDriveLinked = true
        )
        assertEquals("u123", profile.id)
        assertEquals("علی محمدی", profile.name)
        assertFalse(profile.isGuest)
        assertTrue(profile.isGoogleDriveLinked)
    }

    @Test
    fun testRealDocxGeneration() {
        val tempFile = File.createTempFile("test_export", ".docx")
        val session = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "جلسه معماری سیستم",
            platform = PlatformPreset.DEEPSEEK,
            modelName = "DeepSeek-R1",
            messages = listOf(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "توضیح معماری سیستم",
                    codeSnippets = emptyList()
                ),
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "معماری بر پایه Room و Coroutines است.",
                    codeSnippets = listOf(CodeSnippet(language = "kotlin", code = "val x = 10"))
                )
            )
        )

        OpenXmlGenerator.generateRealDocx(
            file = tempFile,
            session = session,
            distillationContent = "خلاصه تصمیمات",
            options = ExportOptions()
        )

        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 500)
        tempFile.delete()
    }

    @Test
    fun testSharedLinkUrlDetection() {
        val chatGptUrl = "https://chatgpt.com/share/67a1b2c3-d4e5-4f6a-8b9c-0123456789ab"
        val claudeUrl = "https://claude.ai/share/88ff99aa-bbcc-ddee-1122-334455667788"
        val perplexityUrl = "https://www.perplexity.ai/page/how-does-bidi-work"
        val deepSeekUrl = "https://chat.deepseek.com/share/abcdef"
        val textWithUrl = "سلام این لینک مکالمه من است: https://chatgpt.com/share/123 لطفا بررسی کنید."

        assertTrue(com.example.domain.parser.SharedLinkExtractor.containsUrl(chatGptUrl))
        assertTrue(com.example.domain.parser.SharedLinkExtractor.containsUrl(claudeUrl))
        assertTrue(com.example.domain.parser.SharedLinkExtractor.containsUrl(textWithUrl))

        assertEquals("https://chatgpt.com/share/123", com.example.domain.parser.SharedLinkExtractor.extractUrl(textWithUrl))
        assertEquals(PlatformPreset.CHATGPT, com.example.domain.parser.SharedLinkExtractor.detectPlatformFromUrl(chatGptUrl))
        assertEquals(PlatformPreset.CLAUDE, com.example.domain.parser.SharedLinkExtractor.detectPlatformFromUrl(claudeUrl))
        assertEquals(PlatformPreset.PERPLEXITY, com.example.domain.parser.SharedLinkExtractor.detectPlatformFromUrl(perplexityUrl))
        assertEquals(PlatformPreset.DEEPSEEK, com.example.domain.parser.SharedLinkExtractor.detectPlatformFromUrl(deepSeekUrl))
    }

    @Test
    fun testZeroToneDefectRepair() {
        val defectiveColloquial = "دمت گرم داداش ، كارت خيلي درسته! آيا مي توني باگ Android رو حل كني? مرسي"
        val repaired = BidiEngine.repairDefectsOnly(defectiveColloquial)

        // Arabic ك and ي replaced with Persian ک and ی
        assertTrue(repaired.contains("کارت"))
        assertTrue(repaired.contains("خیلی"))
        assertTrue(repaired.contains("مرسی"))
        // Half-space in verb
        assertTrue(repaired.contains("می\u200Cتونی"))
        // Persian punctuation
        assertTrue(repaired.contains("،"))
        assertTrue(repaired.contains("؟"))
        // Original informal colloquial tone strictly preserved
        assertTrue(repaired.contains("دمت گرم داداش"))
    }

    // --- OOXML package tests: real ZIP parts, real XML parser, counted graph ---

    private val wordmlNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val contentTypesNs = "http://schemas.openxmlformats.org/package/2006/content-types"
    private val relationshipsNs = "http://schemas.openxmlformats.org/package/2006/relationships"
    private val presentationmlNs = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val drawingmlNs = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /** 60 lines, one of them full of the characters that must survive XML escaping. */
    private val longCode: String = (1..60).joinToString("\n") { index ->
        if (index == 30) "if (n < 10 && n > 0) render(\"<b>\" + $index)" else "val value$index = compute($index)"
    }

    private fun exportFixture(turns: Int, code: String, createdAt: Long = System.currentTimeMillis()): ChatSession = ChatSession(
        id = "ooxml-fixture-session",
        title = "جلسه معماری سیستم",
        platform = PlatformPreset.DEEPSEEK,
        modelName = "DeepSeek-R1",
        createdAt = createdAt,
        messages = (1..turns).map { index ->
            ChatMessage(
                role = if (index % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "معماری بر پایه Room و Coroutines است. part-$index",
                codeSnippets = if (index == 2) listOf(CodeSnippet(language = "kotlin", code = code)) else emptyList()
            )
        }
    )

    private fun zipPartsOf(file: File): Map<String, String> {
        val parts = linkedMapOf<String, String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) parts[entry.name] = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }

    /** Any part that is not well-formed XML fails here, with the part name in the message. */
    private fun parsePart(partName: String, xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        return try {
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (e: SAXException) {
            throw AssertionError("part $partName is not well-formed XML: ${e.message}", e)
        }
    }

    private fun elementsOf(node: Document, ns: String, localName: String): List<Element> =
        (0 until node.getElementsByTagNameNS(ns, localName).length)
            .map { node.getElementsByTagNameNS(ns, localName).item(it) as Element }

    /** Direct children only, so a property of a nested element cannot be mistaken for one of this element. */
    private fun elementsOf(node: Element, ns: String, localName: String): List<Element> =
        descendantsOf(node, ns, localName).filter { it.parentNode === node }

    private fun descendantsOf(node: Element, ns: String, localName: String): List<Element> =
        (0 until node.getElementsByTagNameNS(ns, localName).length)
            .map { node.getElementsByTagNameNS(ns, localName).item(it) as Element }

    private fun childOf(node: Element?, ns: String, localName: String): Element? =
        node?.let { elementsOf(it, ns, localName).firstOrNull() }

    private fun slideNamesOf(parts: Map<String, String>): List<String> =
        parts.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sorted()

    private fun pptxPartsOf(
        turns: Int,
        code: String,
        distillation: String?,
        options: ExportOptions = ExportOptions(),
        createdAt: Long = System.currentTimeMillis()
    ): Map<String, String> {
        val file = File.createTempFile("ooxml_pptx_", ".pptx")
        try {
            OpenXmlGenerator.generateRealPptx(file, exportFixture(turns, code, createdAt), distillation, options)
            return zipPartsOf(file)
        } finally {
            file.delete()
        }
    }

    private fun docxPartsOf(
        turns: Int,
        code: String,
        distillation: String?,
        options: ExportOptions = ExportOptions(),
        createdAt: Long = System.currentTimeMillis()
    ): Map<String, String> {
        val file = File.createTempFile("ooxml_docx_", ".docx")
        try {
            OpenXmlGenerator.generateRealDocx(file, exportFixture(turns, code, createdAt), distillation, options)
            return zipPartsOf(file)
        } finally {
            file.delete()
        }
    }

    /** Every `<a:t>` of a slide, in document order. The first one is the slide heading. */
    private fun slideTextsOf(parts: Map<String, String>, slideName: String): List<String> =
        elementsOf(parsePart(slideName, parts.getValue(slideName)), drawingmlNs, "t").map { it.textContent }

    // --- which slides the deck carries, and what the screen may promise about them ---

    // The body text size is the only thing each section type asks for, so it tells the sections apart
    // without the test having to type the Persian heading it renders. Title 4000, summary 1800, turn 1600.
    private fun turnSlidesOf(parts: Map<String, String>): List<String> =
        slideNamesOf(parts).filter { parts.getValue(it).contains("sz=\"1600\"") }

    private fun summarySlidesOf(parts: Map<String, String>): List<String> =
        slideNamesOf(parts).filter { parts.getValue(it).contains("sz=\"1800\"") }

    /** The code slides are the only ones that ask for the monospace face, as the older test already says. */
    private fun codeSlidesOf(parts: Map<String, String>): List<String> =
        slideNamesOf(parts).filter { parts.getValue(it).contains("Consolas") }

    /**
     * The number the screen shows before the button is pressed and the number of slide parts really inside
     * the written package must be one number. Every shape goes through `generateRealPptx`: a short
     * conversation, one long enough to split turns and code and summary over many slides, one whose code
     * section falls back to its placeholder slide, and both new switches.
     */
    @Test
    fun theSlideEstimateEqualsTheDeckThatGetsWritten() {
        val longSummary = (1..120).joinToString("\n") { "summary line $it" }
        listOf(
            Triple(2, "val x = 10", null) to ExportOptions(),
            Triple(40, longCode, longSummary) to ExportOptions(),
            Triple(2, "", "decision notes") to ExportOptions(),
            Triple(8, longCode, longSummary) to ExportOptions(pptxSummaryAndCodeOnly = true),
            Triple(8, longCode, longSummary) to ExportOptions(includeCodeBlocks = false)
        ).forEach { (shape, options) ->
            val (turns, code, summary) = shape
            val written = slideNamesOf(
                pptxPartsOf(turns = turns, code = code, distillation = summary, options = options)
            ).size
            val estimated = OpenXmlGenerator.estimatePptxSlideCount(exportFixture(turns, code), summary, options)
            assertEquals(
                "the estimate must match the written deck ($turns turns, ${code.length} code chars, " +
                    "${summary?.length} summary chars, options=$options)",
                written,
                estimated
            )
        }
    }

    @Test
    fun theSummaryAndCodeOnlyDeckDropsTheTurnSlidesAndKeepsTheRest() {
        val options = ExportOptions(pptxSummaryAndCodeOnly = true)
        val full = pptxPartsOf(turns = 8, code = longCode, distillation = "decision notes")
        val short = pptxPartsOf(turns = 8, code = longCode, distillation = "decision notes", options = options)

        assertEquals("the full deck must carry one slide for each of the eight turns", 8, turnSlidesOf(full).size)
        assertEquals("the short deck must carry no turn slide at all", 0, turnSlidesOf(short).size)
        assertEquals("only the turn slides may go away", turnSlidesOf(full).size, slideNamesOf(full).size - slideNamesOf(short).size)
        assertTrue("the short deck must still carry the summary", summarySlidesOf(short).isNotEmpty())
        assertEquals(summarySlidesOf(full).size, summarySlidesOf(short).size)
        assertTrue("the short deck must still carry the code", codeSlidesOf(short).isNotEmpty())
        assertEquals(codeSlidesOf(full).size, codeSlidesOf(short).size)
        assertEquals(
            "the estimate must follow the switch, not the default deck",
            slideNamesOf(short).size,
            OpenXmlGenerator.estimatePptxSlideCount(exportFixture(8, longCode), "decision notes", options)
        )
    }

    /**
     * The witness that nothing is dropped quietly: with the options struct at its defaults the deck still
     * carries every turn and every line of code, exactly as it did before any shorter form existed.
     */
    @Test
    fun theDefaultOptionsStillWriteEveryTurnAndEveryCodeLine() {
        assertFalse(ExportOptions().pptxSummaryAndCodeOnly)
        val parts = pptxPartsOf(turns = 6, code = longCode, distillation = "decision notes")
        assertEquals("every one of the six turns needs its own slide", 6, turnSlidesOf(parts).size)

        val texts = slideNamesOf(parts).flatMap { slideTextsOf(parts, it) }
        (1..6).forEach { index ->
            assertTrue("turn $index is missing from the default deck", texts.any { it.contains("part-$index") })
        }
        longCode.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue("code line missing from the default deck: $line", line in texts)
        }
        assertEquals(
            "the default estimate is the full deck",
            slideNamesOf(parts).size,
            OpenXmlGenerator.estimatePptxSlideCount(exportFixture(6, longCode), "decision notes", ExportOptions())
        )
    }

    // --- the date inside the deliverable follows the digit switch ---

    /** The same frozen instant the token tests use, so the two switch positions are compared on one date. */
    private val fixtureCreatedAtMillis = nowSeconds * 1000

    /** Persian digits as code points, because literal Persian text is corrupted by the writers on this machine. */
    private val persianDigitRange = '\u06F0'..'\u06F9'

    /**
     * The date is the tail of its run, so matching on its character class finds it without asking the
     * test to type the Persian label that precedes it.
     */
    private fun dateTailOf(text: String): String {
        val run = text.takeLastWhile {
            it in '0'..'9' || it in persianDigitRange || it == '-' || it == '/' || it == ':' || it == ' '
        }
        assertTrue(
            "the date must be a tail of a longer run, not the whole run: <$text>",
            run.isNotEmpty() && run.length < text.length
        )
        // The walk back swallows the label's ": ", so the date itself starts at its first digit.
        return run.dropWhile { it !in '0'..'9' && it !in persianDigitRange }
    }

    /** The DOCX header run, the only one that opens with the app's own name. */
    private fun docxDateOf(convertToPersianDigits: Boolean): String {
        val parts = docxPartsOf(
            turns = 2,
            code = "val x = 10",
            distillation = null,
            options = ExportOptions(convertToPersianDigits = convertToPersianDigits),
            createdAt = fixtureCreatedAtMillis
        )
        return dateTailOf(
            elementsOf(parsePart("word/document.xml", parts.getValue("word/document.xml")), wordmlNs, "t")
                .map { it.textContent }
                .single { it.startsWith("AIChatExporter") }
        )
    }

    /** The PPTX title slide's meta run, the only one of its runs that separates fields with a pipe. */
    private fun pptxDateOf(convertToPersianDigits: Boolean): String {
        val parts = pptxPartsOf(
            turns = 2,
            code = "val x = 10",
            distillation = null,
            options = ExportOptions(convertToPersianDigits = convertToPersianDigits),
            createdAt = fixtureCreatedAtMillis
        )
        return dateTailOf(slideTextsOf(parts, "ppt/slides/slide1.xml").single { it.contains('|') })
    }

    @Test
    fun docxDateIsPersianWhileTheDigitSwitchIsOn() {
        val on = docxDateOf(convertToPersianDigits = true)
        val off = docxDateOf(convertToPersianDigits = false)
        assertEquals("the DOCX header date still carries a Latin digit: <$on>", 0, on.count { it in '0'..'9' })
        assertEquals("the DOCX header date must carry twelve Persian digits: <$on>", 12, on.count { it in persianDigitRange })
        assertEquals("only the digits may move, never the shape of the date", BidiEngine.toPersianDigits(off), on)
    }

    @Test
    fun docxDateStaysLatinWhenTheDigitSwitchIsOff() {
        val off = docxDateOf(convertToPersianDigits = false)
        assertEquals("an off switch must leave the twelve Latin digits alone: <$off>", 12, off.count { it in '0'..'9' })
        assertEquals("an off switch must not smuggle a Persian digit in: <$off>", 0, off.count { it in persianDigitRange })
        // Formatted here with the generator's own default zone, so the pin is the fixture instant, never the wall clock.
        assertEquals(
            "the DOCX header date must be the frozen fixture instant: <$off>",
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(fixtureCreatedAtMillis)),
            off
        )
        assertTrue("the DOCX header date lost its pattern: <$off>", Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}""").matches(off))
    }

    @Test
    fun pptxDateIsPersianWhileTheDigitSwitchIsOn() {
        val on = pptxDateOf(convertToPersianDigits = true)
        val off = pptxDateOf(convertToPersianDigits = false)
        assertEquals("the title-slide date still carries a Latin digit: <$on>", 0, on.count { it in '0'..'9' })
        assertEquals("the title-slide date must carry eight Persian digits: <$on>", 8, on.count { it in persianDigitRange })
        assertEquals("only the digits may move, never the shape of the date", BidiEngine.toPersianDigits(off), on)
    }

    @Test
    fun pptxDateStaysLatinWhenTheDigitSwitchIsOff() {
        val off = pptxDateOf(convertToPersianDigits = false)
        assertEquals("an off switch must leave the eight Latin digits alone: <$off>", 8, off.count { it in '0'..'9' })
        assertEquals("an off switch must not smuggle a Persian digit in: <$off>", 0, off.count { it in persianDigitRange })
        // Formatted here with the generator's own default zone, so the pin is the fixture instant, never the wall clock.
        assertEquals(
            "the title-slide date must be the frozen fixture instant: <$off>",
            SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(fixtureCreatedAtMillis)),
            off
        )
        assertTrue("the title-slide date lost its pattern: <$off>", Regex("""\d{4}/\d{2}/\d{2}""").matches(off))
    }

    @Test
    fun openXmlDatesComeFromTheSwitchAndNeverFromTheHandsetLocale() {
        val handset = Locale.getDefault()
        try {
            // A handset whose own digits are Persian must not be the thing that decides the digits in a file.
            Locale.setDefault(Locale.forLanguageTag("fa-IR"))
            val docxOff = docxDateOf(convertToPersianDigits = false)
            val docxOn = docxDateOf(convertToPersianDigits = true)
            val pptxOff = pptxDateOf(convertToPersianDigits = false)
            val pptxOn = pptxDateOf(convertToPersianDigits = true)
            assertEquals("the DOCX date must stay Latin with the switch off even on a Persian-digit handset: <$docxOff>", 12, docxOff.count { it in '0'..'9' })
            assertEquals("the title-slide date must stay Latin with the switch off even on a Persian-digit handset: <$pptxOff>", 8, pptxOff.count { it in '0'..'9' })
            assertEquals("the DOCX date must be the switch that writes Persian digits: <$docxOn>", 12, docxOn.count { it in persianDigitRange })
            assertEquals("the title-slide date must be the switch that writes Persian digits: <$pptxOn>", 8, pptxOn.count { it in persianDigitRange })
        } finally {
            Locale.setDefault(handset)
        }
    }

    @Test
    fun everyGeneratedOpenXmlPartIsWellFormedXmlAndThePartSetIsExact() {
        val docxParts = docxPartsOf(turns = 8, code = longCode, distillation = "خلاصه تصمیمات")
        val pptxParts = pptxPartsOf(turns = 8, code = longCode, distillation = "خلاصه تصمیمات")

        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "word/_rels/document.xml.rels",
                "word/styles.xml",
                "word/document.xml"
            ),
            docxParts.keys
        )

        val slides = slideNamesOf(pptxParts)
        assertTrue("an eight-turn conversation must produce more than three slides", slides.size > 3)
        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "ppt/_rels/presentation.xml.rels",
                "ppt/presentation.xml"
            ) + slides,
            pptxParts.keys
        )

        // Every part of both packages goes through the parser; a malformed one fails with its name.
        var parsed = 0
        listOf(docxParts, pptxParts).forEach { parts ->
            parts.forEach { (name, xml) ->
                parsePart(name, xml)
                parsed++
            }
        }
        assertTrue("both packages together should carry more than a dozen parts", parsed >= 12)

        // Each package registers exactly the parts it contains, and its entry point resolves.
        val docxOverrides = elementsOf(
            parsePart("[Content_Types].xml", docxParts.getValue("[Content_Types].xml")),
            contentTypesNs,
            "Override"
        ).map { it.getAttribute("PartName") }
        assertEquals(setOf("/word/document.xml", "/word/styles.xml"), docxOverrides.toSet())

        listOf(docxParts to "word/document.xml", pptxParts to "ppt/presentation.xml").forEach { (parts, expected) ->
            val rootRels = parsePart("_rels/.rels", parts.getValue("_rels/.rels"))
            val targets = elementsOf(rootRels, relationshipsNs, "Relationship").map { it.getAttribute("Target") }
            assertEquals(listOf(expected), targets)
            assertTrue("the entry point must be a part inside the ZIP", targets.all { it in parts.keys })
        }

        // The document part must reach the styles part through its own relationship.
        val docRels = parsePart("word/_rels/document.xml.rels", docxParts.getValue("word/_rels/document.xml.rels"))
        assertEquals(
            listOf("styles.xml"),
            elementsOf(docRels, relationshipsNs, "Relationship").map { it.getAttribute("Target") }
        )
    }
    @Test
    fun docxDeclaresFourParagraphStylesAndOnlyHeadingsGetOutlineLevels() {
        val styles = parsePart(
            "word/styles.xml",
            docxPartsOf(turns = 2, code = "val x = 10", distillation = "خلاصه تصمیمات").getValue("word/styles.xml")
        )
        val declared = elementsOf(styles, wordmlNs, "style")
        assertEquals(
            "the document must ship exactly the four named paragraph styles",
            listOf("Body", "Code", "Heading1", "Heading2"),
            declared.map { it.getAttribute("w:styleId") }.sorted()
        )

        val outlineLevels = declared.associate { style ->
            style.getAttribute("w:styleId") to childOf(childOf(style, wordmlNs, "pPr"), wordmlNs, "outlineLvl")
                ?.getAttribute("w:val")
        }
        assertEquals("0", outlineLevels["Heading1"])
        assertEquals("1", outlineLevels["Heading2"])
        assertNull("only headings may enter the navigation pane", outlineLevels["Body"])
        assertNull("only headings may enter the navigation pane", outlineLevels["Code"])

        declared.forEach { style ->
            val id = style.getAttribute("w:styleId")
            assertNotNull("$id carries no w:name", childOf(style, wordmlNs, "name"))
            childOf(style, wordmlNs, "basedOn")?.getAttribute("w:val")?.let { base ->
                assertTrue("$id is based on the undeclared style $base", base in outlineLevels.keys)
            }
        }
        assertTrue(
            "Heading1 must inherit from a declared style",
            childOf(declared.first { it.getAttribute("w:styleId") == "Heading1" }, wordmlNs, "basedOn") != null
        )
    }

    @Test
    fun docxHeadingParagraphsCarryPStyleAndNoLongerFakeBoldSizeTwentyEight() {
        val parts = docxPartsOf(turns = 8, code = longCode, distillation = "خلاصه تصمیمات")
        val doc = parsePart("word/document.xml", parts.getValue("word/document.xml"))
        val stylesDoc = parsePart("word/styles.xml", parts.getValue("word/styles.xml"))
        val declared = elementsOf(stylesDoc, wordmlNs, "style").map { it.getAttribute("w:styleId") }.toSet()

        val styledParagraphs = elementsOf(doc, wordmlNs, "p").mapNotNull { paragraph ->
            val styleId = childOf(childOf(paragraph, wordmlNs, "pPr"), wordmlNs, "pStyle")?.getAttribute("w:val")
            if (styleId.isNullOrEmpty()) null else styleId to paragraph.textContent
        }

        val sectionHeadings = styledParagraphs.filter { (_, text) ->
            text.contains("(AI Distillation)") || text.contains("(Transcript)")
        }
        assertEquals(2, sectionHeadings.size)
        sectionHeadings.forEach { (styleId, text) ->
            assertEquals("section heading paragraphs must use the real style: $text", "Heading1", styleId)
        }
        assertTrue(
            "every turn label must be a Heading2 so the navigation pane lists the turns",
            styledParagraphs.count { (styleId, _) -> styleId == "Heading2" } >= 8
        )
        assertEquals(1, styledParagraphs.count { (styleId, _) -> styleId == "Code" })
        assertTrue(styledParagraphs.any { (styleId, _) -> styleId == "Body" })

        // Nothing may reference a style the package never declares.
        styledParagraphs.map { it.first }.distinct().forEach { styleId ->
            assertTrue("$styleId is used but never declared in word/styles.xml", styleId in declared)
        }

        // The direct formatting that used to fake these headings is gone.
        assertTrue(
            "document.xml still fakes a heading with a direct <w:sz w:val=\"28\"/>",
            elementsOf(doc, wordmlNs, "sz").none { it.getAttribute("w:val") == "28" }
        )
        sectionHeadings.forEach { (styleId, text) ->
            val paragraph = elementsOf(doc, wordmlNs, "p").first { p ->
                childOf(childOf(p, wordmlNs, "pPr"), wordmlNs, "pStyle")?.getAttribute("w:val") == styleId &&
                    p.textContent == text
            }
            assertTrue(
                "the style must supply the weight and the size, not the run: $text",
                descendantsOf(paragraph, wordmlNs, "b").isEmpty() &&
                    descendantsOf(paragraph, wordmlNs, "sz").isEmpty()
            )
        }
    }

    @Test
    fun docxKeepsRtlMarksAndVazirmatnAlongsideTheNewStyles() {
        val styles = parsePart(
            "word/styles.xml",
            docxPartsOf(turns = 2, code = "val x = 10", distillation = "خلاصه تصمیمات").getValue("word/styles.xml")
        )

        val defaults = elementsOf(styles, wordmlNs, "docDefaults")
        assertEquals(1, defaults.size)
        val defaultPPr = childOf(childOf(defaults.first(), wordmlNs, "pPrDefault"), wordmlNs, "pPr")!!
        assertNotNull("docDefaults lost the paragraph bidi mark", childOf(defaultPPr, wordmlNs, "bidi"))
        assertEquals("right", childOf(defaultPPr, wordmlNs, "jc")?.getAttribute("w:val"))
        val defaultRPr = childOf(childOf(defaults.first(), wordmlNs, "rPrDefault"), wordmlNs, "rPr")!!
        assertEquals("fa-IR", childOf(defaultRPr, wordmlNs, "lang")?.getAttribute("w:bidi"))
        assertEquals(
            "the Vazirmatn run font of docDefaults must survive",
            "Vazirmatn",
            childOf(defaultRPr, wordmlNs, "rFonts")?.getAttribute("w:cs")
        )

        elementsOf(styles, wordmlNs, "style").forEach { style ->
            val id = style.getAttribute("w:styleId")
            val pPr = childOf(style, wordmlNs, "pPr")
            assertNotNull("$id has no paragraph properties", pPr)
            assertNotNull("$id lost its bidi mark", childOf(pPr, wordmlNs, "bidi"))
            assertNotNull("$id lost its language mark", childOf(childOf(style, wordmlNs, "rPr"), wordmlNs, "lang"))
            assertEquals("$id lost w:lang w:bidi", "fa-IR", childOf(childOf(style, wordmlNs, "rPr"), wordmlNs, "lang")?.getAttribute("w:bidi"))
            if (id == "Code") {
                assertEquals("a code block stays left-to-right", "0", childOf(pPr, wordmlNs, "bidi")?.getAttribute("w:val"))
                assertEquals("left", childOf(pPr, wordmlNs, "jc")?.getAttribute("w:val"))
            } else {
                assertEquals("$id must stay right-aligned", "right", childOf(pPr, wordmlNs, "jc")?.getAttribute("w:val"))
            }
        }
    }

    @Test
    fun pptxSlideCountDrivesThePartsContentTypesAndTheRelationshipGraph() {
        val parts = pptxPartsOf(turns = 8, code = longCode, distillation = "خلاصه تصمیمات")
        val slides = slideNamesOf(parts)
        assertTrue(
            "an eight-turn conversation must produce more than the three hard-coded slides, got ${slides.size}",
            slides.size > 3
        )

        val overrides = elementsOf(parsePart("[Content_Types].xml", parts.getValue("[Content_Types].xml")), contentTypesNs, "Override")
            .map { it.getAttribute("PartName") }
            .filter { it.startsWith("/ppt/slides/slide") }
        assertEquals(slides.size, overrides.size)

        val rels = parsePart("ppt/_rels/presentation.xml.rels", parts.getValue("ppt/_rels/presentation.xml.rels"))
        val slideRels = elementsOf(rels, relationshipsNs, "Relationship")
            .filter { it.getAttribute("Type").endsWith("/slide") }
        assertEquals(slides.size, slideRels.size)

        val presentation = parsePart("ppt/presentation.xml", parts.getValue("ppt/presentation.xml"))
        val slideIds = elementsOf(presentation, presentationmlNs, "sldId")
        assertEquals(slides.size, slideIds.size)

        // The three lists must describe the same graph: same targets, same ids, no dangling reference.
        assertEquals(slides.map { "/$it" }.toSet(), overrides.toSet())
        assertEquals(slides.toSet(), slideRels.map { "ppt/" + it.getAttribute("Target") }.toSet())
        val relIds = slideRels.map { it.getAttribute("Id") }
        assertEquals(relIds.size, relIds.toSet().size)
        assertEquals(relIds.toSet(), slideIds.map { it.getAttribute("r:id") }.toSet())
        val numericIds = slideIds.map { it.getAttribute("id") }
        assertEquals(numericIds.size, numericIds.toSet().size)
        assertTrue(
            "slide ids must start at the first legal value",
            numericIds.map { it.toInt() }.minOrNull()!! >= 256
        )

        slides.forEach { name -> parsePart(name, parts.getValue(name)) }
    }

    @Test
    fun pptxSplitsALongCodeBlockOverSeveralSlidesInsteadOfCuttingItAtFiveHundredCharacters() {
        val parts = pptxPartsOf(turns = 8, code = longCode, distillation = "خلاصه تصمیمات")
        // The code slides are the only ones that ask for the monospace face.
        val codeSlides = slideNamesOf(parts).filter { parts.getValue(it).contains("Consolas") }
        assertTrue(
            "a 60-line snippet must be split over continuation slides, got ${codeSlides.size}",
            codeSlides.size > 1
        )

        val codeTexts = codeSlides.flatMap { name -> slideTextsOf(parts, name) }
        longCode.lines().filter { it.isNotBlank() }.forEach { line ->
            assertTrue("code line missing from the deck: $line", line in codeTexts)
        }
        assertTrue(
            "the deck must carry more code than the old 500-character window allowed",
            codeTexts.joinToString("\n").length > 500
        )
    }

    @Test
    fun pptxSummaryLongerThanTheOldSixHundredCharacterCapKeepsEveryLine() {
        val summary = (1..80).joinToString("\n") { "خلاصه تصمیمات $it" }
        val parts = pptxPartsOf(turns = 2, code = "", distillation = summary)
        val slides = slideNamesOf(parts)
        assertTrue("the summary alone needs more than three slides", slides.size > 3)

        val allTexts = slides.flatMap { name -> slideTextsOf(parts, name) }
        summary.lines().forEach { line ->
            assertTrue("summary line missing from the deck: $line", line in allTexts)
        }

        val headings = slides.map { name -> slideTextsOf(parts, name).firstOrNull().orEmpty() }
        assertTrue(
            "a continued summary slide must say so instead of silently dropping the rest",
            headings.count { it.contains("خلاصه اجرایی") } > 1
        )
        assertTrue(
            "the continuation slides must be marked as continuation",
            headings.any { it.contains("ادامهٔ") }
        )
    }
}
