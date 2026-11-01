package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.AuthScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicInteger

/**
 * The way back into the app from behind the lock: a user who chose «ورود دستی به حساب دیگر» lands on
 * the login screen with the app still locked, so that screen has to offer the device prompt again -
 * otherwise manual sign-in is a one-way door that looks like a crash.
 *
 * The button's presence is the whole contract: `MainActivity` passes a lambda only when the lock is on
 * and the manual pane was chosen, and passes null otherwise, so "null means no button" is what keeps
 * the login screen from advertising a way in that does not exist. Both halves are rendered here, one
 * `setContent` per test. That a successful prompt then opens the app is the activity's job and the
 * controller's handset check, not something a fake prompt could prove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class QuickUnlockButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        viewModel = MainViewModel(application)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun withoutTheLockTheLoginScreenOffersNoQuickUnlock() {
        compose.setContent { MaterialTheme { AuthScreen(viewModel, onQuickUnlock = null) } }

        compose.onNodeWithTag("auth_screen").assertIsDisplayed()
        compose.onNodeWithTag("auth_quick_unlock_btn").assertDoesNotExist()
        compose.onNodeWithText(QUICK_UNLOCK_LABEL).assertDoesNotExist()
    }

    @Test
    fun theManualSignInScreenOffersTheQuickUnlockAndTheButtonFires() {
        val presses = AtomicInteger()

        compose.setContent {
            MaterialTheme { AuthScreen(viewModel, onQuickUnlock = { presses.incrementAndGet() }) }
        }

        compose.onNode(hasTestTag("auth_quick_unlock_btn") and hasText(QUICK_UNLOCK_LABEL))
            .performScrollTo()
            .assertIsDisplayed()

        compose.onNodeWithTag("auth_quick_unlock_btn").performClick()
        compose.waitForIdle()

        assertEquals("the press asks for the device lock exactly once", 1, presses.get())
        assertNull(
            "a quick unlock is not a sign-in; the form must not have been submitted",
            viewModel.userProfile.value
        )
    }

    /**
     * Google's two process-wide caches, dropped the way `DriveGuestCardTest` drops them, beside the
     * three preference files a view model reads at construction - the same fixture the other
     * view-model tests use so this class cannot pass alone and fail in the full suite.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
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

        // The screen's own copy, asserted verbatim so what the user reads cannot drift unnoticed.
        const val QUICK_UNLOCK_LABEL = "ورود سریع با قفل امنیتی"
    }
}
