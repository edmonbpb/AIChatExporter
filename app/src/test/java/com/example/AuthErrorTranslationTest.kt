package com.example

import com.example.data.remote.auth.FIREBASE_MIN_PASSWORD
import com.example.data.remote.auth.authErrorFa
import com.example.data.remote.auth.emailLooksValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorTranslationTest {

    @Test
    fun aWrongPasswordIsNamedAsAWrongPassword() {
        // The sentence must not be the provider's English one.
        assertNotEquals("ERROR_WRONG_PASSWORD", authErrorFa("ERROR_WRONG_PASSWORD"))
        assertTrue(authErrorFa("ERROR_WRONG_PASSWORD").contains("رمز"))
    }

    @Test
    fun theCombinedLoginFailureIsNamedAndBlamesNeitherHalf() {
        // Enumeration protection makes this the only code a password check answers with, so the
        // sentence must stay true whether the address is unknown or the password is wrong.
        val sentence = authErrorFa("ERROR_INVALID_LOGIN_CREDENTIAL")
        assertNotEquals("ERROR_INVALID_LOGIN_CREDENTIAL", sentence)
        assertTrue(sentence.contains("ایمیل"))
        assertTrue(sentence.contains("رمز"))
    }

    @Test
    fun anUnknownCodeStillAnswersInPersianAndBlamesNothingSpecific() {
        val sentence = authErrorFa("ERROR_SOMETHING_NEW")
        assertTrue(sentence.isNotBlank())
        assertFalse(sentence.contains("ERROR_SOMETHING_NEW"))
    }

    @Test
    fun aMissingCodeIsStillAnswered() {
        assertTrue(authErrorFa(null).isNotBlank())
    }

    @Test
    fun thePasswordFloorIsTheProvidersOwnSix() {
        assertEquals(6, FIREBASE_MIN_PASSWORD)
    }

    @Test
    fun anAddressWithNoDomainIsRefusedBeforeAnyCall() {
        assertTrue(emailLooksValid("sbedmon1@gmail.com"))
        assertFalse(emailLooksValid("sbedmon1"))
        assertFalse(emailLooksValid("sbedmon1@"))
        assertFalse(emailLooksValid("@gmail.com"))
        assertFalse(emailLooksValid("   "))
        assertFalse(emailLooksValid(""))
    }
}
