package com.example

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.ExportFormat
import com.example.data.model.MessageRole
import com.example.data.model.PlatformPreset
import com.example.data.model.TextDirection
import com.example.domain.exporter.DocumentExporter
import com.example.domain.exporter.ExportOptions
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PDF writer lays every paragraph on the side its own language asks for, so this measures it on the
 * pixels of the file the exporter really wrote: a Persian line has to reach the right margin and an
 * English line has to start at the left margin. No JVM test can draw a layout, so the claim about the
 * page can only be closed here, on a device, on a real render.
 *
 * The Persian fixture is written as code points, like the OOXML test, because the writers on this
 * machine corrupt literal Persian.
 */
@RunWith(AndroidJUnit4::class)
class PdfParagraphSideTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val exportRoot = File(targetContext.cacheDir, "pdf_side_exports")

    private val exportContext: Context = object : ContextWrapper(targetContext) {
        override fun getFilesDir(): File = exportRoot
    }

    private val publishedNames = mutableListOf<String>()

    @Before
    fun removeLeftoversOfAnAbortedRun() {
        exportRoot.deleteRecursively()
    }

    @After
    fun removeWhatThisTestWrote() {
        exportRoot.deleteRecursively()
        publishedNames.forEach { name ->
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
    fun aPersianLineReachesTheRightMarginAndAnEnglishLineStartsAtTheLeft() {
        val persianFile = exportPdf(PERSIAN, TextDirection.RTL)
        val englishFile = exportPdf(ENGLISH, TextDirection.LTR)

        val persianPage = renderFirstPage(persianFile)
        val englishPage = renderFirstPage(englishFile)
        val w = persianPage.width

        val persianBands = inkBands(persianPage)
        val englishBands = inkBands(englishPage)
        println("pdf-side widths=$w persianBands=$persianBands englishBands=$englishBands")

        val wideEnough = 0.15f * w
        val persianFlushRight = persianBands.any { it.right >= 0.92f * w && (it.right - it.left) >= wideEnough }
        val englishFlushLeft = englishBands.any { it.left <= 0.10f * w && (it.right - it.left) >= wideEnough }

        assertTrue(
            "no wide Persian line reached the right margin; page width=$w bands=$persianBands",
            persianFlushRight
        )
        assertTrue(
            "no wide English line started at the left margin; page width=$w bands=$englishBands",
            englishFlushLeft
        )
    }

    /** One real export through the real exporter, into this test's own folder. */
    private fun exportPdf(content: String, direction: TextDirection): File {
        val session = ChatSession(
            id = "pdf-side-${direction.name}",
            title = "side check",
            platform = PlatformPreset.CUSTOM,
            modelName = "gpt-4o",
            messages = listOf(
                ChatMessage(role = MessageRole.ASSISTANT, content = content, direction = direction)
            )
        )
        val record = DocumentExporter.exportDocument(
            exportContext,
            session,
            null,
            ExportOptions(format = ExportFormat.PDF)
        ).getOrThrow()
        publishedNames += record.fileName
        return File(record.filePath)
    }

    /** Page one of a PDF, drawn by the platform's own renderer at a readable width. */
    private fun renderFirstPage(file: File): Bitmap {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                val page = renderer.openPage(0)
                try {
                    val width = 1200
                    val height = (width.toFloat() * page.height / page.width).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                } finally {
                    page.close()
                }
            }
        }
    }

    private data class Band(val top: Int, val bottom: Int, val left: Int, val right: Int)

    /**
     * Every run of inked rows on the page, with the leftmost and rightmost dark pixel of that run.
     * The banner card and the code box are fills, not text, and their colour stays above the threshold,
     * so what is left is the text itself.
     */
    private fun inkBands(bitmap: Bitmap): List<Band> {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val bands = mutableListOf<Band>()
        var y = 0
        while (y < h) {
            if (firstDark(pixels, w, y) < 0) {
                y++
                continue
            }
            var lastInked = y
            var gap = 0
            var probe = y + 1
            while (probe < h && gap < 5) {
                if (firstDark(pixels, w, probe) < 0) gap++ else {
                    lastInked = probe
                    gap = 0
                }
                probe++
            }
            bands += Band(
                top = y,
                bottom = lastInked,
                left = firstDark(pixels, w, y),
                right = lastDark(pixels, w, lastInked)
            )
            y = lastInked + 1
        }
        return bands
    }

    private fun firstDark(pixels: IntArray, w: Int, y: Int): Int {
        var x = 0
        while (x < w) {
            if (isDark(pixels[y * w + x])) return x
            x++
        }
        return -1
    }

    private fun lastDark(pixels: IntArray, w: Int, y: Int): Int {
        var x = w - 1
        while (x >= 0) {
            if (isDark(pixels[y * w + x])) return x
            x--
        }
        return -1
    }

    private fun isDark(pixel: Int): Boolean =
        Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 480
}

private const val PERSIAN =
    "\u0633\u0644\u0627\u0645 \u0627\u06CC\u0646 \u06CC\u06A9 \u0645\u062A\u0646 \u0622\u0632\u0645\u0627\u06CC\u0634\u06CC \u0628\u0647 \u0632\u0628\u0627\u0646 \u0641\u0627\u0631\u0633\u06CC \u0627\u0633\u062A"

private const val ENGLISH = "Hello this is a test text in English language"