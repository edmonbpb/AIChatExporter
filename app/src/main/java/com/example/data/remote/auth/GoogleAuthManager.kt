package com.example.data.remote.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.BuildConfig
import com.example.data.model.UserProfile
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

sealed class GoogleSignInOutcome {
    object NotConfigured : GoogleSignInOutcome()
    object Cancelled : GoogleSignInOutcome()
    data class SignedIn(val profile: UserProfile) : GoogleSignInOutcome()
    data class Failed(val reasonFa: String) : GoogleSignInOutcome()
}

val GoogleSignInOutcome.messageFa: String
    get() = when (this) {
        GoogleSignInOutcome.NotConfigured -> NOT_CONFIGURED_FA
        GoogleSignInOutcome.Cancelled -> "انتخاب حساب گوگل لغو شد؛ هیچ نشستی ساخته نشد."
        is GoogleSignInOutcome.SignedIn -> "هویت «${profile.email}» تأیید شد."
        is GoogleSignInOutcome.Failed -> "ورود با گوگل کامل نشد: $reasonFa"
    }

object GoogleAuthManager {

    val clientId: String get() = BuildConfig.GOOGLE_CLIENT_ID.trim()

    val isConfigured: Boolean
        get() = clientId.isNotBlank() && !clientId.startsWith(PLACEHOLDER_PREFIX)

    suspend fun signInWithGoogle(context: Context): GoogleSignInOutcome {
        if (!isConfigured) return GoogleSignInOutcome.NotConfigured

        val activity = context.findActivity()
            ?: return GoogleSignInOutcome.Failed(HOST_MISSING_FA)

        val hashedNonce = newHashedNonce()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .setNonce(hashedNonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val credential = CredentialManager.create(activity)
                .getCredential(request = request, context = activity)
                .credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleSignInOutcome.Failed("نوع اعتبارنامهٔ بازگشتی مورد انتظار نبود.")
            } else {
                readProfile(GoogleIdTokenCredential.createFrom(credential.data), hashedNonce)
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleSignInOutcome.Cancelled
        } catch (e: NoCredentialException) {
            // Google handed this app no usable sign-in. That is the whole of what this exception
            // proves: measured on `R58X30GS9QJ` (2026-10-31) it arrived while Google's own chooser was
            // listing two of the user's accounts, because the build was not registered for OAuth. So the
            // sentence may claim neither "no Google account on this device" nor any suspected reason, and
            // the provider's raw words go to logcat instead of the screen.
            Log.w(CREDENTIAL_LOG_TAG, "credential provider offered no credential", e)
            GoogleSignInOutcome.Failed(NO_CREDENTIAL_FA)
        } catch (e: GetCredentialException) {
            // A provider fault is a different fact from "no credential", so it keeps its own sentence.
            // Google's text for it is English and was measured to be wrong about the cause, so it goes
            // to the log, not to the user.
            Log.w(CREDENTIAL_LOG_TAG, "credential provider request failed", e)
            GoogleSignInOutcome.Failed("ارائه‌دهندهٔ اعتبارنامه پاسخی بازنگرداند.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Same rule for the untyped leftovers: what the throwable carries is logged, and the user
            // gets the one sentence this branch is allowed to give.
            Log.w(CREDENTIAL_LOG_TAG, "credential request failed", e)
            GoogleSignInOutcome.Failed("خطای ناشناخته در درخواست اعتبارنامه.")
        }
    }

    /**
     * The signature is not checked here: an unverified token read on the device proves which
     * account the picker handed back for display, and buys no permission to any Google data.
     */
    private fun readProfile(credential: GoogleIdTokenCredential, hashedNonce: String): GoogleSignInOutcome {
        val claims = parseJwtClaims(credential.idToken)
            ?: return GoogleSignInOutcome.Failed("توکن شناسایی قابل خواندن نبود.")

        val parsed = IdTokenClaims(
            audience = readAudience(claims),
            issuer = claims.optString("iss"),
            subject = claims.optString("sub"),
            name = claims.optString("name"),
            email = claims.optString("email"),
            picture = claims.optString("picture"),
            expiresAtSeconds = claims.optLong("exp"),
            nonce = claims.optString("nonce")
        )

        val reason = validateIdTokenClaims(parsed, clientId, hashedNonce, System.currentTimeMillis() / 1000L)
        if (reason != null) return GoogleSignInOutcome.Failed(reason)

        return GoogleSignInOutcome.SignedIn(
            UserProfile(
                id = parsed.subject.ifBlank { credential.id },
                name = parsed.name.ifBlank { credential.displayName ?: "کاربر گوگل" },
                email = parsed.email,
                avatarUrl = parsed.picture.takeIf { it.isNotBlank() },
                isGuest = false,
                isGoogleDriveLinked = false,
                lastSyncTimestamp = System.currentTimeMillis(),
                role = "کاربر گوگل"
            )
        )
    }

    private fun readAudience(claims: JSONObject): List<String> {
        val list = claims.optJSONArray("aud")
        if (list != null) return (0 until list.length()).map { list.optString(it) }
        return listOf(claims.optString("aud"))
    }

    private fun parseJwtClaims(idToken: String): JSONObject? = try {
        val payload = idToken.split('.').getOrNull(1)
        if (payload.isNullOrBlank()) null
        else JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    private fun newHashedNonce(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private const val PLACEHOLDER_PREFIX = "MY_"
}

internal data class IdTokenClaims(
    val audience: List<String>,
    val issuer: String,
    val subject: String,
    val name: String,
    val email: String,
    val picture: String,
    val expiresAtSeconds: Long,
    val nonce: String
)

/** Null means the token is accepted; anything else is the reason to show the user. */
internal fun validateIdTokenClaims(
    claims: IdTokenClaims,
    clientId: String,
    hashedNonce: String,
    nowSeconds: Long
): String? = when {
    claims.expiresAtSeconds <= nowSeconds -> "توکن شناسایی منقضی شده بود."
    claims.audience.none { it == clientId } -> "توکن برای همین برنامه صادر نشده است."
    claims.issuer !in TRUSTED_ISSUERS -> "صادرکنندهٔ توکن ناشناس بود."
    claims.nonce != hashedNonce -> "توکن با درخواست این ورود مطابقت ندارد."
    claims.email.isBlank() -> "توکن نشانی ایمیل نداشت."
    else -> null
}

private val TRUSTED_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")

private const val HOST_MISSING_FA =
    "صفحهٔ میزبان در دسترس نیست؛ درخواست حساب گوگل به یک Activity نیاز دارد."

private const val NOT_CONFIGURED_FA =
    "ورود با حساب گوگل در این نسخه پیکربندی نشده است. با ایمیل و رمز عبور ادامه بده."

private const val CREDENTIAL_LOG_TAG = "GoogleSignIn"

// B-45, and the one sentence the sign-in path may show when Google offers nothing usable (Item D2). It
// states the observed fact and claims nothing about the handset — the phone was measured holding two
// Google accounts while this still arrived — and names no suspected reason (an unregistered package, a
// provider fault), because no typed value ever tells us which one it was.
internal const val NO_CREDENTIAL_FA =
    "گوگل برای این برنامه ورودِ قابلِ استفاده‌ای پیدا نکرد؛ روی این دستگاه دوباره تلاش کن."
