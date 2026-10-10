package com.example.domain.exporter

import com.example.data.model.ChatSession
import com.example.data.model.MessageRole
import com.example.domain.bidi.BidiEngine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * High-performance, pure Java/Kotlin OpenXML (.docx & .pptx) generator.
 * Creates genuine binary OpenXML ZIP archives without heavy external dependencies.
 */
object OpenXmlGenerator {

    // Slide budgets. These are conservative CHARACTER counts for the 7.9M-EMU-wide body frame at the sizes
    // used below, chosen so a slide stays readable; they are not a measured text layout of PowerPoint.
    private const val PPTX_BODY_MAX_CHARS = 1200
    private const val PPTX_BODY_MAX_LINES = 16
    private const val PPTX_CODE_MAX_CHARS = 900
    private const val PPTX_CODE_MAX_LINES = 22

    /** Marks a slide that carries the rest of a chunk that did not fit on the previous one. */
    private const val PPTX_CONTINUATION_PREFIX = "ادامهٔ "

    /**
     * Generates a genuine Microsoft Word (.docx) OpenXML document with native RTL,
     * styled tables, custom headings, code callout boxes, and executive summary sections.
     */
    fun generateRealDocx(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val title = options.customTitle ?: session.title
        val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(session.createdAt))

        ZipOutputStream(FileOutputStream(file)).use { zos ->
            // 1. [Content_Types].xml
            writeZipEntry(zos, "[Content_Types].xml", buildContentTypesXml())

            // 2. _rels/.rels
            writeZipEntry(zos, "_rels/.rels", buildRootRelsXml())

            // 3. word/_rels/document.xml.rels
            writeZipEntry(zos, "word/_rels/document.xml.rels", buildDocRelsXml())

            // 4. word/styles.xml
            writeZipEntry(zos, "word/styles.xml", buildStylesXml())

            // 5. word/document.xml
            val docXml = buildDocumentXml(session, distillationContent, title, dateStr, options)
            writeZipEntry(zos, "word/document.xml", docXml)
        }
    }

    /**
     * Generates a genuine PowerPoint (.pptx) OpenXML presentation: a title slide, the summary, one slide per
     * conversation turn and one or more slides per code block. The deck is as long as the conversation needs,
     * and the part list, the content-type overrides and the slide relationships are all derived from that one
     * count so the three never disagree.
     */
    fun generateRealPptx(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val title = options.customTitle ?: session.title
        val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(session.createdAt))
        val slides = buildPptxSlideParts(session, distillationContent, title, dateStr, options)

        ZipOutputStream(FileOutputStream(file)).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", buildPptxContentTypesXml(slides.size))
            writeZipEntry(zos, "_rels/.rels", buildPptxRootRelsXml())
            writeZipEntry(zos, "ppt/_rels/presentation.xml.rels", buildPptxPresRelsXml(slides.size))
            writeZipEntry(zos, "ppt/presentation.xml", buildPptxPresentationXml(slides.size))
            slides.forEachIndexed { index, slideXml ->
                writeZipEntry(zos, "ppt/slides/slide${index + 1}.xml", slideXml)
            }
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, path: String, content: String) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // --- DOCX OpenXML Templates ---

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private fun buildRootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private fun buildDocRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    /**
     * Real paragraph styles, not direct formatting. `Heading1`/`Heading2` carry `w:outlineLvl`, so Word's
     * navigation pane and a field-based table of contents have something to index; `Body` and `Code` keep the
     * right-to-left marks (`w:bidi`, `w:jc`, `w:lang w:bidi="fa-IR"`) that `w:docDefaults` already sets, so the
     * Vazirmatn faces and the RTL flow survive the move from run-level properties into named styles.
     * Element order inside `w:pPr`/`w:rPr`/`w:style` follows the WordprocessingML sequence.
     */
    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Vazirmatn" w:cs="Vazirmatn" w:eastAsia="Vazirmatn" w:hAnsi="Vazirmatn"/>
        <w:sz w:val="24"/>
        <w:szCs w:val="24"/>
        <w:lang w:bidi="fa-IR"/>
      </w:rPr>
    </w:rPrDefault>
    <w:pPrDefault>
      <w:pPr>
        <w:bidi/>
        <w:jc w:val="right"/>
        <w:spacing w:line="360" w:lineRule="auto" w:after="160"/>
      </w:pPr>
    </w:pPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:styleId="Body">
    <w:name w:val="Body"/>
    <w:qFormat/>
    <w:pPr>
      <w:bidi/>
      <w:spacing w:line="360" w:lineRule="auto" w:after="160"/>
      <w:jc w:val="right"/>
    </w:pPr>
    <w:rPr>
      <w:rFonts w:ascii="Vazirmatn" w:hAnsi="Vazirmatn" w:cs="Vazirmatn"/>
      <w:sz w:val="24"/>
      <w:szCs w:val="24"/>
      <w:lang w:bidi="fa-IR"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading1">
    <w:name w:val="heading 1"/>
    <w:basedOn w:val="Body"/>
    <w:next w:val="Body"/>
    <w:uiPriority w:val="9"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:pBdr><w:bottom w:val="single" w:sz="12" w:space="4" w:color="6366F1"/></w:pBdr>
      <w:bidi/>
      <w:spacing w:before="240" w:after="160" w:line="360" w:lineRule="auto"/>
      <w:jc w:val="right"/>
      <w:outlineLvl w:val="0"/>
    </w:pPr>
    <w:rPr>
      <w:rFonts w:ascii="Vazirmatn" w:hAnsi="Vazirmatn" w:cs="Vazirmatn"/>
      <w:b/>
      <w:color w:val="4338CA"/>
      <w:sz w:val="28"/>
      <w:szCs w:val="28"/>
      <w:lang w:bidi="fa-IR"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Heading2">
    <w:name w:val="heading 2"/>
    <w:basedOn w:val="Body"/>
    <w:next w:val="Body"/>
    <w:uiPriority w:val="9"/>
    <w:qFormat/>
    <w:pPr>
      <w:keepNext/>
      <w:bidi/>
      <w:spacing w:before="160" w:after="80" w:line="360" w:lineRule="auto"/>
      <w:jc w:val="right"/>
      <w:outlineLvl w:val="1"/>
    </w:pPr>
    <w:rPr>
      <w:b/>
      <w:color w:val="4338CA"/>
      <w:sz w:val="24"/>
      <w:szCs w:val="24"/>
      <w:lang w:bidi="fa-IR"/>
    </w:rPr>
  </w:style>
  <w:style w:type="paragraph" w:styleId="Code">
    <w:name w:val="Code"/>
    <w:uiPriority w:val="16"/>
    <w:qFormat/>
    <w:pPr>
      <w:pBdr><w:left w:val="single" w:sz="24" w:space="8" w:color="38BDF8"/></w:pBdr>
      <w:shd w:val="clear" w:color="auto" w:fill="0F172A"/>
      <w:bidi w:val="0"/>
      <w:spacing w:before="80" w:after="80" w:line="240" w:lineRule="auto"/>
      <w:jc w:val="left"/>
    </w:pPr>
    <w:rPr>
      <w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>
      <w:color w:val="38BDF8"/>
      <w:sz w:val="19"/>
      <w:szCs w:val="19"/>
      <w:lang w:bidi="fa-IR"/>
    </w:rPr>
  </w:style>
</w:styles>"""

    private fun buildDocumentXml(
        session: ChatSession,
        distillationContent: String?,
        title: String,
        dateStr: String,
        options: ExportOptions
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>""")

        // Title Header Box
        appendLine("""
    <w:p>
      <w:pPr>
        <w:pBdr>
          <w:bottom w:val="single" w:sz="24" w:space="8" w:color="6366F1"/>
        </w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="1E1B4B"/>
        <w:spacing w:before="240" w:after="240"/>
        <w:jc w:val="center"/>
      </w:pPr>
      <w:r>
        <w:rPr>
          <w:b/>
          <w:color w:val="FFFFFF"/>
          <w:sz w:val="38"/>
          <w:szCs w:val="38"/>
        </w:rPr>
        <w:t>${escapeXml(title)}</w:t>
      </w:r>
    </w:p>
    <w:p>
      <w:pPr><w:spacing w:after="300"/><w:jc w:val="center"/></w:pPr>
      <w:r>
        <w:rPr><w:color w:val="6366F1"/><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr>
        <w:t>AIChatExporter • پلتفرم: ${escapeXml(session.platform.displayName)} • مدل: ${escapeXml(session.modelName)} • تاریخ: $dateStr</w:t>
      </w:r>
    </w:p>""")

        // Metadata Table
        if (options.includeMetadataTable) {
            val wordsStr = if (options.convertToPersianDigits) BidiEngine.toPersianDigits(session.totalWords.toString()) else session.totalWords.toString()
            val tokensStr = if (options.convertToPersianDigits) BidiEngine.toPersianDigits(session.tokenEstimate.toString()) else session.tokenEstimate.toString()
            val msgsStr = if (options.convertToPersianDigits) BidiEngine.toPersianDigits(session.messages.size.toString()) else session.messages.size.toString()

            appendLine("""
    <w:tbl>
      <w:tblPr>
        <w:tblW w:w="5000" w:type="pct"/>
        <w:tblBorders>
          <w:top w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>
          <w:left w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>
          <w:bottom w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>
          <w:right w:val="single" w:sz="6" w:space="0" w:color="E2E8F0"/>
          <w:insideH w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>
          <w:insideV w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>
        </w:tblBorders>
        <w:tblCellMar>
          <w:top w:w="120" w:type="dxa"/><w:left w:w="160" w:type="dxa"/>
          <w:bottom w:w="120" w:type="dxa"/><w:right w:w="160" w:type="dxa"/>
        </w:tblCellMar>
      </w:tblPr>
      <w:tr>
        <w:tc><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="4F46E5"/></w:tcPr><w:p><w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:t>ویژگی</w:t></w:r></w:p></w:tc>
        <w:tc><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="4F46E5"/></w:tcPr><w:p><w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/></w:rPr><w:t>مقدار</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>مدل هوش مصنوعی:</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>${escapeXml(session.modelName)}</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>تعداد پیام‌ها:</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>$msgsStr نوبت</w:t></w:r></w:p></w:tc>
      </w:tr>
      <w:tr>
        <w:tc><w:p><w:r><w:t>حجم کلمات و توکن:</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>$wordsStr کلمه (~$tokensStr توکن)</w:t></w:r></w:p></w:tc>
      </w:tr>
    </w:tbl>
    <w:p><w:spacing w:after="200"/></w:p>""")
        }

        // Executive Summary Section
        if (options.includeExecutiveSummary && !distillationContent.isNullOrBlank()) {
            appendLine("""
    <w:p>
      <w:pPr>
        <w:pStyle w:val="Heading1"/>
      </w:pPr>
      <w:r><w:t>📊 خلاصه و پالایش هوشمند (AI Distillation)</w:t></w:r>
    </w:p>""")

            distillationContent.lines().forEach { line ->
                appendLine("""
    <w:p>
      <w:pPr>
        <w:pStyle w:val="Body"/>
        <w:pBdr><w:right w:val="single" w:sz="24" w:space="8" w:color="EAB308"/></w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="FEFCE8"/>
      </w:pPr>
      <w:r><w:t>${escapeXml(line)}</w:t></w:r>
    </w:p>""")
            }
        }

        // Chat Transcript Messages
        appendLine("""
    <w:p>
      <w:pPr>
        <w:pStyle w:val="Heading1"/>
      </w:pPr>
      <w:r><w:t>💬 متن کامل گفتگو (Transcript)</w:t></w:r>
    </w:p>""")

        session.messages.forEachIndexed { idx, msg ->
            val isUser = msg.role == MessageRole.USER
            val turnLabel = if (isUser) "👤 پرسش کاربر" else "🤖 پاسخ هوش مصنوعی (${msg.modelTag ?: session.modelName})"
            val borderColor = if (isUser) "22C55E" else "6366F1"
            val fillColor = if (isUser) "F0FDF4" else "F8FAFC"
            val numStr = if (options.convertToPersianDigits) BidiEngine.toPersianDigits((idx + 1).toString()) else (idx + 1).toString()

            appendLine("""
    <w:p>
      <w:pPr>
        <w:pStyle w:val="Heading2"/>
        <w:pBdr><w:right w:val="single" w:sz="24" w:space="8" w:color="$borderColor"/></w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="$fillColor"/>
        <w:spacing w:before="160" w:after="80"/>
      </w:pPr>
      <w:r><w:rPr><w:color w:val="${if (isUser) "15803D" else "4338CA"}"/></w:rPr><w:t>نوبت #$numStr — $turnLabel</w:t></w:r>
    </w:p>""")

            msg.content.lines().forEach { line ->
                appendLine("""
    <w:p>
      <w:pPr>
        <w:pStyle w:val="Body"/>
        <w:pBdr><w:right w:val="single" w:sz="12" w:space="8" w:color="$borderColor"/></w:pBdr>
        <w:shd w:val="clear" w:color="auto" w:fill="$fillColor"/>
      </w:pPr>
      <w:r><w:t>${escapeXml(line)}</w:t></w:r>
    </w:p>""")
            }

            if (options.includeCodeBlocks && msg.codeSnippets.isNotEmpty()) {
                msg.codeSnippets.forEach { snippet ->
                    appendLine("""
    <w:p>
      <w:pPr>
        <w:pStyle w:val="Code"/>
      </w:pPr>
      <w:r>
        <w:t xml:space="preserve">${escapeXml(snippet.code)}</w:t>
      </w:r>
    </w:p>""")
                }
            }
        }

        // Section properties
        appendLine("""
    <w:sectPr>
      <w:pgSz w:w="11906" w:h="16838"/>
      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>
    </w:sectPr>
  </w:body>
</w:document>""")
    }

    // --- PPTX OpenXML Templates ---

    /**
     * One `<Override>` per slide that actually exists, so the content-type map matches the part list
     * whatever the length of the conversation.
     */
    private fun buildPptxContentTypesXml(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
""")
        for (index in 1..slideCount) {
            append("""  <Override PartName="/ppt/slides/slide$index.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
""")
        }
        append("</Types>")
    }

    private fun buildPptxRootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""

    /** One slide relationship per slide, ids kept in step with `p:sldIdLst` below. */
    private fun buildPptxPresRelsXml(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
""")
        for (index in 1..slideCount) {
            append("""  <Relationship Id="rId$index" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide$index.xml"/>
""")
        }
        append("</Relationships>")
    }

    /** `p:sldId` ids are unique and start at 256, the first id the format allows. */
    private fun buildPptxPresentationXml(slideCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:sldIdLst>
""")
        for (index in 1..slideCount) {
            append("""    <p:sldId id="${255 + index}" r:id="rId$index"/>
""")
        }
        append("""  </p:sldIdLst>
  <p:sldSz cx="9144000" cy="5143500" type="screen16x9"/>
</p:presentation>""")
    }

    private fun buildPptxTitleSlideXml(title: String, platform: String, model: String, date: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="800000" y="1200000"/><a:ext cx="7544000" cy="1800000"/></a:xfrm></p:spPr>
        <p:txBody>
          <a:bodyPr rtlCol="1"/><a:lstStyle/>
          <a:p><a:r><a:rPr lang="fa-IR" sz="4000" b="1"><a:solidFill><a:srgbClr val="38BDF8"/></a:solidFill></a:rPr><a:t>${escapeXml(title)}</a:t></a:r></a:p>
          <a:p><a:r><a:rPr lang="fa-IR" sz="2000"><a:solidFill><a:srgbClr val="A5B4FC"/></a:solidFill></a:rPr><a:t>پلتفرم: ${escapeXml(platform)} | مدل: ${escapeXml(model)} | تاریخ: ${escapeXml(date)}</a:t></a:r></a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>"""

    /**
     * The summary, every conversation turn and every code block, each split over as many slides as it
     * needs. Nothing is clipped any more: the old deck stopped at a 600-character summary head and a
     * 500-character code head, and it always stopped at three slides.
     */
    private fun buildPptxSlideParts(
        session: ChatSession,
        distillationContent: String?,
        title: String,
        dateStr: String,
        options: ExportOptions
    ): List<String> {
        val shown = persianDigitFormatter(options)
        val slides = mutableListOf<String>()
        slides += buildPptxTitleSlideXml(title, session.platform.displayName, session.modelName, dateStr)
        slides += buildPptxSummarySlidesXml(session, distillationContent, shown)
        slides += buildPptxTurnSlidesXml(session, shown)
        if (options.includeCodeBlocks) slides += buildPptxCodeSlidesXml(session)
        return slides
    }

    /** Counts spoken of to a Persian reader use Persian digits; never applied to chat or code text. */
    private fun persianDigitFormatter(options: ExportOptions): (Int) -> String = { value ->
        if (options.convertToPersianDigits) BidiEngine.toPersianDigits(value.toString()) else value.toString()
    }

    private fun buildPptxSummarySlidesXml(
        session: ChatSession,
        distillation: String?,
        shown: (Int) -> String
    ): List<String> {
        val heading = "📊 خلاصه اجرایی و نکات کلیدی"
        val summaryText = distillation?.takeIf { it.isNotBlank() }
            ?: "تعداد کل پیام‌ها: ${shown(session.messages.size)} • کلمات: ${shown(session.totalWords)}"
        return slideChunks(summaryText, PPTX_BODY_MAX_CHARS, PPTX_BODY_MAX_LINES).mapIndexed { index, lines ->
            buildPptxTextSlideXml(
                heading = if (index == 0) heading else "$PPTX_CONTINUATION_PREFIX$heading",
                headingColor = "6366F1",
                lines = lines,
                textColor = "E2E8F0",
                fontSize = 1800,
                monospace = false,
                rtl = true
            )
        }
    }

    private fun buildPptxTurnSlidesXml(session: ChatSession, shown: (Int) -> String): List<String> {
        val slides = mutableListOf<String>()
        session.messages.forEachIndexed { index, msg ->
            val isUser = msg.role == MessageRole.USER
            val turnLabel = if (isUser) "👤 پرسش کاربر" else "🤖 پاسخ هوش مصنوعی (${msg.modelTag ?: session.modelName})"
            val heading = "نوبت #${shown(index + 1)} — $turnLabel"
            slideChunks(msg.content, PPTX_BODY_MAX_CHARS, PPTX_BODY_MAX_LINES).forEachIndexed { part, lines ->
                slides += buildPptxTextSlideXml(
                    heading = if (part == 0) heading else "$PPTX_CONTINUATION_PREFIX$heading",
                    headingColor = if (isUser) "15803D" else "4338CA",
                    lines = lines,
                    textColor = if (isUser) "F0FDF4" else "F8FAFC",
                    fontSize = 1600,
                    monospace = false,
                    rtl = true
                )
            }
        }
        return slides
    }

    private fun buildPptxCodeSlidesXml(session: ChatSession): List<String> {
        val heading = "💻 قطعات کد و پیاده‌سازی (Code Artifacts)"
        val snippets = session.messages.flatMap { it.codeSnippets }
        if (snippets.isEmpty()) {
            return listOf(
                buildPptxTextSlideXml(
                    heading = heading,
                    headingColor = "10B981",
                    lines = listOf("کدهای استخراج‌شده در بخش مستندات فنی ضمیمه گردید."),
                    textColor = "38BDF8",
                    fontSize = 1500,
                    monospace = false,
                    rtl = true
                )
            )
        }
        val slides = mutableListOf<String>()
        snippets.forEach { snippet ->
            // The language rides in the slide heading so the body stays a byte-for-byte copy of the code.
            val slideHeading = if (snippet.language.isBlank()) heading else "$heading — ${snippet.language}"
            slideChunks(snippet.code, PPTX_CODE_MAX_CHARS, PPTX_CODE_MAX_LINES).forEachIndexed { part, lines ->
                slides += buildPptxTextSlideXml(
                    heading = if (part == 0) slideHeading else "$PPTX_CONTINUATION_PREFIX$slideHeading",
                    headingColor = "10B981",
                    lines = lines,
                    textColor = "38BDF8",
                    fontSize = 1500,
                    monospace = true,
                    rtl = false
                )
            }
        }
        return slides
    }

    /**
     * Breaks text at line boundaries into slide-sized chunks.
     *
     * Joining the emitted chunks back with newlines is an identity only when the text is short enough
     * that no `maxLines` cut happens; such a flush rejoins with an inserted newline of its own. A hard
     * split of a line wider than `maxChars` breaks the text for good as well. Measured over thirty-two
     * real calls the rejoin differed from the input in twenty-four of them with no line wider than the
     * budget, so the chunks are a rendering of the text and never a way to rebuild it.
     */
    private fun slideChunks(text: String, maxChars: Int, maxLines: Int): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var used = 0
        fun flush() {
            if (current.isNotEmpty()) {
                chunks += current.toList()
                current = mutableListOf()
                used = 0
            }
        }
        for (line in text.lines()) {
            if (line.length > maxChars) {
                flush()
                var start = 0
                while (start < line.length) {
                    val end = minOf(start + maxChars, line.length)
                    chunks += listOf(line.substring(start, end))
                    start = end
                }
                continue
            }
            if (used + line.length > maxChars || current.size >= maxLines) flush()
            current += line
            used += line.length + 1
        }
        flush()
        return chunks
    }

    private fun buildPptxTextSlideXml(
        heading: String,
        headingColor: String,
        lines: List<String>,
        textColor: String,
        fontSize: Int,
        monospace: Boolean,
        rtl: Boolean
    ): String {
        val rtlFlag = if (rtl) "1" else "0"
        val align = if (rtl) "r" else "l"
        val bodyLang = if (monospace) "en-US" else "fa-IR"
        // In CT_TextCharacterProperties `a:latin` follows the fill, so the faces are appended after solidFill.
        val faces = if (monospace) "<a:latin typeface=\"Consolas\"/><a:cs typeface=\"Consolas\"/>" else ""
        val body = buildString {
            lines.forEach { line ->
                if (line.isBlank()) {
                    appendLine("          <a:p/>")
                } else {
                    appendLine("""          <a:p><a:pPr algn="$align" rtl="$rtlFlag"/><a:r><a:rPr lang="$bodyLang" sz="$fontSize"><a:solidFill><a:srgbClr val="$textColor"/></a:solidFill>$faces</a:rPr><a:t xml:space="preserve">${escapeXml(line)}</a:t></a:r></a:p>""")
                }
            }
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="2" name="Heading"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="600000" y="430000"/><a:ext cx="7944000" cy="900000"/></a:xfrm></p:spPr>
        <p:txBody>
          <a:bodyPr rtlCol="$rtlFlag"/><a:lstStyle/>
          <a:p><a:pPr algn="$align" rtl="$rtlFlag"/><a:r><a:rPr lang="fa-IR" sz="2600" b="1"><a:solidFill><a:srgbClr val="$headingColor"/></a:solidFill></a:rPr><a:t>${escapeXml(heading)}</a:t></a:r></a:p>
        </p:txBody>
      </p:sp>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="600000" y="1430000"/><a:ext cx="7944000" cy="3200000"/></a:xfrm></p:spPr>
        <p:txBody>
          <a:bodyPr rtlCol="$rtlFlag"/><a:lstStyle/>
$body        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>"""
    }
}
