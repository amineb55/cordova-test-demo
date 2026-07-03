package com.actionverite.live.feature.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.actionverite.live.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Wraps the Jetpack Credential Manager + Google Identity flow to obtain a Google
 * ID token, which is then exchanged for a Firebase session by [AuthViewModel].
 * The web client id comes from BuildConfig (see secrets.properties.example).
 */
class GoogleAuthClient(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)

    /** Returns a Google ID token, or throws if the user cancels / no account. */
    suspend fun getIdToken(): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = credentialManager.getCredential(context, request)
        val credential = response.credential
        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        return googleCredential.idToken
    }
}
