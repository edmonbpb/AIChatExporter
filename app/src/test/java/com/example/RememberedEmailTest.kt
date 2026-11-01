package com.example

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.AuthScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * «مرا به خاطر بسپار» keeps one thing: the address, so the next launch does not ask for it again.
 * The password is never part of the bargain, and clearing the box has to clear the stored key rather
 * than merely stop reading it - otherwise a tick removed on Tuesday still fills the form on Friday.
 * The last test walks the whole thing through the real form, so the wiring between the checkbox and
 * the store is covered as well as the store itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class RememberedEmailTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private val viewModels = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun aRememberedAddressIsWrittenAndReadBackByTheNextViewModel() {
        val first = newViewModel()

        first.setRememberedEmail(SAVED_EMAIL, true)

        assertEquals(SAVED_EMAIL, authPrefs().getString("remembered_email", null))
        assertEquals(SAVED_EMAIL, first.rememberedEmail.value)
        // The next launch: a fresh view model over the same file has to find it without being told.
        assertEquals(SAVED_EMAIL, newViewModel().rememberedEmail.value)
    }

    @Test
    fun forgettingTakesTheAddressOutOfTheFileAndOutOfTheFlow() {
        val first = newViewModel()
        first.setRememberedEmail(SAVED_EMAIL, true)

        first.setRememberedEmail(SAVED_EMAIL, false)

        assertNull("the flow must forget it", first.rememberedEmail.value)
        assertFalse("the key must be gone, not just unread", authPrefs().contains("remembered_email"))
        assertNull(authPrefs().getString("remembered_email", null))
        assertNull("a cold start must not find it either", newViewModel().rememberedEmail.value)
    }

    @Test
    fun aRememberedLoginStoresTheAddressAndNeverThePassword() {
        val viewModel = newViewModel()
        compose.setContent { MaterialTheme { AuthScreen(viewModel) } }

        compose.onNodeWithTag("auth_email_input").performScrollTo().performTextInput(SAVED_EMAIL)
        compose.onNodeWithTag("auth_password_input").performScrollTo().performTextInput(PASSWORD)
        compose.onNodeWithTag("auth_remember_me").performScrollTo().performClick()
        compose.onNodeWithTag("auth_remember_me").assertIsOn()

        compose.onNodeWithTag("auth_submit_btn").performScrollTo().performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200))
        compose.waitForIdle()

        assertEquals("the box that was ticked fills the next launch", SAVED_EMAIL, viewModel.rememberedEmail.value)
        assertEquals(SAVED_EMAIL, authPrefs().getString("remembered_email", null))
        val prefs = authPrefs().all
        assertTrue("fixture: something besides the session was written", prefs.containsKey("user_email"))
        prefs.forEach { (key, value) ->
            assertFalse("pref «$key» holds the password", value.toString().contains(PASSWORD))
        }
    }

    private fun newViewModel(): MainViewModel =
        MainViewModel(application).also { viewModels += it }

    private fun authPrefs() = application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)

    /**
     * Google's two process-wide caches, dropped the way `DriveGuestCardTest` drops them, so this
     * class reads its own fixture and the full suite behaves like a solo run.
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

        const val SAVED_EMAIL = "saved@example.com"
        const val PASSWORD = "Secret-1234"
    }
}
