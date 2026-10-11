package com.example

import android.content.Context
import android.content.ContextWrapper
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.CodeSnippet
import com.example.data.model.ExportFormat
import com.example.data.model.ExportRecord
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import com.example.domain.exporter.DocumentExporter
import com.example.domain.exporter.ExportOptions
import com.example.domain.exporter.OpenXmlGenerator
import java.io.File
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves an export really lands as a readable file, not as bytes that merely look plausible on screen.
 *
 * Every write goes through `DocumentExporter.exportDocument`, then the produced file is checked as a
 * document: a PDF must carry the header and trailer a viewer needs, and a `.docx` / `.pptx` must open as
 * a ZIP holding exactly the parts its writer claims to write.
 *
 * The exporter is handed a context whose `filesDir` is pointed at this test's own folder inside the
 * instrumentation package's cache, so nothing here can reach the app's real `files/documents` directory
 * where the owner's own exports live.
 */
@RunWith(AndroidJUnit4::class)
class ExportFileProofTest {

    /** The instrumentation process runs under the app's uid, so only the target's own dirs are writable. */
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    /** This test's own output folder: created by the exporter, deleted in [removeWhatThisTestWrote]. */
    private val exportRoot = File(targetContext.cacheDir, "w9_exports")

    private val exportContext: Context = object : ContextWrapper(targetContext) {
        override fun getFilesDir(): File = exportRoot
    }

    /** Names the exporter published into the shared Download folder, so the copies are taken back out. */
    private val publishedNames = mutableListOf<String>()

    @Before
    fun removeLeftoversOfAnAbortedRun() {
        exportRoot.deleteRecursively()
    }

    @After
    fun removeWhatThisTestWrote() {
        exportRoot.deleteRecursively()
        publishedNames.forEach { name ->
            // Both clauses are kept deliberately: only a row this instrumentation package published,
            // under exactly the name it gave it. Anything else in Download is not ours to touch.
            runCatching {
                targetContext.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                    arrayOf(name, targetContext.packageName)
                )
            }
        }
        publishedNames.clear()
    }

    @Test
    fun pdfExportLandsAsARealPdfFile() {
        val record = runExport(ExportFormat.PDF)
        val file = assertLandedOnDisk(record, ExportFormat.PDF, MIN_PDF_BYTES)
        val bytes = file.readBytes()

        assertTrue(
            "a $MIN_PDF_BYTES byte PDF window is needed for the header and trailer checks",
            bytes.size > PDF_HEADER.length + TRAILER_WINDOW
        )
        assertEquals(
            "the file does not start as a PDF, so no viewer will open it",
            PDF_HEADER,
            String(bytes, 0, PDF_HEADER.length, Charsets.ISO_8859_1)
        )
        val tail = String(bytes, bytes.size - TRAILER_WINDOW, TRAILER_WINDOW, Charsets.ISO_8859_1)
        assertTrue(
            "the PDF has no end-of-file marker, which means it was cut off mid-write: tail was '$tail'",
            tail.trimEnd { it.isWhitespace() }.endsWith(EOF_MARKER)
        )
    }

    @Test
    fun docxExportLandsAsThePackageItsWriterDeclares() {
        val record = runExport(ExportFormat.DOCX)
        val file = assertLandedOnDisk(record, ExportFormat.DOCX, MIN_OOXML_BYTES)
        val parts = readZipParts(file)

        assertEquals(
            "the .docx part list drifted from what OpenXmlGenerator.generateRealDocx writes",
            DOCX_PARTS,
            parts.keys
        )
        // A Word package is opened through the relationships and the content types, so those strings have
        // to agree with the parts that are really in the ZIP.
        assertTrue("_rels/.rels does not point at the main part", parts.getValue(ROOT_RELS).contains(DOCX_MAIN_PART))
        assertTrue(
            "[Content_Types].xml does not declare the main document part",
            parts.getValue(CONTENT_TYPES).contains(DOCX_MAIN_OVERRIDE)
        )
        assertTrue(
            "[Content_Types].xml does not declare the styles part",
            parts.getValue(CONTENT_TYPES).contains(DOCX_STYLES_OVERRIDE)
        )

        val document = parts.getValue(DOCX_MAIN_PART)
        assertTrue("the title never reached word/document.xml", document.contains(TITLE))
        assertTrue(
            "a message body line never reached word/document.xml",
            document.contains(USER_TURN_MARKER)
        )
        assertTrue(
            "the code block never reached word/document.xml, although includeCodeBlocks defaults to on",
            document.contains(CODE_MARKER)
        )
    }

    @Test
    fun pptxExportWritesExactlyOneSlidePerEstimatedSlide() {
        val options = ExportOptions(format = ExportFormat.PPTX)
        val record = runExport(ExportFormat.PPTX, options)
        val file = assertLandedOnDisk(record, ExportFormat.PPTX, MIN_OOXML_BYTES)
        val parts = readZipParts(file)

        val estimated = OpenXmlGenerator.estimatePptxSlideCount(fixtureSession(), DISTILLATION, options)
        val slideNames = parts.keys.filter { it.startsWith(PPTX_SLIDE_DIR) }
        val expectedSlides = (1..estimated).map { "$PPTX_SLIDE_DIR$it.xml" }.toSet()
        assertEquals(
            "the deck holds $slideNames but the on-screen estimate promised $estimated slides",
            expectedSlides,
            slideNames.toSet()
        )
        assertEquals(
            "the deck's part list drifted from what OpenXmlGenerator.generateRealPptx writes",
            pptxParts(estimated),
            parts.keys
        )

        // The three lists a viewer reads have to be the same length as the slides that exist, or the
        // package is either unreachable or points at parts that were never written.
        assertEquals(
            "one content-type override per slide",
            estimated,
            parts.getValue(CONTENT_TYPES).lines().count { it.contains("PartName=\"/$PPTX_SLIDE_DIR") }
        )
        assertEquals(
            "one slide id per slide in ppt/presentation.xml",
            estimated,
            parts.getValue(PPTX_PRESENTATION).lines().count { it.contains("<p:sldId id=") }
        )
        assertEquals(
            "one slide relationship per slide in ppt/_rels/presentation.xml.rels",
            estimated,
            parts.getValue(PPTX_PRESENTATION_RELS).lines().count { it.contains("Target=\"slides/slide") }
        )
        assertTrue(
            "the title slide does not carry the conversation title",
            parts.getValue(PPTX_SLIDE_DIR + "1.xml").contains(TITLE)
        )
    }

    /** Runs one real export and returns the record it produced. */
    private fun runExport(format: ExportFormat, options: ExportOptions = ExportOptions(format = format)): ExportRecord {
        val record = DocumentExporter.exportDocument(
            exportContext,
            fixtureSession(),
            DISTILLATION,
            options
        ).getOrThrow()
        publishedNames += record.fileName
        return record
    }

    /**
     * The file the record points at must exist, be this test's own, hold more than a stub, and describe
     * itself with the same size string the archive history shows the user.
     */
    private fun assertLandedOnDisk(record: ExportRecord, requested: ExportFormat, minimumBytes: Long): File {
        val file = File(record.filePath)
        assertTrue("exportDocument returned a path with no file behind it: ${record.filePath}", file.exists())
        assertTrue("the produced file is not a regular file: ${record.filePath}", file.isFile)
        assertTrue(
            "the export wrote outside this test's own folder (${exportRoot.absolutePath}): ${file.absolutePath}",
            file.canonicalPath.startsWith(exportRoot.canonicalPath)
        )
        assertTrue(
            "a $minimumBytes byte floor is the smallest file that can hold this fixture's header banner, " +
                "four turns and one code block; got ${file.length()}",
            file.length() >= minimumBytes
        )
        assertEquals("the record keeps the format that was asked for", requested, record.format)
        assertEquals(
            "the file on disk does not carry the extension of the format that was asked for",
            requested.extension,
            file.extension
        )
        assertEquals("the record carries the session title", TITLE, record.sessionTitle)
        assertEquals("the record carries the session id", SESSION_ID, record.sessionId)
        assertSizeStringMatchesTheFile(record, file)
        return file
    }

    /**
     * The size string is checked against the file's real length without calling the exporter's own
     * formatter, so a stale or floored string ("0 KB" for a 4 KiB file) is still caught. One unit step of
     * slack is allowed because the app floors a byte count to whole KB and rounds MB to one decimal.
     */
    private fun assertSizeStringMatchesTheFile(record: ExportRecord, file: File) {
        val shown = record.fileSizeFormatted
        val note = "fileSizeFormatted '$shown' for a ${file.length()} byte file"
        val pieces = shown.trim().split(" ")
        assertEquals("$note is not '<number> <unit>'", 2, pieces.size)
        val value = pieces[0].toDoubleOrNull()
        assertNotNull("$note has no readable number", value)
        val step: Double = when (val unit = pieces[1]) {
            "B" -> 1.0
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024.0
            // fail() throws, so the 0.0 is only there to keep this an expression.
            else -> {
                fail("$note uses a unit the app never prints: '$unit'")
                0.0
            }
        }
        val reportedBytes = (value!! * step).toLong()
        assertTrue(
            "$note is off by more than one unit of ${pieces[1]}",
            kotlin.math.abs(file.length() - reportedBytes) < step.toLong()
        )
        assertTrue("$note still reads as an empty file to the user", reportedBytes > 0)
    }

    private fun readZipParts(file: File): Map<String, String> =
        ZipFile(file).use { zip ->
            val parts = mutableMapOf<String, String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    parts[entry.name] = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                }
            }
            parts
        }

    private fun pptxParts(slideCount: Int): Set<String> = buildSet {
        add(CONTENT_TYPES)
        add(ROOT_RELS)
        add(PPTX_PRESENTATION)
        add(PPTX_PRESENTATION_RELS)
        for (index in 1..slideCount) add("$PPTX_SLIDE_DIR$index.xml")
    }

    /** Four turns and one code block: enough for a multi-page document, small enough to export fast. */
    private fun fixtureSession() = ChatSession(
        id = SESSION_ID,
        title = TITLE,
        platform = PlatformPreset.CHATGPT,
        modelName = "gpt-4o-2024-08-06",
        createdAt = 1_760_000_000_000L,
        updatedAt = 1_760_000_900_000L,
        tags = listOf("w9"),
        messages = listOf(
            ChatMessage(
                id = "w9-export-turn-1",
                role = MessageRole.USER,
                content = "$USER_TURN_MARKER\nAnd a second line of the same question?",
                timestamp = 1_760_000_100_000L,
                modelTag = null,
                direction = TextDirection.LTR,
                reasoning = null,
                codeSnippets = emptyList()
            ),
            ChatMessage(
                id = "w9-export-turn-2",
                role = MessageRole.ASSISTANT,
                content = "The exporter writes a document per format.",
                timestamp = 1_760_000_200_000L,
                modelTag = "gpt-4o-2024-08-06",
                direction = TextDirection.MIXED,
                reasoning = "listed the formats first",
                codeSnippets = listOf(
                    CodeSnippet(
                        id = "w9-export-snippet-1",
                        language = "kotlin",
                        code = "$CODE_MARKER(name: String) {\n    println(name)\n}"
                    )
                )
            ),
            ChatMessage(
                id = "w9-export-turn-3",
                role = MessageRole.USER,
                content = "Does it keep the code block?",
                timestamp = 1_760_000_300_000L,
                modelTag = null,
                direction = TextDirection.LTR,
                reasoning = null,
                codeSnippets = emptyList()
            ),
            ChatMessage(
                id = "w9-export-turn-4",
                role = MessageRole.ASSISTANT,
                content = "Yes, when the code toggle is on.",
                timestamp = 1_760_000_400_000L,
                modelTag = "gpt-4o-2024-08-06",
                direction = TextDirection.MIXED,
                reasoning = null,
                codeSnippets = emptyList()
            )
        )
    )

    private companion object {
        /** A title the exporter's file-name sanitizer leaves intact: ASCII letters and digits only. */
        const val SESSION_ID = "w9-export-proof"
        const val TITLE = "W9ExportProof"
        const val USER_TURN_MARKER = "Documents come back out of the file"
        const val CODE_MARKER = "fun greet"
        const val DISTILLATION = "Summary: the session was exported for the persistence proof.\nSecond line of the summary."

        const val MIN_PDF_BYTES = 1024L
        const val MIN_OOXML_BYTES = 1024L

        const val PDF_HEADER = "%PDF-"
        const val EOF_MARKER = "%%EOF"
        const val TRAILER_WINDOW = 64

        const val CONTENT_TYPES = "[Content_Types].xml"
        const val ROOT_RELS = "_rels/.rels"
        const val DOCX_MAIN_PART = "word/document.xml"
        const val DOCX_MAIN_OVERRIDE = "/word/document.xml"
        const val DOCX_STYLES_OVERRIDE = "/word/styles.xml"
        const val PPTX_PRESENTATION = "ppt/presentation.xml"
        const val PPTX_PRESENTATION_RELS = "ppt/_rels/presentation.xml.rels"
        const val PPTX_SLIDE_DIR = "ppt/slides/slide"

        // The five parts of OpenXmlGenerator.generateRealDocx, read off its writeZipEntry calls.
        val DOCX_PARTS = setOf(
            CONTENT_TYPES,
            ROOT_RELS,
            "word/_rels/document.xml.rels",
            "word/styles.xml",
            DOCX_MAIN_PART
        )
    }
}
