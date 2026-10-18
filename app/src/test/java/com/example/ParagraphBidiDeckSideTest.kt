package com.example

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.CodeSnippet
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import com.example.domain.exporter.ExportOptions
import com.example.domain.exporter.OpenXmlGenerator
import org.junit.Assert.assertEquals
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
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The DOCX and PPTX writers must put a paragraph on the side its OWN language asks for, not pin
 * everything right. Each writer carries the side as DIRECT paragraph properties (DOCX `w:bidi` +
 * `w:jc`, PPTX `a:pPr algn` + `rtl`), leaving the style-level RTL alone.
 */
class ParagraphBidiDeckSideTest {

    private val wordmlNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val presentationmlNs = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val drawingmlNs = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /** A distillation English line kept distinct from [ENGLISH] so the two never collide. */
    private val distillEnglish = "Key takeaway: export the chat to a docx file"

    private val code = "val x = compute(1)\nreturn x"

    /** One pure-Persian turn, one pure-English turn, one mixed turn carrying both in two lines. */
    private fun bidiFixture(): ChatSession = ChatSession(
        id = "bidi-openxml-fixture",
        title = "Persian and English turns",
        platform = PlatformPreset.CUSTOM,
        modelName = "gpt-4o",
        messages = listOf(
            ChatMessage(role = MessageRole.USER, content = PERSIAN, direction = TextDirection.RTL),
            ChatMessage(role = MessageRole.ASSISTANT, content = ENGLISH, direction = TextDirection.LTR),
            ChatMessage(
                role = MessageRole.USER,
                content = "$PERSIAN\n$ENGLISH",
                direction = TextDirection.MIXED,
                codeSnippets = listOf(CodeSnippet(language = "kotlin", code = code))
            )
        )
    )

    private val distillation = "$PERSIAN\n$distillEnglish"

    // --- helpers, copied in from the OOXML test so this file stands on its own ---

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

    /** Direct children only, so a nested property cannot be mistaken for one of this element. */
    private fun elementsOf(node: Element, ns: String, localName: String): List<Element> =
        descendantsOf(node, ns, localName).filter { it.parentNode === node }

    private fun descendantsOf(node: Element, ns: String, localName: String): List<Element> =
        (0 until node.getElementsByTagNameNS(ns, localName).length)
            .map { node.getElementsByTagNameNS(ns, localName).item(it) as Element }

    private fun childOf(node: Element?, ns: String, localName: String): Element? =
        node?.let { elementsOf(it, ns, localName).firstOrNull() }

    private fun pStyleOf(p: Element): String? =
        childOf(childOf(p, wordmlNs, "pPr"), wordmlNs, "pStyle")?.getAttribute("w:val")

    /** The run text of a paragraph, not its whole text content: the writer's own indentation is
     *  whitespace text between the tags, and comparing against it would match nothing. */
    private fun runTextOf(p: Element, ns: String): String =
        descendantsOf(p, ns, "t").joinToString("") { it.textContent }

    private fun slideNamesOf(parts: Map<String, String>): List<String> =
        parts.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sorted()

    private fun codeSlidesOf(parts: Map<String, String>): List<String> =
        slideNamesOf(parts).filter { parts.getValue(it).contains("typeface=\"Consolas\"") }

    /** The body paragraphs of a slide: the shape the writer calls "Body". The title slide has none. */
    private fun bodyParagraphsOf(slideXml: String): List<Element> {
        val doc = parsePart("slide", slideXml)
        val bodySp = elementsOf(doc, presentationmlNs, "sp").firstOrNull { sp ->
            descendantsOf(sp, presentationmlNs, "cNvPr").any { it.getAttribute("name") == "Body" }
        } ?: return emptyList()
        val txBody = descendantsOf(bodySp, presentationmlNs, "txBody").first()
        return elementsOf(txBody, drawingmlNs, "p")
    }

    private fun docxParts(): Map<String, String> {
        val file = File.createTempFile("bidi_docx_", ".docx")
        try {
            OpenXmlGenerator.generateRealDocx(file, bidiFixture(), distillation, ExportOptions())
            return zipPartsOf(file)
        } finally {
            file.delete()
        }
    }

    private fun pptxParts(): Map<String, String> {
        val file = File.createTempFile("bidi_pptx_", ".pptx")
        try {
            OpenXmlGenerator.generateRealPptx(file, bidiFixture(), distillation, ExportOptions())
            return zipPartsOf(file)
        } finally {
            file.delete()
        }
    }

    // --- DOCX ---

    @Test
    fun everyNonCodeParagraphPairsBidiWithAnAgreeingJc() {
        val doc = parsePart("word/document.xml", docxParts().getValue("word/document.xml"))
        var checked = 0
        elementsOf(doc, wordmlNs, "p")
            .filter { pStyleOf(it) != "Code" }
            .forEach { p ->
                val pPr = childOf(p, wordmlNs, "pPr") ?: return@forEach
                val bidi = childOf(pPr, wordmlNs, "bidi") ?: return@forEach
                val val0 = bidi.getAttribute("w:val")
                val expectedJc = if (val0.isEmpty() || val0 == "1" || val0 == "true") "right" else "left"
                val jc = childOf(pPr, wordmlNs, "jc")?.getAttribute("w:val")
                assertEquals("w:bidi val=$val0 must pair with jc=$expectedJc", expectedJc, jc)
                checked++
            }
        assertTrue("no paragraph carried a direct bidi to check", checked > 0)
    }

    @Test
    fun anEnglishLineSitsLeftWithItsAccentBarOnTheLeft() {
        val doc = parsePart("word/document.xml", docxParts().getValue("word/document.xml"))
        val english = elementsOf(doc, wordmlNs, "p")
            .filter { pStyleOf(it) == "Body" && runTextOf(it, wordmlNs) == ENGLISH }
        assertTrue("the fixture carries no English body line", english.isNotEmpty())
        english.forEach { p ->
            val pPr = childOf(p, wordmlNs, "pPr")!!
            assertEquals("0", childOf(pPr, wordmlNs, "bidi")?.getAttribute("w:val"))
            assertEquals("left", childOf(pPr, wordmlNs, "jc")?.getAttribute("w:val"))
            val pBdr = childOf(pPr, wordmlNs, "pBdr")!!
            assertNotNull("accent bar must hang off the left", childOf(pBdr, wordmlNs, "left"))
            assertNull("no right bar on a left paragraph", childOf(pBdr, wordmlNs, "right"))
        }
    }

    @Test
    fun thePersianLineStillSitsRight() {
        val doc = parsePart("word/document.xml", docxParts().getValue("word/document.xml"))
        val persian = elementsOf(doc, wordmlNs, "p")
            .filter { pStyleOf(it) == "Body" && runTextOf(it, wordmlNs) == PERSIAN }
        assertTrue("the fixture carries no Persian body line", persian.isNotEmpty())
        persian.forEach { p ->
            val pPr = childOf(p, wordmlNs, "pPr")!!
            assertEquals("", childOf(pPr, wordmlNs, "bidi")?.getAttribute("w:val"))
            assertEquals("right", childOf(pPr, wordmlNs, "jc")?.getAttribute("w:val"))
            assertNotNull("accent bar must hang off the right", childOf(childOf(pPr, wordmlNs, "pBdr"), wordmlNs, "right"))
        }
    }

    @Test
    fun aModelCellHoldingOnlyALatinNameSitsLeft() {
        val doc = parsePart("word/document.xml", docxParts().getValue("word/document.xml"))
        val cell = elementsOf(doc, wordmlNs, "p").first { runTextOf(it, wordmlNs) == "gpt-4o" }
        val pPr = childOf(cell, wordmlNs, "pPr")!!
        assertEquals("0", childOf(pPr, wordmlNs, "bidi")?.getAttribute("w:val"))
        assertEquals("left", childOf(pPr, wordmlNs, "jc")?.getAttribute("w:val"))
    }

    // --- PPTX ---

    /** Every body paragraph in the deck: a turn that mixes languages may be split across slides. */
    private fun deckBodyParagraphs(parts: Map<String, String>): List<Element> =
        slideNamesOf(parts).flatMap { bodyParagraphsOf(parts.getValue(it)) }

    @Test
    fun aPersianLineSitsRightAndAnEnglishLineSitsLeftOnSlides() {
        val paragraphs = deckBodyParagraphs(pptxParts())
        val persian = paragraphs.firstOrNull { runTextOf(it, drawingmlNs) == PERSIAN }
        val english = paragraphs.firstOrNull { runTextOf(it, drawingmlNs) == ENGLISH }
        assertNotNull("the deck carries no Persian body line", persian)
        assertNotNull("the deck carries no English body line", english)

        val persianPPr = elementsOf(persian!!, drawingmlNs, "pPr").first()
        assertEquals("a Persian line must align right", "r", persianPPr.getAttribute("algn"))
        assertEquals("1", persianPPr.getAttribute("rtl"))

        val englishPPr = elementsOf(english!!, drawingmlNs, "pPr").first()
        assertEquals("an English line must align left", "l", englishPPr.getAttribute("algn"))
        assertEquals("0", englishPPr.getAttribute("rtl"))
        assertEquals("en-US", descendantsOf(english, drawingmlNs, "rPr").first().getAttribute("lang"))
    }

    @Test
    fun aCodeSlideKeepsEveryParagraphLeftToRight() {
        val parts = pptxParts()
        val codeSlide = codeSlidesOf(parts).first()
        val flags = bodyParagraphsOf(parts.getValue(codeSlide)).mapNotNull { p ->
            elementsOf(p, drawingmlNs, "pPr").firstOrNull()?.getAttribute("rtl")
        }
        assertTrue("the code slide has no paragraphs", flags.isNotEmpty())
        assertTrue("a code paragraph flipped away from LTR: $flags", flags.all { it == "0" })
    }

    private companion object {
        // Copied byte-for-byte from ParagraphDirectionTest.kt; never hand-typed.
        const val PERSIAN = "سلام این یک متن آزمایشی به زبان فارسی است"
        const val ENGLISH = "Hello this is a test text in English language"
    }
}
