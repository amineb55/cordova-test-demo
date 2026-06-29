package com.actionverite.live.data.repository

import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val messaging: FirebaseMessaging,
) : com.actionverite.live.domain.repository.NotificationRepository {

    private val users get() = firestore.collection(FirestorePaths.USERS)

    override suspend fun registerDeviceToken(): DomainResult<Unit> = withUser { uid ->
        val token = messaging.token.await()
        users.document(uid).update("fcmTokens", FieldValue.arrayUnion(token)).await()
        Unit
    }

    override suspend fun unregisterDeviceToken(): DomainResult<Unit> = withUser { uid ->
        val token = messaging.token.await()
        users.document(uid).update("fcmTokens", FieldValue.arrayRemove(token)).await()
        Unit
    }

    override suspend fun setFriendInvitesEnabled(enabled: Boolean): DomainResult<Unit> = firebaseCall {
        if (enabled) messaging.subscribeToTopic(TOPIC_FRIEND_INVITES).await()
        else messaging.unsubscribeFromTopic(TOPIC_FRIEND_INVITES).await()
        Unit
    }

    private suspend inline fun withUser(crossinline block: suspend (String) -> Unit): DomainResult<Unit> {
        val uid = auth.currentUser?.uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return firebaseCall { block(uid) }
    }

    private companion object {
        const val TOPIC_FRIEND_INVITES = "friend_invites"
    }
}
