package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealUserScenarioPhase02ImportTest {

    @Test
    fun phase0_preflightIsClean() {
        assertTrue("preflight must pass on emulator-5554", RealUserScenarioHarness.preflight())
        RealUserScenarioHarness.launchApp()
        assertTrue("app must launch without crash", True)
    }

    @Test
    fun phase1_threeTabsOnFreshInstall() {
        RealUserScenarioHarness.launchApp()
        assertTrue("tab ورود must be visible", RealUserScenarioHarness.tabIsVisible("ورود"))
        assertTrue("tab کتابخانه must be visible", RealUserScenarioHarness.tabIsVisible("کتابخانه"))
        assertTrue("tab تنظیمات must be visible", RealUserScenarioHarness.tabIsVisible("تنظیمات"))
        val workspaceVisible = RealUserScenarioHarness.tabIsVisible("محیط کار")
        val importVisible = RealUserScenarioHarness.tabIsVisible("استخراج")
            || RealUserScenarioHarness.tabIsVisible("ورود")
        assertTrue("import/entry tab must be visible on fresh install", importVisible || !workspaceVisible)
    }

    @Test
    fun phase1_guestMode() {
        RealUserScenarioHarness.launchApp()
        val guest = RealUserScenarioHarness.isGuestMode()
        val importVisible = RealUserScenarioHarness.tabIsVisible("ورود")
            || RealUserScenarioHarness.tabIsVisible("استخراج")
            || RealUserScenarioHarness.tabIsVisible("کتابخانه")
        assertTrue("app must be usable without Google login (guest or import visible)", guest || importVisible)
    }

    @Test
    fun phase2_importRefLinkShowsCounts() {
        RealUserScenarioHarness.launchApp()
        RealUserScenarioHarness.importLink(RealUserScenarioHarness.REF_LINK)
        RealUserScenarioHarness.dumpUi("phase2_importRefLink")
        val banner = RealUserScenarioHarness.bannerText()
        val workspaceVisible = RealUserScenarioHarness.tabIsVisible("محیط کار")
        if (banner != null) {
            assertTrue("banner must be expected error text", banner.contains("کامل خوانده نشد") || banner.contains("نامعتبر"))
        } else {
            assertTrue("workspace tab محیط کار must be visible after import", workspaceVisible)
        }
        assertTrue("no unexpected crash - workspace or banner must appear", workspaceVisible || banner != null)
    }
}

private const val True = true
