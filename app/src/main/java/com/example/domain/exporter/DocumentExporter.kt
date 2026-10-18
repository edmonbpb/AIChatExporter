package com.example.domain.exporter

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.model.ChatSession
import com.example.data.model.ExportFormat
import com.example.data.model.ExportRecord
import com.example.data.model.MessageRole
import com.example.data.model.TextDirection
import com.example.domain.bidi.AlignSide
import com.example.domain.bidi.BidiEngine
import com.example.domain.bidi.paragraphSide
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ExportOptions(
    val format: ExportFormat = ExportFormat.DOCX,
    val includeMetadataTable: Boolean = true,
    val includeExecutiveSummary: Boolean = true,
    val includeCodeBlocks: Boolean = true,
    val convertToPersianDigits: Boolean = true,
    // PPTX only, and false by default: turning it on leaves the per-turn slides out of the deck, so the
    // whole conversation stays in it unless the user asks for the shorter one. No ceiling, nothing clipped.
    val pptxSummaryAndCodeOnly: Boolean = false,
    val customTitle: String? = null
)

object DocumentExporter {

    fun exportDocument(
        context: Context,
        session: ChatSession,
        distillationContent: String? = null,
        options: ExportOptions = ExportOptions()
    ): Result<ExportRecord> {
        return try {
            val docsDir = File(context.filesDir, "documents")
            if (!docsDir.exists()) docsDir.mkdirs()

            val timestamp = exportTimestamp()
            val fileName = exportFileName(session, options, timestamp)
            val targetFile = File(docsDir, fileName)

            when (options.format) {
                ExportFormat.DOCX -> OpenXmlGenerator.generateRealDocx(targetFile, session, distillationContent, options)
                ExportFormat.PDF -> generatePdfDocument(targetFile, session, distillationContent, options)
                ExportFormat.PPTX -> OpenXmlGenerator.generateRealPptx(targetFile, session, distillationContent, options)
                ExportFormat.MARKDOWN -> generateMarkdown(targetFile, session, distillationContent, options)
                ExportFormat.HTML -> generateHtml(targetFile, session, distillationContent, options)
                ExportFormat.TXT -> generatePlainText(targetFile, session, distillationContent, options)
                ExportFormat.JSON -> generateJson(targetFile, session, distillationContent, options)
            }

            val fileSize = formatFileSize(targetFile.length())
            val publish = DownloadsPublisher.publish(context, targetFile, fileName, options.format.mimeType)
            val downloadNote = when (publish) {
                is DownloadsPublisher.Outcome.Published ->
                    "رونوشت در پوشهٔ Download ذخیره شد: ${publish.displayName}"
                is DownloadsPublisher.Outcome.Failed ->
                    "فایل اصلی ذخیره شد؛ فقط رونوشت در Download ذخیره نشد (${publish.reason})"
            }
            val record = ExportRecord(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                sessionTitle = options.customTitle ?: session.title,
                fileName = fileName,
                format = options.format,
                filePath = targetFile.absolutePath,
                fileSizeFormatted = fileSize,
                exportedAt = System.currentTimeMillis(),
                downloadNote = downloadNote
            )

            Result.success(record)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The one timestamp pattern an export file name is stamped with. */
    private const val EXPORT_TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

    /** The stamp [exportDocument] puts in the file name at this moment. */
    internal fun exportTimestamp(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat(EXPORT_TIMESTAMP_PATTERN, Locale.US).format(Date(now))

    /**
     * The name [exportDocument] gives the file it writes. Built in one place so the studio preview
     * can show that exact name instead of a second guess at it.
     */
    internal fun exportFileName(session: ChatSession, options: ExportOptions, timestamp: String): String {
        val safeTitle = (options.customTitle ?: session.title)
            .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF_-]"), "_")
            .take(30)
        return "AIChat_${safeTitle}_$timestamp.${options.format.extension}"
    }

    /**
     * A count the reader sees, in the script [options] asks for. This is the HTML path's own
     * `shown`/`num` pair lifted to the object level, so PDF, Markdown and plain text localise a
     * number the way HTML already did instead of each path retyping the rule for itself.
     */
    internal fun shownNumber(value: Int, options: ExportOptions): String =
        if (options.convertToPersianDigits) BidiEngine.toPersianDigits(value.toString()) else value.toString()

    /** [shownNumber] for a whole string, such as a formatted date. */
    private fun shownText(value: String, options: ExportOptions): String =
        if (options.convertToPersianDigits) BidiEngine.toPersianDigits(value) else value

    /**
     * The first [maxLines] lines of the output this export would really write, so the studio preview
     * cannot drift from the file that lands on disk. `null` when the format has no text form to
     * read: `.docx` and `.pptx` are ZIP packages whose text is assembled inside `OpenXmlGenerator`,
     * and a PDF is drawn on a canvas, so neither is reachable as a string from here.
     */
    internal fun previewLines(
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions,
        maxLines: Int
    ): List<String>? {
        if (maxLines <= 0) return emptyList()
        val text = when (options.format) {
            ExportFormat.MARKDOWN -> buildMarkdownText(session, distillationContent, options)
            ExportFormat.HTML -> buildHtmlText(session, distillationContent, options)
            ExportFormat.TXT -> buildPlainText(session, distillationContent, options)
            ExportFormat.JSON -> buildJsonText(session, distillationContent, options)
            ExportFormat.PDF -> pdfHeaderLines(session, options).joinToString("\n")
            ExportFormat.DOCX, ExportFormat.PPTX -> return null
        }
        return text.lineSequence().take(maxLines).toList()
    }

    /**
     * The two lines the PDF draws at the top of every page, kept as text so the page and the studio
     * preview print the same string and the message count cannot arrive as a Latin digit.
     */
    internal fun pdfHeaderLines(session: ChatSession, options: ExportOptions): List<String> = listOf(
        options.customTitle ?: session.title,
        "AIChatExporter | پلتفرم: ${session.platform.displayName} | مدل: ${session.modelName} | تعداد پیام‌ها: ${shownNumber(session.messages.size, options)}"
    )

    /**
     * Generates a clean, standard A4 PDF Document with Page Numbering and Canvas Styling.
     */
    private fun generatePdfDocument(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 standard width (pt)
        val pageHeight = 842 // A4 standard height (pt)
        val margin = 36f

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        var yPos = margin + 20f

        val titlePaint = Paint().apply {
            color = Color.rgb(30, 27, 75)
            textSize = 18f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val subPaint = Paint().apply {
            color = Color.rgb(99, 102, 241)
            textSize = 10f
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.rgb(30, 41, 59)
            textSize = 10f
            isAntiAlias = true
        }
        val headerCardPaint = Paint().apply {
            color = Color.rgb(241, 245, 249)
            style = Paint.Style.FILL
        }
        val codeBoxPaint = Paint().apply {
            color = Color.rgb(15, 23, 42)
            style = Paint.Style.FILL
        }
        val codeTextPaint = Paint().apply {
            color = Color.rgb(56, 189, 248)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        /**
         * A page number is a number the reader sees, so the footer runs it through the same digit
         * rule as every other count printed by this exporter.
         */
        fun pageFooterText(): String = shownText("صفحه $pageNumber", options)

        // Built once, outside the page loop, so every page prints the same two strings the studio
        // preview shows for this format.
        val headerLines = pdfHeaderLines(session, options)

        fun drawHeader() {
            // Draw Header Banner
            val headerRect = RectF(margin, yPos, pageWidth - margin, yPos + 60f)
            canvas.drawRoundRect(headerRect, 8f, 8f, headerCardPaint)

            canvas.drawText(headerLines[0], margin + 14f, yPos + 26f, titlePaint)
            canvas.drawText(headerLines[1], margin + 14f, yPos + 46f, subPaint)
            yPos += 75f
        }

        fun checkNewPage(neededHeight: Float) {
            if (yPos + neededHeight > pageHeight - margin - 25f) {
                // Footer page number
                val footerPaint = Paint().apply {
                    color = Color.GRAY
                    textSize = 9f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(pageFooterText(), pageWidth / 2f, pageHeight - 20f, footerPaint)

                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                yPos = margin + 20f
                drawHeader()
            }
        }

        fun wrapLine(text: String, paint: Paint, maxWidth: Float, breakOnWords: Boolean): List<String> {
            if (text.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val count = paint.breakText(text, start, text.length, true, maxWidth, null)
                if (count <= 0) {
                    result.add(text.substring(start))
                    break
                }
                var end = start + count
                if (breakOnWords && end < text.length) {
                    val lastSpace = text.lastIndexOf(' ', end - 1)
                    if (lastSpace > start) end = lastSpace
                }
                val piece = text.substring(start, end).trimEnd()
                if (piece.isNotEmpty()) result.add(piece)
                start = end
                while (start < text.length && text[start] == ' ') start++
            }
            return result
        }

        drawHeader()

        // Draw Summary if present
        if (!distillationContent.isNullOrBlank()) {
            checkNewPage(40f)
            val secPaint = Paint().apply {
                color = Color.rgb(79, 70, 229)
                textSize = 13f
                isFakeBoldText = true
            }
            canvas.drawText("📊 خلاصه و پالایش هوشمند:", margin, yPos, secPaint)
            yPos += 18f

            val summaryWidth = pageWidth - 2 * margin - 8f
            for (line in distillationContent.lines()) {
                for (piece in wrapLine(line, bodyPaint, summaryWidth, true)) {
                    checkNewPage(16f)
                    canvas.drawText(piece, margin + 8f, yPos, bodyPaint)
                    yPos += 14f
                }
            }
            yPos += 10f
        }

        // Draw Messages
        val secPaint = Paint().apply {
            color = Color.rgb(79, 70, 229)
            textSize = 13f
            isFakeBoldText = true
        }
        checkNewPage(30f)
        canvas.drawText("💬 متن مکالمه:", margin, yPos, secPaint)
        yPos += 18f

        session.messages.forEachIndexed { idx, msg ->
            checkNewPage(35f)
            val isUser = msg.role == MessageRole.USER
            val rolePaint = Paint().apply {
                color = if (isUser) Color.rgb(22, 163, 74) else Color.rgb(79, 70, 229)
                textSize = 10.5f
                isFakeBoldText = true
            }
            val sender = if (isUser) "👤 کاربر" else "🤖 ${msg.modelTag ?: session.modelName}"
            val turnNumber = shownNumber(idx + 1, options)
            canvas.drawText("#$turnNumber $sender", margin, yPos, rolePaint)
            yPos += 14f

            val bodyWidth = pageWidth - 2 * margin - 10f
            for (line in msg.content.lines()) {
                for (piece in wrapLine(line, bodyPaint, bodyWidth, true)) {
                    checkNewPage(16f)
                    canvas.drawText(piece, margin + 10f, yPos, bodyPaint)
                    yPos += 14f
                }
            }

            if (options.includeCodeBlocks && msg.codeSnippets.isNotEmpty()) {
                val codeWidth = pageWidth - 2 * margin - 28f
                msg.codeSnippets.forEach { snippet ->
                    val codeLines = snippet.code.lines().flatMap { wrapLine(it, codeTextPaint, codeWidth, false) }
                    var drawn = 0
                    while (drawn < codeLines.size) {
                        val room = pageHeight - margin - 25f - yPos - 26f
                        val chunk = codeLines.subList(drawn, (drawn + maxOf(1, (room / 12f).toInt())).coerceAtMost(codeLines.size))
                        val boxHeight = chunk.size * 12f + 16f
                        checkNewPage(boxHeight + 10f)

                        val codeRect = RectF(margin + 10f, yPos, pageWidth - margin - 10f, yPos + boxHeight)
                        canvas.drawRoundRect(codeRect, 6f, 6f, codeBoxPaint)

                        var codeY = yPos + 14f
                        for (cLine in chunk) {
                            canvas.drawText(cLine, margin + 18f, codeY, codeTextPaint)
                            codeY += 12f
                        }
                        yPos += boxHeight + 10f
                        drawn += chunk.size
                    }
                }
            }
            yPos += 8f
        }

        // Footer page number for last page
        val footerPaint = Paint().apply {
            color = Color.GRAY
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(pageFooterText(), pageWidth / 2f, pageHeight - 20f, footerPaint)
        pdfDocument.finishPage(page)

        FileOutputStream(file).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
    }

    /**
     * Generates Clean GitHub-Flavored Markdown.
     */
    private fun generateMarkdown(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val md = buildMarkdownText(session, distillationContent, options)

        FileOutputStream(file).use { out ->
            out.write(md.toByteArray(Charsets.UTF_8))
        }
    }

    /** The Markdown the exporter writes, kept as a string so the studio preview shows the real head of it. */
    internal fun buildMarkdownText(session: ChatSession, distillationContent: String?, options: ExportOptions): String {
        val title = options.customTitle ?: session.title
        val rawDateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(session.createdAt))
        val dateStr = shownText(rawDateStr, options)

        return buildString {
            appendLine("---")
            appendLine("title: \"$title\"")
            appendLine("platform: \"${session.platform.displayName}\"")
            appendLine("model: \"${session.modelName}\"")
            appendLine("date: \"$dateStr\"")
            appendLine("messages_count: ${session.messages.size}")
            appendLine("tags: [${session.tags.joinToString { "\"$it\"" }}]")
            appendLine("exported_by: \"AIChatExporter\"")
            appendLine("---")
            appendLine()
            appendLine("# $title")
            appendLine("> **پلتفرم:** ${session.platform.displayName} | **مدل:** ${session.modelName} | **تاریخ:** $dateStr")
            appendLine()

            if (!distillationContent.isNullOrBlank()) {
                appendLine("## 📊 پالایش هوشمند و خلاصه")
                appendLine(distillationContent)
                appendLine("\n---\n")
            }

            appendLine("## 💬 متن کامل گفتگو")
            appendLine()

            session.messages.forEachIndexed { idx, msg ->
                val sender = if (msg.role == MessageRole.USER) "### 👤 کاربر" else "### 🤖 ${msg.modelTag ?: session.modelName}"
                appendLine("$sender (#${shownNumber(idx + 1, options)}):")
                appendLine()
                if (!msg.reasoning.isNullOrBlank()) {
                    appendLine("<details><summary>🧠 تفکر و استدلال مدل</summary>\n\n${msg.reasoning}\n\n</details>\n")
                }
                appendLine(msg.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    /**
     * Generates one self-contained HTML file: embedded CSS, right-to-left layout, and both a light
     * and a dark scheme so it reads well wherever it is opened.
     */
    private fun generateHtml(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val html = buildHtmlText(session, distillationContent, options)

        FileOutputStream(file).use { out ->
            out.write(html.toByteArray(Charsets.UTF_8))
        }
    }

    /** The HTML the exporter writes, kept as a string so the studio preview shows the real head of it. */
    internal fun buildHtmlText(session: ChatSession, distillationContent: String?, options: ExportOptions): String {
        val title = options.customTitle ?: session.title
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val shown: (String) -> String = { value -> shownText(value, options) }
        fun num(value: Int) = shownNumber(value, options)
        val createdDate = shown(dateFormat.format(Date(session.createdAt)))
        val exportedDate = shown(dateFormat.format(Date()))

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"fa\" dir=\"rtl\">")
            appendLine("<head>")
            appendLine("<meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            appendLine("<title>${escapeHtml(title)}</title>")
            appendLine("<style>")
            appendLine(HTML_STYLE)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<header>")
            appendLine("<h1>${escapeHtml(title)}</h1>")
            appendLine("<p class=\"sub\">${escapeHtml(session.platform.displayName)} · ${escapeHtml(session.modelName)} · $createdDate</p>")
            if (options.includeMetadataTable) {
                appendLine("<table class=\"meta\">")
                appendLine("<tr><th>پلتفرم</th><td>${escapeHtml(session.platform.displayName)}</td></tr>")
                appendLine("<tr><th>مدل</th><td>${escapeHtml(session.modelName)}</td></tr>")
                appendLine("<tr><th>تاریخ شروع</th><td>$createdDate</td></tr>")
                appendLine("<tr><th>تعداد پیام‌ها</th><td>${num(session.messages.size)}</td></tr>")
                appendLine("<tr><th>تعداد کلمات</th><td>${num(session.totalWords)}</td></tr>")
                appendLine("<tr><th>توکن (تخمینی)</th><td>${num(session.tokenEstimate)}</td></tr>")
                appendLine("<tr><th>برچسب‌ها</th><td>${escapeHtml(session.tags.joinToString("، ")).ifBlank { "—" }}</td></tr>")
                appendLine("</table>")
            }
            appendLine("</header>")

            if (!distillationContent.isNullOrBlank()) {
                appendLine("<section class=\"summary\">")
                appendLine("<h2>📊 پالایش هوشمند و خلاصه</h2>")
                appendLine(htmlParagraphs(distillationContent, BidiEngine.detectDirection(distillationContent)))
                appendLine("</section>")
            }

            appendLine("<main>")
            session.messages.forEachIndexed { idx, msg ->
                val isUser = msg.role == MessageRole.USER
                val sender = if (isUser) "👤 کاربر" else "🤖 ${msg.modelTag ?: session.modelName}"
                appendLine("<article class=\"${if (isUser) "user" else "ai"}\">")
                appendLine("<h3>${escapeHtml(sender)} <span class=\"num\">#${num(idx + 1)}</span></h3>")
                if (!msg.reasoning.isNullOrBlank()) {
                    appendLine("<details><summary>🧠 تفکر و استدلال مدل</summary><pre>${escapeHtml(msg.reasoning)}</pre></details>")
                }
                appendLine(htmlParagraphs(msg.content, msg.direction))
                if (options.includeCodeBlocks) {
                    msg.codeSnippets.forEach { snippet ->
                        appendLine("<figure dir=\"ltr\">")
                        appendLine("<figcaption>${escapeHtml(snippet.language.ifBlank { "code" })}</figcaption>")
                        appendLine("<pre><code>${escapeHtml(snippet.code)}</code></pre>")
                        appendLine("</figure>")
                    }
                }
                appendLine("</article>")
            }
            appendLine("</main>")
            appendLine("<footer>ساخته‌شده با AIChatExporter · $exportedDate</footer>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    internal fun htmlParagraphs(text: String, direction: TextDirection): String =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                val dir = if (paragraphSide(direction, line) == AlignSide.RIGHT) "rtl" else "ltr"
                "<p dir=\"$dir\">${escapeHtml(line.trim())}</p>"
            }

    internal fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * Generates Normalized Plain Text.
     */
    private fun generatePlainText(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val txt = buildPlainText(session, distillationContent, options)

        FileOutputStream(file).use { out ->
            out.write(txt.toByteArray(Charsets.UTF_8))
        }
    }

    /** The plain text the exporter writes, kept as a string so the studio preview shows the real head of it. */
    internal fun buildPlainText(session: ChatSession, distillationContent: String?, options: ExportOptions): String {
        val title = options.customTitle ?: session.title
        return buildString {
            appendLine("==================================================")
            appendLine(title)
            appendLine("پلتفرم: ${session.platform.displayName} | مدل: ${session.modelName}")
            appendLine("==================================================")
            appendLine()

            if (!distillationContent.isNullOrBlank()) {
                appendLine("[ خلاصه و پالایش هوشمند ]")
                appendLine(distillationContent)
                appendLine()
                appendLine("--------------------------------------------------")
                appendLine()
            }

            session.messages.forEachIndexed { idx, msg ->
                val sender = if (msg.role == MessageRole.USER) "کاربر" else (msg.modelTag ?: session.modelName)
                appendLine("[$sender (#${shownNumber(idx + 1, options)})]")
                appendLine(msg.content)
                appendLine()
                appendLine("--------------------------------------------------")
                appendLine()
            }
        }
    }

    /**
     * Generates Structured JSON.
     */
    private fun generateJson(
        file: File,
        session: ChatSession,
        distillationContent: String?,
        options: ExportOptions
    ) {
        val json = buildJsonText(session, distillationContent, options)

        FileOutputStream(file).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    }

    /** The JSON the exporter writes, as text, so the studio preview shows the real head of it. */
    internal fun buildJsonText(session: ChatSession, distillationContent: String?, options: ExportOptions): String {
        val root = JSONObject().apply {
            put("id", session.id)
            put("title", options.customTitle ?: session.title)
            put("platform", session.platform.id)
            put("modelName", session.modelName)
            put("createdAt", session.createdAt)
            put("totalWords", session.totalWords)
            put("tokenEstimate", session.tokenEstimate)

            val tagsArray = JSONArray()
            session.tags.forEach { tagsArray.put(it) }
            put("tags", tagsArray)

            distillationContent?.let { put("distillation", it) }

            val messagesArray = JSONArray()
            session.messages.forEach { msg ->
                val msgObj = JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role.name.lowercase())
                    put("direction", msg.direction.name.lowercase())
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    msg.modelTag?.let { put("modelTag", it) }
                    msg.reasoning?.let { put("reasoning", it) }

                    val codeArray = JSONArray()
                    msg.codeSnippets.forEach { c ->
                        codeArray.put(JSONObject().apply {
                            put("language", c.language)
                            put("code", c.code)
                        })
                    }
                    put("codeSnippets", codeArray)
                }
                messagesArray.put(msgObj)
            }
            put("messages", messagesArray)
        }
        return root.toString(2)
    }

    /** The app's one size string, shared with the archive record; units stay Latin because the digit script is decided at render time. */
    internal fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    // The library card's name box at 1080x1920 fits about this many monospace characters at bodySmall.
    internal const val CARD_NAME_BUDGET = 24

    /**
     * Middle ellipsis done by hand on the string, for the library card's name line. The part that tells two
     * exports apart is the tail - the timestamp and the extension - and Compose's `TextOverflow.Ellipsis`
     * cuts exactly that off; the real fix is `MiddleEllipsis`, which needs ui-text 1.10 while this app
     * resolves 1.7.0. `budget` is how many characters the box fits; names written here keep their extension
     * inside the last 20 characters, which is why the tail is cut at that size.
     */
    internal fun middleEllipsize(name: String, budget: Int): String {
        if (name.length <= budget) return name
        // Under 8 there is room for neither the cue nor a readable tail, so the head is shown as is.
        if (budget < 8) return name.take(budget)
        if (budget <= 21) return "…" + name.takeLast(budget - 1)
        return name.take(budget - 21) + "…" + name.takeLast(20)
    }

    private const val HTML_STYLE = """
:root { color-scheme: light dark; }
body { font-family: "Vazirmatn", "Segoe UI", Tahoma, sans-serif; margin: 0; padding: 24px; background: #f8fafc; color: #0f172a; line-height: 1.85; }
header h1 { margin: 0 0 6px; font-size: 24px; }
.sub { margin: 0 0 16px; color: #64748b; font-size: 13px; }
table.meta { border-collapse: collapse; width: 100%; max-width: 720px; font-size: 13px; }
table.meta th, table.meta td { border: 1px solid #cbd5e1; padding: 6px 10px; text-align: right; }
table.meta th { background: #e2e8f0; width: 34%; }
section.summary { background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 12px; padding: 14px 18px; margin: 22px 0; max-width: 860px; }
section.summary h2 { margin: 0 0 8px; font-size: 17px; }
main { max-width: 860px; }
article { border: 1px solid #cbd5e1; border-inline-start-width: 4px; border-radius: 12px; padding: 14px 18px; margin: 0 0 14px; background: #ffffff; }
article.user { border-inline-start-color: #16a34a; }
article.ai { border-inline-start-color: #4f46e5; }
article h3 { margin: 0 0 8px; font-size: 15px; }
.num { color: #94a3b8; font-size: 12px; font-weight: 400; }
p { margin: 0 0 8px; }
figure { margin: 10px 0; }
figcaption { font-size: 12px; color: #64748b; margin-bottom: 4px; }
pre { direction: ltr; text-align: left; background: #0f172a; color: #e2e8f0; padding: 12px; border-radius: 10px; overflow-x: auto; font-size: 13px; margin: 0; }
details { margin: 0 0 10px; font-size: 13px; color: #475569; }
footer { margin-top: 26px; color: #64748b; font-size: 12px; }
@media (prefers-color-scheme: dark) {
  body { background: #0b0f19; color: #e2e8f0; }
  article { background: #0f172a; border-color: #334155; }
  table.meta th, table.meta td { border-color: #334155; }
  table.meta th { background: #1e293b; }
  section.summary { background: #111827; border-color: #3730a3; }
  .sub, figcaption, footer, .num, details { color: #94a3b8; }
}
"""
}
