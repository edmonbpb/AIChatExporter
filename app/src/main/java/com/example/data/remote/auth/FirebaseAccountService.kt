package com.example.data.remote.auth

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The provider's own floor. Anything lower and `createUser` answers before the network does. */
const val FIREBASE_MIN_PASSWORD = 6

/**
 * A shape check only, run before the call so a stray tap costs no network and no rate-limit against
 * the real account. The provider still decides what is a usable address.
 */
internal fun emailLooksValid(email: String): Boolean {
    val value = email.trim()
    val local = value.substringBeforeLast('@', "")
    val domain = value.substringAfterLast('@', "")
    return local.isNotBlank() && domain.contains('.') &&
        !domain.startsWith('.') && !domain.endsWith('.') && !value.contains(' ')
}

/**
 * What the provider's code means, in the one register the app shows. Nothing here claims a cause the
 * code does not prove: `ERROR_USER_NOT_FOUND` says the address is not in this app's account list, and
 * stays out of any guess about why.
 */
internal fun authErrorFa(errorCode: String?): String = when (errorCode) {
    "ERROR_INVALID_EMAIL" -> "نشانی ایمیل قابل قبول نبود؛ آن را بررسی کن."
    "ERROR_EMAIL_ALREADY_IN_USE" -> "با همین نشانی قبلاً حساب ساخته شده است؛ وارد شو یا رمز را بازیابی کن."
    "ERROR_WEAK_PASSWORD" -> "رمز عبور ضعیف است؛ حداقل ۶ کاراکتر لازم دارد."
    "ERROR_USER_NOT_FOUND" -> "چنین حسابی در این برنامه ثبت نشده است."
    "ERROR_WRONG_PASSWORD" -> "رمز عبور درست نبود."
    // Enumeration protection makes this the only code a password check answers with, and it covers
    // both "no such account" and "wrong password" - so the sentence has to stay true for both.
    "ERROR_INVALID_LOGIN_CREDENTIAL" -> "ایمیل یا رمز عبور درست نبود."
    "ERROR_INVALID_CREDENTIAL" -> "ایمیل یا رمز عبور قابل استفاده نبود."
    "ERROR_USER_DISABLED" -> "این حساب غیرفعال شده است."
    "ERROR_NETWORK_REQUEST_FAILED" -> "ارتباط با سرویس حساب برقرار نشد؛ اینترنت را بررسی کن."
    "ERROR_TOO_MANY_REQUESTS" -> "چند بار پشت هم تلاش شد؛ کمی بعد دوباره امتحان کن."
    null -> "سرویس حساب دلیل خطا را نگفت؛ دوباره امتحان کن."
    else -> "کار با حساب انجام نشد؛ دوباره امتحان کن."
}

/**
 * What a reset request answers when the provider refuses. `ERROR_USER_NOT_FOUND` is masked as
 * [AccountOutcome.EmailSent]: the reset form is the one place that must not confirm which addresses
 * have an account here, so it answers the same for a stranger as for the owner. Every other cause
 * keeps the provider's own sentence, because a network or rate-limit failure is not a secret.
 *
 * Split out of [FirebaseAccountService.sendPasswordReset] so the mask itself has a JVM witness: the
 * whole road needs a Firebase project, this one decision does not.
 */
internal fun resetOutcomeFor(errorCode: String?): AccountOutcome =
    if (errorCode == "ERROR_USER_NOT_FOUND") AccountOutcome.EmailSent
    else AccountOutcome.Failed(authErrorFa(errorCode))

/**
 * The only file in the app that imports Firebase. `build/` and `verify/` scripts and every screen go
 * through AccountService instead, so a JVM test never needs a Firebase project.
 */
class FirebaseAccountService(private val auth: FirebaseAuth) : AccountService {

    override suspend fun register(email: String, password: String): AccountOutcome {
        val address = email.trim()
        if (!emailLooksValid(address)) return AccountOutcome.Failed(EMAIL_INVALID_FA)
        if (password.length < FIREBASE_MIN_PASSWORD) {
            return AccountOutcome.Failed(PASSWORD_SHORT_FA)
        }
        return try {
            val user = await(auth.createUserWithEmailAndPassword(address, password)).user
                ?: return AccountOutcome.Failed(UNKNOWN_FA)
            sendVerificationBestEffort(user)
            AccountOutcome.NeedsVerification(user.email ?: address)
        } catch (e: FirebaseAuthException) {
            AccountOutcome.Failed(authErrorFa(e.errorCode))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccountOutcome.Failed(UNKNOWN_FA)
        }
    }

    override suspend fun signIn(email: String, password: String): AccountOutcome {
        val address = email.trim()
        if (!emailLooksValid(address)) return AccountOutcome.Failed(EMAIL_INVALID_FA)
        return try {
            val user = await(auth.signInWithEmailAndPassword(address, password)).user
                ?: return AccountOutcome.Failed(UNKNOWN_FA)
            if (user.isEmailVerified) {
                AccountOutcome.SignedIn(user.email ?: address, user.uid)
            } else {
                // The account exists and the password was right, so the wait is on the mail, not on
                // the credentials. Sending again here is what makes the screen's own mail current.
                sendVerificationBestEffort(user)
                AccountOutcome.NeedsVerification(user.email ?: address)
            }
        } catch (e: FirebaseAuthException) {
            AccountOutcome.Failed(authErrorFa(e.errorCode))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccountOutcome.Failed(UNKNOWN_FA)
        }
    }

    override suspend fun sendVerification(): AccountOutcome {
        val user = auth.currentUser ?: return AccountOutcome.Failed(NO_SESSION_FA)
        return try {
            sendVerificationTo(user)
            AccountOutcome.EmailSent
        } catch (e: FirebaseAuthException) {
            AccountOutcome.Failed(authErrorFa(e.errorCode))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccountOutcome.Failed(UNKNOWN_FA)
        }
    }

    override suspend fun isEmailVerified(): Boolean {
        val user = auth.currentUser ?: return false
        return try {
            await(user.reload())
            user.isEmailVerified
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun sendPasswordReset(email: String): AccountOutcome {
        val address = email.trim()
        if (!emailLooksValid(address)) return AccountOutcome.Failed(EMAIL_INVALID_FA)
        return try {
            await(auth.sendPasswordResetEmail(address))
            AccountOutcome.EmailSent
        } catch (e: FirebaseAuthException) {
            // The provider answers "no such user" here for an address that never had an account, and
            // the reset mail is the one place that must not confirm which addresses exist.
            resetOutcomeFor(e.errorCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AccountOutcome.Failed(UNKNOWN_FA)
        }
    }

    override fun signOut() = auth.signOut()

    /**
     * The mail is opened in the browser, which flips the flag on Google's side; the app learns about
     * it on the next `isEmailVerified()` call. No intent filter is needed because nothing has to
     * arrive back inside this process.
     */
    private suspend fun sendVerificationTo(user: com.google.firebase.auth.FirebaseUser) {
        await(user.sendEmailVerification())
    }

    /**
     * On the credential paths the mail is a follow-up, not the fact being reported: `NeedsVerification`
     * stays true when the provider refuses to send it (its own rate limit), and claiming the account
     * was not made would send the user into a retry that answers "already registered". A cancelled
     * caller is still the one thing that stops the answer.
     */
    private suspend fun sendVerificationBestEffort(user: com.google.firebase.auth.FirebaseUser) {
        try {
            sendVerificationTo(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the account's verification state is what the outcome claims, not the mail's delivery
        }
    }

    private suspend fun <T> await(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnCompleteListener { completed ->
            if (!cont.isActive) return@addOnCompleteListener
            if (completed.isSuccessful) cont.resume(completed.result)
            else cont.resumeWithException(
                completed.exception ?: IllegalStateException("the auth task failed without an exception")
            )
        }
    }

    private companion object {
        const val EMAIL_INVALID_FA = "نشانی ایمیل کامل نیست؛ name@example.com را شکل ببین."
        const val PASSWORD_SHORT_FA =
            "رمز عبور باید حداقل ۶ کاراکتر باشد؛ همان چیزی که سرویس حساب می‌پذیرد."
        const val NO_SESSION_FA = "نشستِ تأییدنشده‌ای باز نمانده است؛ دوباره حساب بساز یا وارد شو."
        const val UNKNOWN_FA = "کار با حساب انجام نشد؛ کمی بعد دوباره امتحان کن."
    }
}

/**
 * Chosen once, at the view model's door. A build that carried no `google-services.json` has no
 * `FirebaseApp` and gets the unconfigured answer instead of a crash at the first tap — which is the
 * state every hand-out build is in until the file is placed in `app/`.
 */
fun accountServiceFor(context: Context): AccountService =
    if (FirebaseApp.getApps(context).isEmpty()) UnconfiguredAccountService
    else FirebaseAccountService(FirebaseAuth.getInstance())
