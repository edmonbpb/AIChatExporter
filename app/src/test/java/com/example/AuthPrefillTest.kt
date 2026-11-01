package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.AuthScreen
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
 * What the login form looks like on the morning after «مرا به خاطر بسپار». The address is already
 * there, so the user types only the password - and the password box is empty, because the saved
 * address is the only thing the file ever kept. Both halves are read off the screen's own editable
 * text, not off the view model, so a form that ignored the stored address would redden this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AuthPrefillTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        // The same key «مرا به خاطر بسپار» writes on a successful login.
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .edit().putString("remembered_email", SAVED_EMAIL).commit()
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
    fun theFormReopensWithTheSavedAddressAndAnEmptyPassword() {
        viewModel = MainViewModel(application)
        assertEquals("fixture: the address is on disk before the screen is built", SAVED_EMAIL, viewModel.rememberedEmail.value)
        compose.setContent { MaterialTheme { AuthScreen(viewModel) } }

        assertEquals("the saved address fills the address box", SAVED_EMAIL, editableText("auth_email_input"))
        assertTrue(
            "the password box is never prefilled; got «${editableText("auth_password_input")}»",
            editableText("auth_password_input").isEmpty()
        )
    }

    /** The text a box actually holds; an unwritten box has nothing to report and reads as empty. */
    private fun editableText(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

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
    }
}
