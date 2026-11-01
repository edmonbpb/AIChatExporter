package com.example

import com.example.data.model.UserProfile
import com.example.data.remote.auth.GoogleSignInOutcome
import com.example.ui.viewmodel.applyGoogleSignInOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The half of Google sign-in that used to be untestable on the JVM: whether a real profile ever
 * reaches the store. `loginWithGoogle` builds its own `CredentialManager`, so the outcome-to-store
 * step was split out into `applyGoogleSignInOutcome` and is exercised here without a phone. The
 * persist lambda stands in for `persistUser`: counted, so "it ran twice" or "it ran for a cancelled
 * picker" is a failure rather than a detail nobody looks at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleProfileTest {

    @Test
    fun aSignedInOutcomeComesBackAndIsPersistedExactlyOnce() {
        val persisted = mutableListOf<UserProfile>()

        val result = applyGoogleSignInOutcome(GoogleSignInOutcome.SignedIn(GOOGLE_PROFILE)) {
            persisted += it
        }

        assertSame("the caller gets the very profile it will show", GOOGLE_PROFILE, result)
        assertEquals("one sign-in writes one session", listOf(GOOGLE_PROFILE), persisted)
    }

    @Test
    fun everyOutcomeThatCarriesNoProfilePersistsNothing() {
        val persisted = mutableListOf<UserProfile>()
        val outcomes = listOf(
            GoogleSignInOutcome.NotConfigured,
            GoogleSignInOutcome.Cancelled,
            GoogleSignInOutcome.Failed("دوباره تلاش کن")
        )

        outcomes.forEach { outcome ->
            assertNull(
                "«${outcome::class.simpleName}» must not be turned into a session",
                applyGoogleSignInOutcome(outcome) { persisted += it }
            )
        }

        assertEquals("nothing but a signed-in profile may write the store", emptyList<UserProfile>(), persisted)
    }

    private companion object {
        val GOOGLE_PROFILE = UserProfile(
            id = "google-subject-1",
            name = "Ali Reza",
            email = "ali.reza@example.com",
            isGuest = false,
            role = "کاربر گوگل"
        )
    }
}
