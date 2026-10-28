package com.example

import android.accounts.Account
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.drive.DriveAuth
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MainViewModel
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Drive card after the OAuth client switch: Google's local cache still lists `drive.file` while
 * the token request needs a fresh consent. The card must follow the token, not the cache — otherwise
 * it offers backup/restore forever and never the connect button the error message points at.
 *
 * Only Google's blocking token request is shadowed; DriveAuth, the view model and the real settings
 * screen all run as they do on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [DriveReconnectTest.GoogleTokenShadow::class],
    instrumentedPackages = ["com.google.android.gms.auth.GoogleAuthUtil"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class DriveReconnectTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        GoogleTokenShadow.reset()
        application = ApplicationProvider.getApplicationContext()
        seedStaleGrant()
        assertTrue("fixture must reproduce the stale cached grant", DriveAuth.hasConsent(application))
        viewModel = MainViewModel(application)
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) {
            compose.runOnIdle { viewModel.viewModelScope.cancel() }
        }
        if (::application.isInitialized) {
            application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun staleGrantWithPermissionErrorOffersConnectInsteadOfBackup() {
        openSettings()

        awaitProbe()
        awaitStatus(PERMISSION_MISSING)

        assertFalse("a rejected consent cannot stay linked", viewModel.driveLinked.value)
        compose.onNodeWithTag("drive_connect_btn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_backup_now").assertDoesNotExist()
        compose.onNodeWithTag("drive_restore").assertDoesNotExist()
    }

    @Test
    fun networkFailureKeepsTheCardAndSaysWhy() {
        GoogleTokenShadow.script(OUTCOME_NETWORK)
        openSettings()

        awaitProbe()
        awaitStatus(NETWORK_MISSING)

        assertTrue("a network failure says nothing about the consent", viewModel.driveLinked.value)
        compose.onNodeWithTag("drive_backup_now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_restore").assertExists()
        compose.onNodeWithTag("drive_connect_btn").assertDoesNotExist()
    }

    @Test
    fun providerFailureOffersConnectWithItsOwnMessage() {
        GoogleTokenShadow.script(OUTCOME_FAILURE)
        openSettings()

        awaitProbe()
        awaitStatus(CONNECTION_FAILED)

        assertFalse(viewModel.driveLinked.value)
        compose.onNodeWithTag("drive_connect_btn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_backup_now").assertDoesNotExist()
    }

    private fun openSettings() {
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }
    }

    private fun awaitProbe() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            GoogleTokenShadow.requests.get() > 0
        }
    }

    private fun awaitStatus(expected: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            viewModel.driveBackupStatus.value == expected
        }
        assertEquals(expected, viewModel.driveBackupStatus.value)
    }

    /** The real Google Play services cache file, so DriveAuth's own check sees a granted scope. */
    private fun seedStaleGrant() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("defaultGoogleSignInAccount", ACCOUNT_KEY)
            .putString(
                "googleSignInAccount:$ACCOUNT_KEY",
                """{"id":"$ACCOUNT_KEY","email":"$TEST_EMAIL","displayName":"Drive Test",""" +
                    """"expirationTime":4102444800,"obfuscatedIdentifier":"$ACCOUNT_KEY",""" +
                    """"grantedScopes":["${DriveAuth.SCOPE}"]}"""
            )
            .commit()
    }

    @Implements(value = GoogleAuthUtil::class, isInAndroidSdk = false)
    class GoogleTokenShadow {
        companion object {
            private val outcomes = ArrayDeque<String>()
            val requests = AtomicInteger()

            fun script(vararg sequence: String) {
                outcomes.clear()
                sequence.forEach { outcomes.addLast(it) }
            }

            @JvmStatic
            @Resetter
            fun reset() {
                outcomes.clear()
                requests.set(0)
            }

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun getToken(context: Context, account: Account, scope: String): String {
                assertEquals(Account(TEST_EMAIL, "com.google"), account)
                assertEquals("oauth2:${DriveAuth.SCOPE}", scope)
                requests.incrementAndGet()
                return when (outcomes.removeFirstOrNull() ?: OUTCOME_PERMISSION) {
                    OUTCOME_NETWORK -> throw IOException("offline")
                    OUTCOME_FAILURE -> throw GoogleAuthException("provider refused")
                    else -> throw UserRecoverableAuthException(
                        "NEED_REMOTE_CONSENT",
                        Intent(RECOVERY_ACTION)
                    )
                }
            }
        }
    }

    private companion object {
        const val TEST_EMAIL = "drive-reconnect@example.invalid"
        const val ACCOUNT_KEY = "stale-drive-account"
        const val SIGNIN_PREFS = "com.google.android.gms.signin"
        const val RECOVERY_ACTION = "com.example.test.DRIVE_RECOVERY"
        const val OUTCOME_PERMISSION = "permission"
        const val OUTCOME_NETWORK = "network"
        const val OUTCOME_FAILURE = "failure"

        // The app's own copy, asserted verbatim so what the user reads cannot drift unnoticed.
        const val PERMISSION_MISSING =
            "اجازهٔ نوشتن در گوگل درایو داده نشده است؛ از دکمهٔ اتصال استفاده کن."
        const val NETWORK_MISSING =
            "ارتباط با گوگل درایو برقرار نشد؛ اینترنت را بررسی کن و دوباره تلاش کن."
        const val CONNECTION_FAILED = "اتصال به گوگل درایو انجام نشد؛ دوباره تلاش کن."
    }
}
