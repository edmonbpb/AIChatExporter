package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WorkspaceScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * The two switches taken off the Settings screen were calibration keys, not user choices: the BiDi
 * typography card existed to turn Persian digits off on every screen while paragraph direction was
 * judged, and the progressive-workflow card existed to show both bottom-bar layouts on one handset.
 * Both are gone from the screen now, and both keep their "on" value in the view model, so what the
 * user reads must look exactly as it did while the keys were visible. The second test renders a real
 * conversation in the workspace for that reason: the digits are proven on the screen that consumes
 * them, not only on the flag.
 *
 * No Google account is seeded here, so Settings' own Drive probe settles as unlinked without asking
 * Google for a token; the two process caches are still dropped around each test because this class
 * runs in the same JVM as the Drive classes and a stale account would decide their branch instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsHiddenTogglesTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            compose.runOnIdle { viewModel.viewModelScope.cancel() }
        }
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun theSettingsScreenOffersNeitherCalibrationSwitchAnyMore() {
        viewModel = MainViewModel(application)
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        // The screen itself is up - its root and the switch this wave added - so the two absences below
        // are absences on a rendered page and not the silence of a page that failed to compose.
        compose.onNodeWithTag("settings_screen").assertExists()
        compose.onNodeWithTag("settings_toggle_app_lock").assertExists()

        compose.onNodeWithTag("settings_toggle_persian_digits").assertDoesNotExist()
        compose.onNodeWithText(TYPOGRAPHY_CARD_HEADER).assertDoesNotExist()
        compose.onNodeWithText(WORKFLOW_CARD_HEADER).assertDoesNotExist()

        // The keys keep working with their "on" value: hiding them changed the screen, not the app.
        assertTrue("digits are still Persian with the switch gone", viewModel.usePersianDigits.value)
        assertTrue("the workflow stays the default with the switch gone", viewModel.progressiveWorkflowEnabled.value)
    }

    @Test
    fun aNumericStatBadgeInTheWorkspaceStillPrintsPersianDigits() {
        viewModel = MainViewModel(application)
        viewModel.rawInputText.value = AICHAT_JSON
        compose.setContent { MaterialTheme { WorkspaceScreen(viewModel) } }

        viewModel.parseAndLoadTranscript()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            viewModel.activeSession.value?.messages?.isNotEmpty() == true
        }

        assertEquals(CONVERSATION_TITLE, viewModel.activeSession.value?.title)
        assertEquals("fixture: two turns, so the badge's value is the Persian «۲»", 2, viewModel.activeSession.value?.messages?.size)
        compose.onNodeWithTag("workspace_screen").assertExists()
        compose.onNodeWithTag("stat_badge_پیام‌ها").assertExists()

        // The badge prints its value in Persian digits: the latin «2» would be the broken state here.
        val persianDigitValues = compose.onAllNodesWithText("۲").fetchSemanticsNodes()
        assertTrue(
            "the stat badges must still print Persian digits: ${persianDigitValues.size} node(s) carry «۲»",
            persianDigitValues.isNotEmpty()
        )
    }

    /**
     * The preference files these two screens read, and the two process-wide caches Google keeps over
     * them - cleared and dropped the same way `DriveGuestCardTest` does it, so this class cannot leave
     * an account behind for the Drive tests that run in the same JVM.
     */
    private fun clearAccountState() {
        listOf(SIGNIN_PREFS, DRIVE_BACKUP_PREFS, AUTH_PREFS).forEach { name ->
            application.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        resetGoogleAccountCache()
    }

    /**
     * Google's account holder parses the stored account once in its constructor and its `Storage` is
     * bound to the first Application that asked; Robolectric makes a new Application per test and runs
     * every class in one JVM, so both are dropped here or a fixture reads the previous class's file.
     * The names are Google's obfuscated internals, spelled out on purpose: a library update that renames
     * them fails this fixture loudly instead of quietly reading a stale account.
     */
    private fun resetGoogleAccountCache() {
        ReflectionHelpers.setStaticField(GOOGLE_ACCOUNT_HOLDER, GOOGLE_ACCOUNT_HOLDER_FIELD, null)
        ReflectionHelpers.setStaticField(GOOGLE_STORAGE, GOOGLE_STORAGE_FIELD, null)
    }

    private companion object {
        const val SIGNIN_PREFS = "com.google.android.gms.signin"
        const val DRIVE_BACKUP_PREFS = "drive_backup"
        const val AUTH_PREFS = "aichat_auth_prefs"

        val GOOGLE_ACCOUNT_HOLDER: Class<*> =
            com.google.android.gms.auth.api.signin.internal.zbn::class.java

        val GOOGLE_STORAGE: Class<*> =
            com.google.android.gms.auth.api.signin.internal.Storage::class.java

        const val GOOGLE_ACCOUNT_HOLDER_FIELD = "zbd"
        const val GOOGLE_STORAGE_FIELD = "zab"

        // The two card headers the plan removed, asserted verbatim: they are gone from the screen and
        // must not come back under the same words without a test turning red.
        const val TYPOGRAPHY_CARD_HEADER = "تنظیمات تایپوگرافی دوزبانه (BiDi Engine)"
        const val WORKFLOW_CARD_HEADER = "ناوبری مرحله‌ای و پویا (جریان کار)"

        const val CONVERSATION_TITLE = "مکالمهٔ آزمایشی"
        val AICHAT_JSON =
            """{"title":"$CONVERSATION_TITLE","platform":"custom","messages":[""" +
                """{"role":"user","content":"یک پرسش کوتاه برای آزمون ارقام"},{"role":"assistant","content":"پاسخ کوتاه مدل"}]}"""
    }
}
