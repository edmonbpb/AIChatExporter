package com.example.data.remote.drive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

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

    /** True when this device already holds a consent for the scope, so no window has to open. */
    fun hasConsent(context: Context): Boolean {
        val granted = lastAccount(context)?.grantedScopes ?: return false
        return granted.any { it.scopeUri == SCOPE }
    }

    /** Blocking; call from an IO dispatcher. Null means the consent is missing or was revoked. */
    fun accessToken(context: Context): String? {
        val account = lastAccount(context)?.account ?: return null
        return try {
            GoogleAuthUtil.getToken(context, account, "oauth2:$SCOPE")
        } catch (_: Exception) {
            null
        }
    }
}
