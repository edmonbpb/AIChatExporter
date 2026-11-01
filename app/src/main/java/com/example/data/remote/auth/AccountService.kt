package com.example.data.remote.auth

/**
 * What an account call can answer. Every case but [Failed] and [Unavailable] is a fact about the
 * account, never a sentence to show; the two failure cases carry the only Persian an auth call may
 * put on screen.
 */
sealed interface AccountOutcome {
    /** The account exists and its address is already confirmed: this session may open. */
    data class SignedIn(val email: String, val uid: String) : AccountOutcome

    /** The account exists, the address does not: the app must wait on the e-mail. */
    data class NeedsVerification(val email: String) : AccountOutcome

    /** Nothing to sign in with — a mail was handed to the provider to deliver. */
    data object EmailSent : AccountOutcome

    data class Failed(val messageFa: String) : AccountOutcome

    /** This build carries no Firebase configuration, so no account answer is possible at all. */
    data object Unavailable : AccountOutcome
}

/**
 * The one sentence the account path may show when the build carries no Firebase configuration. It
 * names the build, not the user's credentials, because an unconfigured build says nothing about the
 * address they typed.
 */
const val ACCOUNT_UNAVAILABLE_FA =
    "این بسته به سرویس حساب وصل نشده است؛ بستۀ به‌روز را از همان جایی که گرفتی دانلود کن."

/**
 * Every account rule the app needs, without a Firebase type in sight. Keeping the surface this small
 * is what lets the whole flow be tested on the JVM with a fake.
 */
interface AccountService {
    suspend fun register(email: String, password: String): AccountOutcome
    suspend fun signIn(email: String, password: String): AccountOutcome
    suspend fun sendVerification(): AccountOutcome
    suspend fun isEmailVerified(): Boolean
    suspend fun sendPasswordReset(email: String): AccountOutcome

    /** Drops this device's claim to the account. Never touches the user's library. */
    fun signOut()
}

object UnconfiguredAccountService : AccountService {
    override suspend fun register(email: String, password: String): AccountOutcome =
        AccountOutcome.Unavailable

    override suspend fun signIn(email: String, password: String): AccountOutcome =
        AccountOutcome.Unavailable

    override suspend fun sendVerification(): AccountOutcome = AccountOutcome.Unavailable

    override suspend fun isEmailVerified(): Boolean = false

    override suspend fun sendPasswordReset(email: String): AccountOutcome =
        AccountOutcome.Unavailable

    override fun signOut() {}
}
