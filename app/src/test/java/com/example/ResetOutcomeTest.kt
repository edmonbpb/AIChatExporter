package com.example

import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.authErrorFa
import com.example.data.remote.auth.resetOutcomeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enumeration rule, on the one decision that carries it: `resetOutcomeFor` turns the provider's
 * "no such user" into the same "a mail was sent" answer every other request gets, so the reset form
 * cannot be used to ask which addresses have an account here. Every other code keeps its own sentence,
 * because a network or rate-limit failure is not a secret worth hiding.
 *
 * Pure function, no Firebase project and no device: `sendPasswordReset` itself needs both, which is
 * why this decision was lifted out of it.
 */
class ResetOutcomeTest {

    @Test
    fun anAddressWithNoAccountStillAnswersThatAMailWentOut() {
        assertEquals(AccountOutcome.EmailSent, resetOutcomeFor(NOT_FOUND))
    }

    @Test
    fun aNetworkFailureKeepsItsOwnSentenceAndIsNotMaskedAsSent() {
        val outcome = resetOutcomeFor(NETWORK)
        assertTrue("a network failure is a failure: $outcome", outcome is AccountOutcome.Failed)
        val message = (outcome as AccountOutcome.Failed).messageFa
        assertEquals(authErrorFa(NETWORK), message)
        // The mask is for the not-found code alone: a failure must not borrow the stranger's answer.
        assertNotEquals("only the not-found code is masked", authErrorFa(NOT_FOUND), message)
    }

    @Test
    fun aProviderThatNamesNoCodeIsAFailureToo() {
        assertTrue(resetOutcomeFor(null) is AccountOutcome.Failed)
    }

    private companion object {
        // The provider's own codes, spelled as `FirebaseAuthException.errorCode` spells them.
        const val NOT_FOUND = "ERROR_USER_NOT_FOUND"
        const val NETWORK = "ERROR_NETWORK_REQUEST_FAILED"
    }
}
