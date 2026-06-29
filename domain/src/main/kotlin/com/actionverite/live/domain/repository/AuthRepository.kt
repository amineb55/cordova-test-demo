package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.AuthProvider
import kotlinx.coroutines.flow.Flow

/** Opaque credential token obtained from a provider SDK and exchanged for a session. */
data class AuthCredential(
    val provider: AuthProvider,
    val idToken: String? = null,
    val accessToken: String? = null,
    val email: String? = null,
    val password: String? = null,
    val phoneVerificationId: String? = null,
    val smsCode: String? = null,
)

/** Authentication boundary. Implemented by Firebase Auth in :data. */
interface AuthRepository {
    /** Emits the current signed-in user id, or null when signed out. */
    val authState: Flow<String?>

    fun currentUserId(): String?

    suspend fun signIn(credential: AuthCredential): DomainResult<String>

    suspend fun signInAnonymously(): DomainResult<String>

    /** Starts phone verification; returns a verificationId to combine with the SMS code. */
    suspend fun startPhoneVerification(phoneNumber: String): DomainResult<String>

    suspend fun linkProvider(credential: AuthCredential): DomainResult<Unit>

    suspend fun sendEmailVerification(): DomainResult<Unit>

    suspend fun signOut()

    suspend fun deleteAccount(): DomainResult<Unit>
}
