package com.actionverite.live.data.remote

import com.actionverite.live.data.remote.dto.ChallengeDto
import com.actionverite.live.domain.model.ChallengeRequest
import com.actionverite.live.domain.model.ModerationCategory
import com.actionverite.live.domain.model.ModerationResult
import com.actionverite.live.domain.model.ModerationVerdict
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin client over the secured Cloud Functions backend. The AI model API key and
 * the heavy moderation/matchmaking logic live server-side; the app only ever
 * talks to these callable endpoints (authenticated + App Check enforced).
 */
@Singleton
class FunctionsService @Inject constructor(
    private val functions: FirebaseFunctions,
) {

    suspend fun generateChallenge(request: ChallengeRequest): ChallengeDto {
        val payload = mapOf(
            "type" to request.type.name,
            "targetUid" to request.targetUid,
            "languageCode" to request.languageCode,
            "countryCode" to request.countryCode,
            "age" to request.age,
            "interests" to request.interests.map { it.name },
            "limits" to mapOf(
                "allowAdult" to request.limits.allowAdult,
                "maxDifficulty" to request.limits.maxDifficulty,
                "blockedCategories" to request.limits.blockedCategories.map { it.name },
            ),
            "difficulty" to request.difficulty?.let {
                mapOf("average" to it.average, "target" to it.target)
            },
            "recentSignatures" to request.recentSignatures,
        )

        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable(FN_GENERATE_CHALLENGE)
            .call(payload).await().getData() as Map<String, Any?>

        return ChallengeDto(
            id = data["id"] as? String ?: "",
            type = data["type"] as? String ?: request.type.name,
            text = data["text"] as? String ?: "",
            category = data["category"] as? String ?: "FUNNY",
            difficulty = (data["difficulty"] as? Number)?.toInt() ?: 1,
            languageCode = data["languageCode"] as? String ?: request.languageCode,
            isAdult = data["isAdult"] as? Boolean ?: false,
            signature = data["signature"] as? String ?: "",
            generatedAtEpochMs = (data["generatedAtEpochMs"] as? Number)?.toLong() ?: 0L,
        )
    }

    suspend fun moderateText(text: String): ModerationResult {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable(FN_MODERATE_TEXT)
            .call(mapOf("text" to text)).await().getData() as Map<String, Any?>

        val verdict = runCatching {
            ModerationVerdict.valueOf(data["verdict"] as? String ?: "ALLOW")
        }.getOrDefault(ModerationVerdict.ALLOW)

        val categories = (data["categories"] as? List<*>).orEmpty()
            .mapNotNull { it as? String }
            .mapNotNull { runCatching { ModerationCategory.valueOf(it) }.getOrNull() }
            .toSet()

        return ModerationResult(
            verdict = verdict,
            categories = categories,
            score = (data["score"] as? Number)?.toFloat() ?: 0f,
            reason = data["reason"] as? String,
        )
    }

    /** Asks the backend to place/refresh the caller in the matchmaking queue. */
    suspend fun requestMatch(preferences: Map<String, Any?>): String? {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable(FN_REQUEST_MATCH)
            .call(preferences).await().getData() as? Map<String, Any?>
        return data?.get("roomId") as? String
    }

    // ----- Economy (server-authoritative Gold) ------------------------------

    /** Invokes a Gold-mutating callable and returns the new authoritative balance. */
    private suspend fun callBalance(name: String, payload: Map<String, Any?>): Long {
        @Suppress("UNCHECKED_CAST")
        val data = functions.getHttpsCallable(name).call(payload).await().getData() as? Map<String, Any?>
        return (data?.get("balance") as? Number)?.toLong() ?: 0L
    }

    suspend fun chargeGameFee(roomId: String): Long =
        callBalance(FN_CHARGE_GAME_FEE, mapOf("roomId" to roomId))

    suspend fun rewardChallenge(roomId: String): Long =
        callBalance(FN_REWARD_CHALLENGE, mapOf("roomId" to roomId))

    suspend fun penalizeRefusal(roomId: String): Long =
        callBalance(FN_PENALIZE_REFUSAL, mapOf("roomId" to roomId))

    suspend fun grantRewardedVideo(ssvToken: String): Long =
        callBalance(FN_GRANT_REWARDED_VIDEO, mapOf("ssvToken" to ssvToken))

    suspend fun purchasePack(packId: String, purchaseToken: String): Long =
        callBalance(FN_PURCHASE_PACK, mapOf("packId" to packId, "purchaseToken" to purchaseToken))

    // ----- Reputation -------------------------------------------------------

    suspend fun submitRatings(roomId: String, ratings: List<Map<String, Any?>>) {
        functions.getHttpsCallable(FN_SUBMIT_RATINGS)
            .call(mapOf("roomId" to roomId, "ratings" to ratings)).await()
    }

    companion object {
        const val FN_GENERATE_CHALLENGE = "generateChallenge"
        const val FN_MODERATE_TEXT = "moderateText"
        const val FN_REQUEST_MATCH = "requestMatch"
        const val FN_CHARGE_GAME_FEE = "chargeGameFee"
        const val FN_REWARD_CHALLENGE = "rewardChallenge"
        const val FN_PENALIZE_REFUSAL = "penalizeRefusal"
        const val FN_GRANT_REWARDED_VIDEO = "grantRewardedVideo"
        const val FN_PURCHASE_PACK = "purchasePack"
        const val FN_SUBMIT_RATINGS = "submitRatings"
    }
}
