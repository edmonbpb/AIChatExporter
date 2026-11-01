package com.example

import com.example.data.remote.auth.GoogleSignInOutcome
import com.example.data.remote.auth.NO_CREDENTIAL_FA
import com.example.data.remote.auth.messageFa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-45's sign-in half, kept as a byte guard rather than a simulation.
 *
 * Measured on `R58X30GS9QJ` (2026-10-31): the screen claimed no Google account was found on the device
 * while Google's own chooser was listing two of the user's accounts, because the build's package had
 * not been registered for OAuth. The provider reports that as `NoCredentialException`, which carries no
 * typed reason, so the sentence may claim nothing about the handset and names no suspected cause. The
 * AccountManager question that Item D added to decide between two sentences was removed for the same
 * reason: it could not see Google's accounts on a targetSdk 36 handset without the authenticator, its
 * read ran on the UI thread, and every failure of that read fell back to the very sentence B-45 is about.
 *
 * What is NOT covered, and knowingly: no test drives `signInWithGoogle` itself, because the class builds
 * its own `CredentialManager` and nothing in the contract's file list opens that seam. These tests hold
 * the exact bytes the user would read and how they are rendered, so the sentence cannot drift silently;
 * the exception-to-sentence wiring is covered by reading only.
 */
class GoogleSignInCauseTest {

    @Test
    fun theNoCredentialSentenceStopsClaimingAnythingAboutTheHandset() {
        assertFalse(
            "the retired accusation must not come back through this sentence",
            NO_CREDENTIAL_FA.contains(RETIRED_DEVICE_CLAIM)
        )
        assertFalse(
            "guest mode is gone from the auth path, so this sentence must not name it",
            NO_CREDENTIAL_FA.contains(GUEST_WORD)
        )
        assertTrue("the way out is the retry, not a guest session", NO_CREDENTIAL_FA.contains(WAY_OUT))
        assertEquals(
            "one sentence, no reason guessed inside it",
            1,
            NO_CREDENTIAL_FA.count { it == '؛' }
        )
    }

    @Test
    fun aFailedSignInRendersThePrefixTheScreenShows() {
        val outcome = GoogleSignInOutcome.Failed(NO_CREDENTIAL_FA)

        assertTrue(
            "the user reads the prefix plus this exact sentence",
            outcome.messageFa.endsWith(NO_CREDENTIAL_FA)
        )
        assertNotEquals(
            "a Failed outcome is never shown bare",
            NO_CREDENTIAL_FA,
            outcome.messageFa
        )
        assertTrue(outcome.messageFa.length > NO_CREDENTIAL_FA.length)
    }

    private companion object {
        // The bytes of the claim the phone proved false, asserted absent rather than restated in full.
        const val RETIRED_DEVICE_CLAIM = "پیدا نشد"

        // The retired way out and the one that replaced it, both read as substrings so the sentence
        // can be rephrased around them without the guard going quiet.
        const val GUEST_WORD = "مهمان"
        const val WAY_OUT = "دوباره تلاش کن"
    }
}
