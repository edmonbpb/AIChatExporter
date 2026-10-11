package com.example

import com.example.data.model.ExportFormat
import com.example.domain.exporter.ExportOptions
import com.example.ui.viewmodel.ExportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * `ExportOptions` used to be hand-built in two places: the export button in `MainViewModel` and the live
 * preview in `ExportStudioScreen`. The two literals had already taken different routes to the document
 * title, and the next wave adds another switch, which is how a preview starts lying about the document it
 * claims to show. Both paths now call [ExportUiState.toExportOptions], and these tests pin that one route.
 *
 * What this test can and cannot do, plainly: a JVM test cannot see which literal a screen writes, so it
 * cannot catch somebody re-adding a second hand-built `ExportOptions(...)` at a call site. What it does
 * catch is the schema half of the drift: it walks every field the struct declares and demands the factory
 * account for it, so a field added to `ExportOptions` without a matching line in the factory turns this
 * red the moment it is written.
 */
class ExportOptionsFactoryTest {

    /**
     * Backing-field names of the options struct, i.e. every option that exists today. Kotlin puts a static
     * `$stable` flag on a class with default arguments, and a static is not an option, so only instance
     * fields are walked.
     */
    private fun optionFields(): List<Field> =
        ExportOptions::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }

    private fun optionFieldNames(): List<String> = optionFields().map { it.name }.sorted()

    /** Reads a property through its public getter, so a data-class field added later is read too. */
    private fun valueOf(target: Any, fieldName: String): Any? {
        val getter = "get" + fieldName.replaceFirstChar { it.uppercaseChar() }
        return target.javaClass.getMethod(getter).invoke(target)
    }

    private fun snapshot(options: ExportOptions): Map<String, Any?> =
        optionFields().associate { it.name to valueOf(options, it.name) }

    @Test
    fun everyFieldTheStructDeclaresIsAnsweredByName() {
        val state = ExportUiState(
            selectedFormat = ExportFormat.PDF,
            includeMetadata = false,
            includeSummary = false,
            includeCode = false,
            convertPersianDigits = false,
            pptxSummaryAndCodeOnly = false,
            customTitle = "Typed Title"
        )
        val expected = mapOf<String, Any?>(
            "format" to ExportFormat.PDF,
            "includeMetadataTable" to false,
            "includeExecutiveSummary" to false,
            "includeCodeBlocks" to false,
            "convertToPersianDigits" to false,
            "pptxSummaryAndCodeOnly" to false,
            "customTitle" to "Typed Title"
        )
        val produced = state.toExportOptions("Session Title")

        assertEquals(
            "ExportOptions grew a field that toExportOptions does not set - give it a line in the factory and here",
            expected.keys.sorted().toString(),
            optionFieldNames().toString()
        )
        // Sorted copies: `declaredFields` order is not fixed by the JVM, so the comparison may not depend on it.
        assertEquals(expected.toSortedMap().toString(), snapshot(produced).toSortedMap().toString())
    }

    @Test
    fun switchesLeftOnStillReachTheDocument() {
        val produced = ExportUiState(customTitle = "Typed Title").toExportOptions("Session Title")

        assertEquals(ExportFormat.DOCX, produced.format)
        assertTrue(produced.includeMetadataTable)
        assertTrue(produced.includeExecutiveSummary)
        assertTrue(produced.includeCodeBlocks)
        assertTrue(produced.convertToPersianDigits)
        // The fifth switch is the one that is off on its own: an untouched struct means the whole conversation.
        assertFalse(produced.pptxSummaryAndCodeOnly)
        assertEquals("Typed Title", produced.customTitle)
    }

    /**
     * The export path used to substitute the session title itself; the preview path used to hand the
     * exporter a null and let `options.customTitle ?: session.title` patch it downstream. Same printed
     * title either way, so the factory keeps the export path's behaviour - the resolved one - and the
     * preview stops depending on every consumer remembering the fallback.
     */
    @Test
    fun anEmptyTitleComesOutAsTheSessionTitleNeverAsNull() {
        listOf("", "   ").forEach { blank ->
            val produced = ExportUiState(customTitle = blank).toExportOptions("Session Title")

            assertEquals("Session Title", produced.customTitle)
        }
    }

    @Test
    fun flippingTheCodeSwitchChangesExactlyThatOption() {
        val state = ExportUiState(customTitle = "Typed Title")
        val before = state.toExportOptions("Session Title")
        val after = state.copy(includeCode = false).toExportOptions("Session Title")

        assertTrue(valueOf(before, "includeCodeBlocks") as Boolean)
        assertFalse(valueOf(after, "includeCodeBlocks") as Boolean)
        assertNotEquals(before, after)

        val changed = optionFieldNames().filter { valueOf(before, it) != valueOf(after, it) }
        assertEquals(listOf("includeCodeBlocks").toString(), changed.toString())
    }

    @Test
    fun flippingTheScopeSwitchChangesExactlyThatOption() {
        val state = ExportUiState(customTitle = "Typed Title")
        val before = state.toExportOptions("Session Title")
        val after = state.copy(pptxSummaryAndCodeOnly = true).toExportOptions("Session Title")

        assertFalse(valueOf(before, "pptxSummaryAndCodeOnly") as Boolean)
        assertTrue(valueOf(after, "pptxSummaryAndCodeOnly") as Boolean)
        assertNotEquals(before, after)

        val changed = optionFieldNames().filter { valueOf(before, it) != valueOf(after, it) }
        assertEquals(listOf("pptxSummaryAndCodeOnly").toString(), changed.toString())
    }

    /**
     * Every other state in this file gives all the switches the same value at once, so a factory line that
     * reads its neighbour's switch prints the same answer and passes. These states alternate instead, and
     * between them every pair of switches differs in at least one of them, so no pair can be swapped quietly.
     * Five switches need three states, not two: four booleans already use up the four two-state patterns.
     */
    @Test
    fun everySwitchCarriesItsOwnValueNotANeighbours() {
        listOf(
            ExportUiState(
                selectedFormat = ExportFormat.PPTX,
                includeMetadata = true,
                includeSummary = false,
                includeCode = true,
                convertPersianDigits = false,
                pptxSummaryAndCodeOnly = true,
                customTitle = "Typed Title"
            ),
            ExportUiState(
                selectedFormat = ExportFormat.PDF,
                includeMetadata = false,
                includeSummary = false,
                includeCode = true,
                convertPersianDigits = true,
                pptxSummaryAndCodeOnly = false,
                customTitle = "Typed Title"
            ),
            ExportUiState(
                selectedFormat = ExportFormat.MARKDOWN,
                includeMetadata = true,
                includeSummary = true,
                includeCode = false,
                convertPersianDigits = true,
                pptxSummaryAndCodeOnly = false,
                customTitle = "Typed Title"
            )
        ).forEach { state ->
            val produced = state.toExportOptions("Session Title")

            assertEquals("format must come from selectedFormat", state.selectedFormat, produced.format)
            assertEquals("includeMetadataTable must come from includeMetadata", state.includeMetadata, produced.includeMetadataTable)
            assertEquals("includeExecutiveSummary must come from includeSummary", state.includeSummary, produced.includeExecutiveSummary)
            assertEquals("includeCodeBlocks must come from includeCode", state.includeCode, produced.includeCodeBlocks)
            assertEquals("convertToPersianDigits must come from convertPersianDigits", state.convertPersianDigits, produced.convertToPersianDigits)
            assertEquals("pptxSummaryAndCodeOnly must come from pptxSummaryAndCodeOnly", state.pptxSummaryAndCodeOnly, produced.pptxSummaryAndCodeOnly)
            assertEquals("customTitle must come from customTitle", state.customTitle, produced.customTitle)
        }
    }
}
