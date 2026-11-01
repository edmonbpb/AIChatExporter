package com.example

import android.accounts.Account
import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.drive.DriveAuth
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MainViewModel
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicInteger

/**
 * The owner's decision for this wave: the button in the backup card disconnects Google Drive, it does
 * not sign anybody out. The account, its stored session and its conversations stay on the device; the
 * real exit is the separate red card, and this test holds them apart.
 *
 * Only Google's blocking token request is shadowed, and it succeeds, so the card has a genuine
 * "connected" state to leave: the account in Google's own cache carries no Drive scope, so the app's
 * record on disk says linked while only the token can make the card say so. Everything else - DriveAuth,
 * the view model and the real settings screen - runs as it does on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [DriveDisconnectTest.GoogleTokenShadow::class],
    instrumentedPackages = ["com.google.android.gms.auth.GoogleAuthUtil"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class DriveDisconnectTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        GoogleTokenShadow.reset()
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        seedCachedGoogleAccount()
        seedSignedInSession()
        assertNotNull("fixture: an account this app signed in with is still in Google's cache", DriveAuth.lastAccount(application))
        assertFalse("fixture: the cached account itself carries no Drive scope", DriveAuth.hasConsent(application))
        viewModel = MainViewModel(application)
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
    fun disconnectingGoogleKeepsTheAccountAndDropsOnlyTheDriveLink() {
        val profileBefore = viewModel.userProfile.value
        assertNotNull("fixture: the session is a signed-in one, not a guest", profileBefore)
        assertTrue("fixture: the session is a signed-in one, not a guest", profileBefore?.isGuest == false)
        assertEquals("fixture: the stored session is this account", ACCOUNT_EMAIL, profileBefore?.email)

        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        // The card only says "connected" once Drive's token was actually minted - the switch is not
        // under test here, it is the state the disconnect has to undo.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            viewModel.driveLinked.value
        }
        compose.onNodeWithTag("drive_backup_now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("disconnect_btn").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("disconnect_btn").performClick()
        compose.onNodeWithText(ASK_BEFORE_DISCONNECT).assertIsDisplayed()
        compose.onNodeWithText(CONFIRM_DISCONNECT).performClick()

        // What the view model holds: the Drive link is off and the session is untouched.
        assertFalse("the link is dropped", viewModel.driveLinked.value)
        assertEquals(DISCONNECTED, viewModel.driveBackupStatus.value)
        val profileAfter = viewModel.userProfile.value
        assertNotNull("a disconnect is not a sign-out: the account stays", profileAfter)
        assertEquals("the same account stays", ACCOUNT_EMAIL, profileAfter?.email)
        assertTrue("the session stays a signed-in one", profileAfter?.isGuest == false)

        // What the device holds: the account is still written down, only the Drive flag went off.
        val prefs = application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        assertEquals("the stored address survives the disconnect", ACCOUNT_EMAIL, prefs.getString("user_email", null))
        assertTrue("the stored session survives the disconnect", prefs.contains("user_id"))
        assertFalse("the Drive flag is what gets cleared", prefs.getBoolean("drive_linked", true))

        // What the screen shows: the connect button is back, the backup actions are gone, and the
        // separate red exit card is still there for the user who really wants to leave.
        compose.onNodeWithTag("drive_connect_btn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_backup_now").assertDoesNotExist()
        compose.onNodeWithTag("drive_restore").assertDoesNotExist()
        compose.onNodeWithText(DISCONNECTED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SIGN_OUT_CARD_TITLE).performScrollTo().assertIsDisplayed()
    }

    /** The real Google Play services cache file, seeded the way `DriveReconnectTest` seeds it. */
    private fun seedCachedGoogleAccount() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("defaultGoogleSignInAccount", ACCOUNT_KEY)
            .putString(
                "googleSignInAccount:$ACCOUNT_KEY",
                """{"id":"$ACCOUNT_KEY","email":"$ACCOUNT_EMAIL","displayName":"Drive Disconnect",""" +
                    """"expirationTime":4102444800,"obfuscatedIdentifier":"$ACCOUNT_KEY",""" +
                    """"grantedScopes":[]}"""
            )
            .commit()
        // Google parses that file once per process and keeps the result, so the singletons holding it
        // are dropped before anything reads it here; a stale read would pick the wrong branch silently.
        resetGoogleAccountCache()
    }

    /** The app's own stored session: signed in, and on record as linked to Drive. */
    private fun seedSignedInSession() {
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("user_id", APP_SESSION_ID)
            .putString("user_name", "Drive Disconnect")
            .putString("user_email", ACCOUNT_EMAIL)
            .putBoolean("is_guest", false)
            .putBoolean("drive_linked", true)
            .putString("user_role", "کاربر سازمان")
            .commit()
    }

    /**
     * The preference files the card reads: Google's own account cache, Drive's answer, the app session -
     * and the two process-wide caches Google keeps over them.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        resetGoogleAccountCache()
    }

    /**
     * The account holder parses the stored account once in its constructor and never reads the file
     * again, and the `Storage` it parsed from is bound to whichever Application asked first. Robolectric
     * makes a new Application per test and runs every class in one JVM, so both are dropped here, the
     * same way `DriveGuestCardTest` does it. The names are Google's obfuscated internals, spelled out on
     * purpose: a library update that renames them fails loudly instead of reading a stale account.
     */
    private fun resetGoogleAccountCache() {
        ReflectionHelpers.setStaticField(GOOGLE_ACCOUNT_HOLDER, GOOGLE_ACCOUNT_HOLDER_FIELD, null)
        ReflectionHelpers.setStaticField(GOOGLE_STORAGE, GOOGLE_STORAGE_FIELD, null)
    }

    /** Google's blocking token request: the one class shadowed, and here it hands back a token. */
    @Implements(value = GoogleAuthUtil::class, isInAndroidSdk = false)
    class GoogleTokenShadow {
        companion object {
            val requests = AtomicInteger()

            @JvmStatic
            @Resetter
            fun reset() {
                requests.set(0)
            }

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun getToken(context: Context, account: Account, scope: String): String {
                assertEquals(Account(ACCOUNT_EMAIL, GOOGLE_ACCOUNT_TYPE), account)
                assertEquals("oauth2:${DriveAuth.SCOPE}", scope)
                requests.incrementAndGet()
                return "test-drive-token"
            }
        }
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
        const val GOOGLE_ACCOUNT_TYPE = "com.google"

        const val ACCOUNT_EMAIL = "drive-disconnect@example.invalid"
        const val ACCOUNT_KEY = "cached-disconnect-account"
        const val APP_SESSION_ID = "app-session-under-disconnect"

        // The contract's copy, asserted verbatim so what the user reads cannot drift unnoticed.
        const val ASK_BEFORE_DISCONNECT =
            "اتصال برنامه به گوگل درایو قطع می‌شود؛ حساب و مکالمه‌های شما روی همین دستگاه می‌ماند."
        const val DISCONNECTED =
            "اتصال به گوگل قطع شد؛ حساب و داده‌های شما روی همین دستگاه مانده است."
        const val CONFIRM_DISCONNECT = "قطع اتصال"
        const val SIGN_OUT_CARD_TITLE = "خروج از حساب کاربری"
    }
}
