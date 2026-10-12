package com.example

import com.example.data.model.ExportFormat
import com.example.domain.exporter.DocumentExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Register row B-27: the library card printed the head of an export name, and the head is the part every
 * export of one session shares ("AIChat_Synthetic_Digits..."). Compose cuts from the end, so the tail that
 * actually tells two cards apart - the timestamp and the extension - is what disappeared, and neither the
 * user nor the file list in their own Downloads app could be matched against a card.
 *
 * The fix is [DocumentExporter.middleEllipsize], a string-level middle ellipsis, because the render-time
 * `TextOverflow.MiddleEllipsis` needs ui-text 1.10 and this app resolves 1.7.0. A JVM test cannot see which
 * string the card renders, but it can pin the contract the card now depends on: the tail survives, and the
 * result never exceeds the budget the card's box fits.
 */
class FileNameShapeTest {

    /** Two names this app really writes: same session, same prefix, one minute and one format apart. */
    private val docxName = "AIChat_Synthetic_Digits_Check_20261012_051425.docx"
    private val pdfName = "AIChat_Synthetic_Digits_Check_20261012_051331.pdf"

    /** The card's name box at 1080x1920 fits about this many monospace characters at bodySmall. */
    private val cardBudget = 24

    @Test
    fun ellipsizedAtCardWidthTheTailStillSurvives() {
        val shown = DocumentExporter.middleEllipsize(docxName, cardBudget)

        assertTrue("extension must be visible, was: $shown", shown.endsWith(".docx"))
        assertTrue("timestamp must be visible, was: $shown", shown.contains("20261012_051425"))
    }

    @Test
    fun noBudgetIsEverOverflowedAndNoRoomyBudgetLosesTheExtension() {
        (0..40).forEach { budget ->
            val shown = DocumentExporter.middleEllipsize(docxName, budget)

            assertTrue("budget $budget produced ${shown.length} chars: $shown", shown.length <= budget)
            if (budget >= 8) {
                assertTrue("budget $budget dropped the extension: $shown", shown.endsWith(".docx"))
            }
        }
    }

    @Test
    fun aNameThatFitsComesBackByteIdentical() {
        assertEquals(docxName, DocumentExporter.middleEllipsize(docxName, docxName.length + 1))
        // The boundary is the length itself: a name exactly as wide as the box is not touched.
        assertEquals(docxName, DocumentExporter.middleEllipsize(docxName, docxName.length))

        val short = "Report.docx"
        assertEquals(short, DocumentExporter.middleEllipsize(short, cardBudget))
    }

    @Test
    fun twoExportsOfOneSessionStayDistinctAtCardWidth() {
        val shown = DocumentExporter.middleEllipsize(docxName, cardBudget)
        val other = DocumentExporter.middleEllipsize(pdfName, cardBudget)

        assertNotEquals(shown, other)
    }

    /**
     * The ellipsis keeps the last 20 characters, which is exactly `yyyyMMdd_HHmmss` plus "." plus the
     * longest extension this app writes. Nothing else in the codebase promises that, so a format with a
     * longer extension would silently clip the timestamp on the card instead of failing here.
     */
    @Test
    fun everyFormatTheAppCanWriteKeepsItsTimestampInsideTheTail() {
        ExportFormat.entries.forEach { format ->
            val name = "AIChat_Any_Session_Title_20261012_051425.${format.extension}"
            val shown = DocumentExporter.middleEllipsize(name, cardBudget)

            assertTrue(
                "${format.extension} lost its extension on the card: $shown",
                shown.endsWith(".${format.extension}")
            )
            assertTrue(
                "${format.extension} lost its timestamp on the card: $shown",
                shown.contains("20261012_051425")
            )
        }
    }
}
