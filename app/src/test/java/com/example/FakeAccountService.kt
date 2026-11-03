package com.example

import com.example.data.remote.auth.AccountOutcome
import com.example.data.remote.auth.AccountService

/**
 * The account the flow is tested against. Every answer is a field a test sets, and every call is
 * counted, so a test can prove the app asked for what it claims to have asked for.
 */
class FakeAccountService : AccountService {
    var registerAnswer: AccountOutcome = AccountOutcome.EmailSent
    var signInAnswer: AccountOutcome = AccountOutcome.SignedIn("a@b.com", "uid-1")
    var sendVerificationAnswer: AccountOutcome = AccountOutcome.EmailSent
    var passwordResetAnswer: AccountOutcome = AccountOutcome.EmailSent
    var verified: Boolean = false
    var signedInAs: AccountOutcome = AccountOutcome.SignedIn("a@b.com", "uid-1")
    var uidToReport: String? = "uid-1"
    var emailToReport: String? = "a@b.com"

    var registerCalls = 0
    var signInCalls = 0
    var sendVerificationCalls = 0
    var reloadCalls = 0
    var passwordResetCalls = 0
    var signOutCalls = 0
    var uidCalls = 0

    override suspend fun register(email: String, password: String): AccountOutcome {
        registerCalls += 1
        return registerAnswer
    }

    override suspend fun signIn(email: String, password: String): AccountOutcome {
        signInCalls += 1
        return signInAnswer
    }

    override suspend fun sendVerification(): AccountOutcome {
        sendVerificationCalls += 1
        return sendVerificationAnswer
    }

    override suspend fun isEmailVerified(): Boolean {
        reloadCalls += 1
        return verified
    }

    override suspend fun currentUid(): String? {
        uidCalls += 1
        return if (verified) uidToReport else null
    }

    override suspend fun currentEmail(): String? = if (verified) emailToReport else null

    override suspend fun sendPasswordReset(email: String): AccountOutcome {
        passwordResetCalls += 1
        return passwordResetAnswer
    }

    override fun signOut() {
        signOutCalls += 1
    }

    fun reset() {
        registerAnswer = AccountOutcome.EmailSent
        signInAnswer = AccountOutcome.SignedIn("a@b.com", "uid-1")
        sendVerificationAnswer = AccountOutcome.EmailSent
        passwordResetAnswer = AccountOutcome.EmailSent
        verified = false
        signedInAs = AccountOutcome.SignedIn("a@b.com", "uid-1")
        uidToReport = "uid-1"
        emailToReport = "a@b.com"
        registerCalls = 0
        signInCalls = 0
        sendVerificationCalls = 0
        reloadCalls = 0
        passwordResetCalls = 0
        signOutCalls = 0
        uidCalls = 0
    }
}
