package com.example

import androidx.compose.ui.text.style.TextAlign
import com.example.data.model.TextDirection
import com.example.domain.bidi.AlignSide
import com.example.domain.bidi.BidiEngine
import com.example.domain.bidi.paragraphSide
import com.example.domain.exporter.DocumentExporter
import com.example.ui.components.bidiTextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every writer asks the one shared decision, and the screen already made the same call for the
 * same words, so these cases pin the table and hold the document to the screen.
 */
class ParagraphDirectionTest {

    @Test
    fun aPersianParagraphSitsOnTheRight() {
        assertEquals(TextDirection.RTL, BidiEngine.detectDirection(PERSIAN))
        assertEquals(AlignSide.RIGHT, paragraphSide(TextDirection.RTL, PERSIAN))
    }

    @Test
    fun anEnglishParagraphSitsOnTheLeft() {
        assertEquals(TextDirection.LTR, BidiEngine.detectDirection(ENGLISH))
        assertEquals(AlignSide.LEFT, paragraphSide(TextDirection.LTR, ENGLISH))
    }

    @Test
    fun aMixedParagraphSitsOnTheRightWhenPersianDominates() {
        assertEquals(TextDirection.MIXED, BidiEngine.detectDirection(PERSIAN_WITH_LATIN))
        assertEquals(AlignSide.RIGHT, paragraphSide(TextDirection.MIXED, PERSIAN_WITH_LATIN))
    }

    @Test
    fun aMixedParagraphSitsOnTheLeftWhenLatinDominates() {
        assertEquals(TextDirection.LTR, BidiEngine.detectDirection(LATIN_HEAVY))
        assertEquals(AlignSide.LEFT, paragraphSide(TextDirection.MIXED, LATIN_HEAVY))
    }

    @Test
    fun theDocumentSideIsTheSideTheScreenChose() {
        val fixtures = listOf(
            TextDirection.RTL to PERSIAN,
            TextDirection.LTR to ENGLISH,
            TextDirection.MIXED to PERSIAN,
            TextDirection.MIXED to ENGLISH,
            TextDirection.MIXED to LATIN_HEAVY
        )
        fixtures.forEach { (direction, text) ->
            val shown = bidiTextAlign(direction, text)
            assertEquals(
                "screen and document disagree for $direction over the same text",
                if (shown == TextAlign.Right) AlignSide.RIGHT else AlignSide.LEFT,
                paragraphSide(direction, text)
            )
        }
    }

    @Test
    fun htmlMarksEachLineWithTheSideItDecided() {
        val twoLines = listOf(PERSIAN, ENGLISH).joinToString("\n")
        assertEquals(
            "<p dir=\"rtl\">$PERSIAN</p>\n<p dir=\"ltr\">$ENGLISH</p>",
            DocumentExporter.htmlParagraphs(twoLines, TextDirection.MIXED)
        )
    }

    private companion object {
        const val PERSIAN = "سلام این یک متن آزمایشی به زبان فارسی است"
        const val PERSIAN_WITH_LATIN = "$PERSIAN (ViewModel)"
        const val ENGLISH = "Hello this is a test text in English language"
        const val LATIN_HEAVY = "Use Kotlin coroutines and Retrofit inside the ViewModel"
    }
}
