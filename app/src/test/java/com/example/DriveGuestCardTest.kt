package com.example

import android.accounts.Account
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.drive.DriveAuth
import com.example.ui.screens.SettingsScreen
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.drivePermissionNotice
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.Resetter
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * B-45's Drive half: the card used to answer "write permission to Google Drive has not been granted"
 * to somebody in guest mode who had never been asked for it. [DriveAuth.accessToken] reports both the
 * guest and the withdrawn-consent case as `PermissionRequired`, and the payload tells them apart -
 * `PermissionRequired(null)` when this app knows no Google account (DriveAuth.kt:55),
 * `PermissionRequired(e.intent)` when an account is there and Google wants consent for it
 * (DriveAuth.kt:63). That payload, beside the session this app is itself in, is the only thing the card
 * may build its sentence from; it never asks Google a second question.
 *
 * Both branches of that rule are walked on the real screen here. Only Google's blocking token request
 * is shadowed (the same single class `DriveReconnectTest` shadows), so the real [DriveAuth], the real
 * view model and the real settings screen run the way they do on the phone: the guest case never
 * reaches the token call at all, because with no account known to the app Drive's answer is already
 * settled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    shadows = [DriveGuestCardTest.GoogleTokenShadow::class],
    instrumentedPackages = ["com.google.android.gms.auth.GoogleAuthUtil"]
)
@LooperMode(LooperMode.Mode.PAUSED)
class DriveGuestCardTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        GoogleTokenShadow.reset()
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        // The fixture starts on the guest's device: no Google sign-in known to the app, and nothing in
        // Google's local cache either, so nothing Drive could hand back a token for.
        assertNull(DriveAuth.lastAccount(application))
        assertFalse("fixture: no Drive grant on this device", DriveAuth.hasConsent(application))
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
    fun guestCardAsksForAGoogleSignInInsteadOfBlamingAMissingPermission() {
        viewModel = MainViewModel(application)
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        awaitStatus(SIGN_IN_FIRST)

        // The payload this sentence was built from: Drive answered `PermissionRequired` without ever
        // asking Google for a token, because this app knows no account.
        assertEquals("no account known means no token request", 0, GoogleTokenShadow.requests.get())
        assertNull(GoogleTokenShadow.askedAccount.get())
        compose.onNodeWithTag("drive_status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SIGN_IN_FIRST).performScrollTo().assertIsDisplayed()
        assertNotEquals(
            "the card must not blame a guest for a permission they never saw",
            CONSENT_MISSING,
            viewModel.driveBackupStatus.value
        )
        assertFalse("an unconnected card stays unconnected", viewModel.driveLinked.value)
        compose.onNodeWithTag("drive_backup_now").assertDoesNotExist()
    }

    @Test
    fun aGuestSessionGetsTheSameSentenceAsNobodySignedIn() {
        // The state the bug report was written from: the user is inside the app as a guest, and
        // opening Settings re-probes the Drive connection on its own. Guest mode is retired, so the
        // session is put on disk before the view model starts, the way a guest device already holds it.
        seedAppSession(guest = true)
        viewModel = MainViewModel(application)
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        awaitStatus(SIGN_IN_FIRST)

        assertTrue(viewModel.userProfile.value?.isGuest == true)
        assertEquals("no account known means no token request", 0, GoogleTokenShadow.requests.get())
        compose.onNodeWithText(SIGN_IN_FIRST).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_connect_btn").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aGuestSessionWithAnAccountStillInGooglesLocalCacheKeepsTheGuestSentence() {
        // The case nothing covered and the one that matters. The user signed out of this app and
        // continued as a guest, while Google's own cache still holds the account they signed out of -
        // so Drive answers `PermissionRequired(e.intent)`: an account is there and consent is missing.
        // Saying "write permission has not been granted" to a guest would blame them for the consent of
        // an account this app is no longer using, so the app's own session wins and the sentence stays
        // the guest one.
        seedCachedGoogleAccount()
        assertNotNull("fixture: Google's local cache holds an account", DriveAuth.lastAccount(application))
        seedAppSession(guest = true)
        viewModel = MainViewModel(application)
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        awaitStatus(SIGN_IN_FIRST)

        // Proof that the payload really was the account-present one, not the null of the tests above.
        awaitProbe()
        assertEquals(Account(SEED_EMAIL, GOOGLE_ACCOUNT_TYPE), GoogleTokenShadow.askedAccount.get())
        compose.onNodeWithText(SIGN_IN_FIRST).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CONSENT_MISSING).assertDoesNotExist()
        assertNotEquals(
            "a cached account must not turn the guest's sentence into a permission blame",
            CONSENT_MISSING,
            viewModel.driveBackupStatus.value
        )
    }

    @Test
    fun anAccountThisAppIsSignedInWithHearsTheConnectSentence() {
        // The other branch of the same rule, rendered rather than read off the function: a session that
        // is not a guest one, Google's cache holding the account, and consent missing - the payload
        // carries a recovery intent, so the B-42 sentence `DriveReconnectTest` asserts is the honest
        // answer and the guest sentence would send the user off to sign in again.
        seedCachedGoogleAccount()
        seedAppSession(guest = false)
        assertNotNull("fixture: Google's local cache holds an account", DriveAuth.lastAccount(application))
        viewModel = MainViewModel(application)
        assertFalse("fixture: the app is in a session, not a guest one", viewModel.userProfile.value?.isGuest == true)
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }

        awaitStatus(CONSENT_MISSING)

        awaitProbe()
        assertEquals(Account(SEED_EMAIL, GOOGLE_ACCOUNT_TYPE), GoogleTokenShadow.askedAccount.get())
        compose.onNodeWithText(CONSENT_MISSING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SIGN_IN_FIRST).assertDoesNotExist()
        assertFalse("a rejected consent cannot stay linked", viewModel.driveLinked.value)
        compose.onNodeWithTag("drive_connect_btn").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drive_backup_now").assertDoesNotExist()
    }

    @Test
    fun aSessionChangeTakesThePreviousSessionsDriveSentenceOffTheCard() {
        // The defect measured on the handset (2026-10-31): the card's line answers the last Drive event,
        // and a session change did not reset it, so a cause the new session never met stayed on screen
        // until some Drive call happened to overwrite it. The reachable direction then was a signed-in
        // session that met the missing consent, then the app's own session becoming a guest one. Guest
        // entry is retired now, so the guest half of that switch is put on disk and the app starts on it -
        // the only way a guest session exists after this wave. The composed screen above keeps the session
        // it was built with, so this test now holds the state side of the switch (the new session starts
        // with no sentence inherited from the old one) and no longer the card side of it.
        seedCachedGoogleAccount()
        seedAppSession(guest = false)
        viewModel = MainViewModel(application)
        compose.setContent { MaterialTheme { SettingsScreen(viewModel) } }
        awaitStatus(CONSENT_MISSING)
        compose.onNodeWithText(CONSENT_MISSING).performScrollTo().assertIsDisplayed()

        // The session change, driven through the one persist path this wave still has: the user signs in
        // with another account while the screen is open. persistUser resets the card's sentence, and
        // nothing re-probes Drive, so only that reset can take the old cause off the screen.
        compose.runOnIdle {
            viewModel.loginWithEmail(
                email = "other-session@example.invalid",
                password = "pass",
                onSuccess = {},
                onError = { fail("the fixture login must not fail: $it") }
            )
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
        compose.waitForIdle()

        assertFalse("fixture: the session changed", viewModel.userProfile.value?.isGuest == true)
        assertEquals(
            "fixture: the new session is the one this test signed in",
            "other-session@example.invalid",
            viewModel.userProfile.value?.email
        )
        assertNull("the previous session's cause must not outlive it", viewModel.driveBackupStatus.value)
        compose.onNodeWithText(CONSENT_MISSING).assertDoesNotExist()
        // The line itself stays, on the card's own fallback: the last-backup sentence.
        compose.onNodeWithTag("drive_status").performScrollTo().assertIsDisplayed()
        assertEquals("a session change is not a Drive answer; nothing is probed again", 1, GoogleTokenShadow.requests.get())
    }

    @Test
    fun onlyAnAccountTheAppKnowsIsTalkedAboutInTermsOfConsent() {
        // The whole rule, read straight off the function so a swapped branch is caught: the consent
        // sentence `DriveReconnectTest` asserts stays the answer for an account this app is using, and
        // the guest sentence answers every case where nobody was asked for the consent - including a
        // guest whose handset still has the account cached.
        assertEquals(CONSENT_MISSING, drivePermissionNotice(consentRecoveryPending = true, guestSession = false))
        assertEquals(SIGN_IN_FIRST, drivePermissionNotice(consentRecoveryPending = true, guestSession = true))
        assertEquals(SIGN_IN_FIRST, drivePermissionNotice(consentRecoveryPending = false, guestSession = false))
        assertEquals(SIGN_IN_FIRST, drivePermissionNotice(consentRecoveryPending = false, guestSession = true))
    }

    private fun awaitStatus(expected: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            viewModel.driveBackupStatus.value == expected
        }
        assertEquals(expected, viewModel.driveBackupStatus.value)
    }

    private fun awaitProbe() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.waitForIdle()
            GoogleTokenShadow.requests.get() > 0
        }
    }

    /**
     * The real Google Play services cache file, seeded the way `DriveReconnectTest` seeds it, so
     * `DriveAuth.lastAccount` sees an account this app signed in with once and still has locally.
     */
    private fun seedCachedGoogleAccount() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("defaultGoogleSignInAccount", ACCOUNT_KEY)
            .putString(
                "googleSignInAccount:$ACCOUNT_KEY",
                """{"id":"$ACCOUNT_KEY","email":"$SEED_EMAIL","displayName":"Guest Card Test",""" +
                    """"expirationTime":4102444800,"obfuscatedIdentifier":"$ACCOUNT_KEY",""" +
                    """"grantedScopes":["${DriveAuth.SCOPE}"]}"""
            )
            .commit()
        // Google parses that file once per process and keeps the result, so the singleton holding it has
        // to be dropped before anything reads it here: otherwise this test walks whichever branch the
        // first class to ask left the cache on, and the full suite and a solo run disagree.
        resetGoogleAccountCache()
    }

    /** The app's own stored session, the same preferences [MainViewModel] reads when it starts. */
    private fun seedAppSession(guest: Boolean) {
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("user_id", APP_SESSION_ID)
            .putString("user_email", SEED_EMAIL)
            .putBoolean("is_guest", guest)
            .commit()
    }

    /**
     * The preference files the card reads: Google's own grant cache, Drive's answer, the app session -
     * and the two process-wide caches Google keeps over them.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        resetGoogleAccountCache()
    }

    /**
     * Google reads its own account data through two singletons that live as long as the process: the
     * account holder (`zbn`), which parses the stored account once in its constructor and never reads it
     * again, and the `Storage` it parsed from, which is bound to whichever Application asked first.
     * Robolectric makes a new Application per test and runs every test class in one JVM, so without this
     * reset a fixture reads the previous class's file through the previous class's caches - which is how
     * this class came to fail in the full suite while passing on its own. Dropping both makes the next
     * read go back to what this test just wrote, the way a fresh process would.
     *
     * The names are Google's obfuscated internals, so they are spelled out here rather than hidden: if a
     * field is gone after a library update the reset throws and the fixture fails loudly instead of
     * quietly reading a stale account.
     */
    private fun resetGoogleAccountCache() {
        ReflectionHelpers.setStaticField(GOOGLE_ACCOUNT_HOLDER, GOOGLE_ACCOUNT_HOLDER_FIELD, null)
        ReflectionHelpers.setStaticField(GOOGLE_STORAGE, GOOGLE_STORAGE_FIELD, null)
    }

    /** Google's blocking token request: the one class shadowed, and only the answer is scripted. */
    @Implements(value = GoogleAuthUtil::class, isInAndroidSdk = false)
    class GoogleTokenShadow {
        companion object {
            val requests = AtomicInteger()

            /** Which account Drive asked Google about, so a test can prove the payload it keyed on. */
            val askedAccount = AtomicReference<Account?>(null)

            @JvmStatic
            @Resetter
            fun reset() {
                requests.set(0)
                askedAccount.set(null)
            }

            @JvmStatic
            @Implementation
            @Suppress("UNUSED_PARAMETER")
            fun getToken(context: Context, account: Account, scope: String): String {
                askedAccount.set(account)
                requests.incrementAndGet()
                // Consent for `drive.file` is missing, and Google hands back the intent to ask for it:
                // the payload the Drive card is allowed to build its sentence from.
                throw UserRecoverableAuthException("NEED_REMOTE_CONSENT", Intent(RECOVERY_ACTION))
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

        const val SEED_EMAIL = "drive-guest-card@example.invalid"
        const val ACCOUNT_KEY = "cached-guest-card-account"
        const val APP_SESSION_ID = "app-session-under-test"
        const val RECOVERY_ACTION = "com.example.test.DRIVE_RECOVERY"

        // The app's own copies, asserted verbatim so what the user reads cannot drift unnoticed.
        const val SIGN_IN_FIRST =
            "اول با حسابِ گوگل وارد شو، بعد برنامه را به درایو وصل کن."

        const val CONSENT_MISSING =
            "اجازهٔ نوشتن در گوگل درایو داده نشده است؛ از دکمهٔ اتصال استفاده کن."
    }
}
