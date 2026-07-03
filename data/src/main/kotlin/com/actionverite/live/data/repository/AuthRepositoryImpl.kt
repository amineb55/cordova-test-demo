package com.actionverite.live.data.repository

import com.actionverite.live.data.common.ForegroundActivityProvider
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.AuthProvider
import com.actionverite.live.domain.repository.AuthCredential
import com.actionverite.live.domain.repository.AuthRepository
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.google.firebase.auth.AuthCredential as FirebaseCredential

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val activityProvider: ForegroundActivityProvider,
) : AuthRepository {

    override val authState: Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override fun currentUserId(): String? = auth.currentUser?.uid

    override suspend fun signIn(credential: AuthCredential): DomainResult<String> = firebaseCall {
        when (credential.provider) {
            AuthProvider.EMAIL -> {
                val email = requireNotNull(credential.email) { "Email required" }
                val password = requireNotNull(credential.password) { "Password required" }
                auth.signInWithEmailAndPassword(email, password).await()
            }
            AuthProvider.PHONE -> {
                val phoneCredential = PhoneAuthProvider.getCredential(
                    requireNotNull(credential.phoneVerificationId) { "verificationId required" },
                    requireNotNull(credential.smsCode) { "smsCode required" },
                )
                auth.signInWithCredential(phoneCredential).await()
            }
            else -> auth.signInWithCredential(credential.toFirebase()).await()
        }
        auth.currentUser?.uid ?: error("No user after sign-in")
    }

    override suspend fun signInAnonymously(): DomainResult<String> = firebaseCall {
        auth.signInAnonymously().await().user?.uid ?: error("No anonymous user")
    }

    override suspend fun startPhoneVerification(phoneNumber: String): DomainResult<String> {
        val activity = activityProvider.current()
            ?: return DomainResult.Failure(DomainError.Validation("No foreground screen for verification."))
        return firebaseCall {
            suspendCancellableCoroutine { cont ->
                val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                        if (cont.isActive) cont.resume(id)
                    }

                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        // Instant verification: still surface the id when available.
                        credential.smsCode?.let { /* handled by UI */ }
                    }

                    override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }
                }
                val options = PhoneAuthOptions.newBuilder(auth)
                    .setPhoneNumber(phoneNumber)
                    .setTimeout(60L, TimeUnit.SECONDS)
                    .setActivity(activity)
                    .setCallbacks(callbacks)
                    .build()
                PhoneAuthProvider.verifyPhoneNumber(options)
            }
        }
    }

    override suspend fun linkProvider(credential: AuthCredential): DomainResult<Unit> = firebaseCall {
        val user = auth.currentUser ?: error("Not signed in")
        user.linkWithCredential(credential.toFirebase()).await()
        Unit
    }

    override suspend fun sendEmailVerification(): DomainResult<Unit> = firebaseCall {
        auth.currentUser?.sendEmailVerification()?.await()
        Unit
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    override suspend fun deleteAccount(): DomainResult<Unit> = firebaseCall {
        auth.currentUser?.delete()?.await()
        Unit
    }

    private fun AuthCredential.toFirebase(): FirebaseCredential = when (provider) {
        AuthProvider.GOOGLE -> GoogleAuthProvider.getCredential(
            requireNotNull(idToken) { "Google idToken required" }, null,
        )
        AuthProvider.FACEBOOK -> FacebookAuthProvider.getCredential(
            requireNotNull(accessToken) { "Facebook accessToken required" },
        )
        AuthProvider.APPLE -> {
            val builder = OAuthProvider.newCredentialBuilder("apple.com")
            builder.setIdToken(requireNotNull(idToken) { "Apple idToken required" })
            accessToken?.let { builder.setAccessToken(it) }
            builder.build()
        }
        else -> error("Provider $provider is not credential-based")
    }
}
