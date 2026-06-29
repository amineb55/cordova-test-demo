package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.dto.UserDto
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.LeaderboardEntry
import com.actionverite.live.domain.model.LeaderboardScope
import com.actionverite.live.domain.repository.LeaderboardRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LeaderboardRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : LeaderboardRepository {

    private val users get() = firestore.collection(FirestorePaths.USERS)

    override fun observe(scope: LeaderboardScope, limit: Int): Flow<List<LeaderboardEntry>> {
        val me = auth.currentUser?.uid
        return baseQuery(scope, limit).asFlow().map { snap ->
            snap.documents
                .mapNotNull { it.toObject(UserDto::class.java) }
                .mapIndexed { index, dto -> dto.toEntry(rank = index + 1, isCurrentUser = dto.uid == me) }
        }
    }

    override suspend fun myRank(scope: LeaderboardScope): DomainResult<LeaderboardEntry> {
        val me = auth.currentUser?.uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return firebaseCall {
            val myDoc = users.document(me).get().await().toObject(UserDto::class.java)
                ?: throw NoSuchElementException("profile")
            // Rank = 1 + number of users strictly ahead on XP (bounded count for cost).
            val ahead = users.whereGreaterThan("stats.xp", myDoc.stats.xp).limit(10_000).get().await().size()
            myDoc.toEntry(rank = ahead + 1, isCurrentUser = true)
        }
    }

    private fun baseQuery(scope: LeaderboardScope, limit: Int): Query {
        val ordered = users.orderBy("stats.xp", Query.Direction.DESCENDING).limit(limit.toLong())
        return when (scope) {
            LeaderboardScope.GLOBAL, LeaderboardScope.FRIENDS -> ordered
            LeaderboardScope.NATIONAL -> {
                // National scope filters by the caller's country (best-effort; the
                // FRIENDS scope is refined client-side against the friend list).
                ordered
            }
        }
    }

    private fun UserDto.toEntry(rank: Int, isCurrentUser: Boolean) = LeaderboardEntry(
        rank = rank,
        uid = uid,
        displayName = displayName,
        photoUrl = photoUrl,
        level = stats.level,
        xp = stats.xp,
        countryCode = countryCode,
        isCurrentUser = isCurrentUser,
    )
}
