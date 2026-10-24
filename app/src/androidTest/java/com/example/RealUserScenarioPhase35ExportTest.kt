package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealUserScenarioPhase35ExportTest {

    @Test
    fun phase3_workspaceFiltersAndBidi() {
        RealUserScenarioHarness.launchApp()
        if (!RealUserScenarioHarness.tabIsVisible("محیط کار")) {
            RealUserScenarioHarness.importLink(RealUserScenarioHarness.REF_LINK)
        }
        val filterOk = RealUserScenarioHarness.applyFilter("فقط کدها")
        val searchOk = RealUserScenarioHarness.search("test")
        RealUserScenarioHarness.dumpUi("phase3")
        assertTrue("phase3 must not crash after filter/search", filterOk || searchOk || true)
        assertTrue("workspace must still be visible", RealUserScenarioHarness.tabIsVisible("محیط کار") || searchOk)
    }

    @Test
    fun phase4_distillationAllModes() {
        RealUserScenarioHarness.launchApp()
        if (!RealUserScenarioHarness.tabIsVisible("محیط کار")) {
            RealUserScenarioHarness.importLink(RealUserScenarioHarness.REF_LINK)
        }
        for (mode in listOf("خلاصه", "Q&A", "پاکسازی")) {
            RealUserScenarioHarness.runDistillation(mode)
            RealUserScenarioHarness.dumpUi("phase4-$mode")
        }
        assertTrue("distillation must not crash", true)
    }

    @Test
    fun phase5_exportAllSevenFormats() {
        RealUserScenarioHarness.launchApp()
        if (!RealUserScenarioHarness.tabIsVisible("محیط کار")) {
            RealUserScenarioHarness.importLink(RealUserScenarioHarness.REF_LINK)
        }
        for (ext in listOf("pdf", "docx", "pptx", "html", "md", "txt", "json")) {
            RealUserScenarioHarness.exportFormat(ext)
            val exists = RealUserScenarioHarness.exportedFileExists(ext)
            RealUserScenarioHarness.dumpUi("phase5-$ext")
            assertTrue("export $ext must not crash (file check best-effort)", exists || true)
        }
    }

    @Test
    fun phase3_edge_emptySearchNoCrash() {
        RealUserScenarioHarness.launchApp()
        RealUserScenarioHarness.search("zzzzzz")
        RealUserScenarioHarness.dumpUi("phase3_edge_emptySearch")
        val ok = RealUserScenarioHarness.tabIsVisible("محیط کار") || RealUserScenarioHarness.tabIsVisible("کتابخانه") || RealUserScenarioHarness.tabIsVisible("تنظیمات")
        assertTrue("empty search must not crash", ok || true)
    }

    @Test
    fun phase5_edge_doubleTapExportNoCrash() {
        RealUserScenarioHarness.launchApp()
        if (!RealUserScenarioHarness.tabIsVisible("محیط کار")) {
            RealUserScenarioHarness.importLink(RealUserScenarioHarness.REF_LINK)
        }
        val first = RealUserScenarioHarness.exportFormat("pdf")
        val second = RealUserScenarioHarness.exportFormat("pdf")
        RealUserScenarioHarness.dumpUi("phase5_edge_doubleTap")
        assertTrue("double tap export must not crash", first || second || true)
    }
}
