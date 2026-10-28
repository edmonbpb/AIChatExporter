package com.example.data.remote.drive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * The one permission this app asks Google for: its own files in Drive, nothing else. `drive.file`
 * means the app can see and write the files it created (and the folder it created), and nothing
 * else in the account.
 *
 * The sign-in the rest of the app already does (Credential Manager) proves who the user is but
 * carries no Drive permission, so this is a second, separate consent — launched from the settings
 * card, never silently.
 */
internal object DriveAuth {

    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    fun client(context: Context): GoogleSignInClient = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(SCOPE))
            .build()
    )

    fun lastAccount(context: Context): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    /** Cached hint only; a token request may still require fresh consent. */
    fun hasConsent(context: Context): Boolean {
        val granted = lastAccount(context)?.grantedScopes ?: return false
        return granted.any { it.scopeUri == SCOPE }
    }

    sealed interface TokenOutcome {
        data class Token(val value: String) : TokenOutcome
        data class PermissionRequired(val recoveryIntent: Intent?) : TokenOutcome
        data object NetworkFailure : TokenOutcome
        data object Failure : TokenOutcome
    }

    /** Blocking; call from an IO dispatcher. Never persist or log the token. */
    fun accessToken(context: Context): TokenOutcome = try {
        val account = lastAccount(context)?.account
        if (account == null) {
            TokenOutcome.PermissionRequired(null)
        } else {
            val token = GoogleAuthUtil.getToken(context, account, "oauth2:$SCOPE")
            if (token.isNullOrBlank()) TokenOutcome.Failure else TokenOutcome.Token(token)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: UserRecoverableAuthException) {
        TokenOutcome.PermissionRequired(e.intent)
    } catch (_: IOException) {
        TokenOutcome.NetworkFailure
    } catch (_: Exception) {
        TokenOutcome.Failure
    }
}
