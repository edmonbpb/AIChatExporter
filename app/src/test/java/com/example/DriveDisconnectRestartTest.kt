package com.example

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.drive.DriveAuth
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/**
 * B-47 on the JVM: the backup card's disconnect used to last only until the process died, because a
 * new process rebuilt "connected" from Google's own cached `drive.file` grant - a grant this app can
 * never revoke. The fix stores the user's decision (`drive_disconnected` in `aichat_auth_prefs`) and
 * lets it outrank the cached grant at cold start, until a connect press or a new session clears it.
 *
 * No Google token call is made here at all (the card is read straight off the view model, never
 * rendered), so nothing needs shadowing: only the real preference files and Google's real account
 * cache drive the fixture, the way `DriveReconnectTest` seeds its stale grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class DriveDisconnectRestartTest {

    private lateinit var application: Application
    private lateinit var authPrefs: SharedPreferences
    private val created = mutableListOf<MainViewModel>()

    /** The only seam between "this process" and "a new process": a freshly built view model. */
    private fun newViewModel(): MainViewModel =
        MainViewModel(application).also { created += it }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        clearAccountState()
        // The app's own session is on disk before anything starts, so "the account stays" in test 2
        // is measured against a real stored email, not against the absence of one.
        authPrefs = application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        authPrefs.edit()
            .putString("user_id", APP_SESSION_ID)
            .putString("user_email", SEED_EMAIL)
            .putBoolean("is_guest", false)
            .commit()
        // The handset state the bug was measured on: Google's local cache still holds the drive.file
        // grant this app never revoked.
        seedCachedGoogleGrant()
        assertNotNull("fixture: Google's local cache holds an account", DriveAuth.lastAccount(application))
        assertTrue("fixture must reproduce the cached drive.file grant", DriveAuth.hasConsent(application))
    }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        if (::application.isInitialized) {
            clearAccountState()
        }
    }

    @Test
    fun aCachedGoogleGrantRebuildsTheCardAsConnectedOnStartup() {
        val viewModel = newViewModel()
        assertTrue("a fresh process with a cached grant starts connected", viewModel.driveLinked.value)
    }

    @Test
    fun disconnectDropsTheCardAndStoresTheDecisionButKeepsTheAccount() {
        val viewModel = newViewModel()
        viewModel.disconnectGoogleAccount()

        assertFalse(viewModel.driveLinked.value)
        assertTrue("the user's decision must outlive this process",
            authPrefs.getBoolean("drive_disconnected", false))
        assertEquals("disconnect is not sign-out: the account stays",
            SEED_EMAIL, authPrefs.getString("user_email", null))
        assertNotNull(viewModel.userProfile.value)
    }

    @Test
    fun theStoredDecisionSurvivesANewProcess() {
        // The defect itself: without the marker this reads Google's cached grant back as "connected".
        newViewModel().disconnectGoogleAccount()
        val restarted = newViewModel()
        assertFalse("a new process must not rebuild connected over the user's disconnect",
            restarted.driveLinked.value)
    }

    @Test
    fun pressingConnectAgainClearsTheMarkerAndTheCardComesBack() {
        val viewModel = newViewModel()
        viewModel.disconnectGoogleAccount()
        // A fresh connect is a fresh ask (under this harness the guard always takes it: no consent is
        // pending), so the stored disconnect must lose its rank here.
        assertTrue("beginDriveConsent must accept the fresh ask", viewModel.beginDriveConsent())
        assertFalse("the connect press removes the marker",
            authPrefs.getBoolean("drive_disconnected", false))

        val restarted = newViewModel()
        assertTrue("with the marker gone, the cached grant speaks again", restarted.driveLinked.value)
    }

    @Test
    fun aNewSessionInheritsNoDisconnectDecision() {
        val viewModel = newViewModel()
        viewModel.disconnectGoogleAccount()

        // The email login carries an artificial delay(800): walk the paused main looper past it.
        viewModel.loginWithEmail(
            email = "next-session@example.invalid",
            password = "pass",
            onSuccess = {},
            onError = { fail("the fixture login must not fail: $it") }
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))

        assertEquals("next-session@example.invalid", viewModel.userProfile.value?.email)
        assertFalse("a new session inherits no disconnect from the old one",
            authPrefs.getBoolean("drive_disconnected", false))
    }

    /** The real Google Play services cache file, seeded the way `DriveReconnectTest` seeds it. */
    private fun seedCachedGoogleGrant() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE)
            .edit().clear()
            .putString("defaultGoogleSignInAccount", ACCOUNT_KEY)
            .putString(
                "googleSignInAccount:$ACCOUNT_KEY",
                """{"id":"$ACCOUNT_KEY","email":"$SEED_EMAIL","displayName":"Disconnect Restart Test",""" +
                    """"expirationTime":4102444800,"obfuscatedIdentifier":"$ACCOUNT_KEY",""" +
                    """"grantedScopes":["${DriveAuth.SCOPE}"]}"""
            )
            .commit()
        // Google parses that file once per process and keeps the result, so both singletons holding it
        // must be dropped before anything reads it: otherwise this test walks whichever cache the first
        // class to ask left behind, and the full suite and a solo run disagree.
        resetGoogleAccountCache()
    }

    /**
     * The preference files the card reads: Google's own grant cache, Drive's answer, the app session -
     * and the two process-wide caches Google keeps over them. Cleared in `@Before` and `@After`, the
     * way `DriveGuestCardTest` does, or this class turns the full suite red while passing on its own.
     */
    private fun clearAccountState() {
        application.getSharedPreferences(SIGNIN_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(DRIVE_BACKUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        resetGoogleAccountCache()
    }

    /**
     * Google's two process-wide caches - the account holder (`zbn`) and the `Storage` it parsed from -
     * reset exactly the way `DriveGuestCardTest.resetGoogleAccountCache()` does. The obfuscated names
     * are spelled out so a library update that drops a field fails the fixture loudly.
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

        const val SEED_EMAIL = "drive-disconnect-restart@example.invalid"
        const val ACCOUNT_KEY = "disconnect-restart-account"
        const val APP_SESSION_ID = "app-session-under-test"
    }
}
