package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeCategory
import com.actionverite.live.domain.model.ChallengeRequest
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.ChallengeRepository
import com.actionverite.live.domain.usecase.moderation.ContentModerator
import com.actionverite.live.domain.usecase.moderation.FilterChallengeUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class RequestChallengeUseCaseTest {

    /** Hand-rolled fake — no mocking framework needed for a pure interface. */
    private class FakeChallengeRepository(
        private val responses: ArrayDeque<DomainResult<Challenge>>,
        private val recent: List<String> = emptyList(),
    ) : ChallengeRepository {
        val served = mutableListOf<Challenge>()
        var generateCalls = 0

        override suspend fun generate(request: ChallengeRequest): DomainResult<Challenge> {
            generateCalls++
            return responses.removeFirstOrNull() ?: DomainResult.Failure(DomainError.Network)
        }

        override suspend fun recentSignatures(uid: String, limit: Int) = DomainResult.Success(recent)

        override suspend fun recordServed(uid: String, challenge: Challenge): DomainResult<Unit> {
            served += challenge
            return DomainResult.Success(Unit)
        }
    }

    private val filter = FilterChallengeUseCase(ContentModerator())

    private val profile = UserProfile(
        uid = "u1",
        displayName = "Tester",
        birthEpochDay = LocalDate.of(1990, 1, 1).toEpochDay(),
        limits = ContentLimits(allowAdult = false),
    )
    private val today = LocalDate.of(2020, 1, 1).toEpochDay()

    private fun clean(sig: String) = Challenge(
        id = sig, type = ChallengeType.TRUTH, text = "A wholesome question",
        category = ChallengeCategory.FUNNY, difficulty = 1, isAdult = false, signature = sig,
    )

    private fun adult(sig: String) = Challenge(
        id = sig, type = ChallengeType.TRUTH, text = "Adult question",
        category = ChallengeCategory.ADULT, difficulty = 1, isAdult = true, signature = sig,
    )

    @Test
    fun `returns the first acceptable challenge and records it`() = runTest {
        val repo = FakeChallengeRepository(ArrayDeque(listOf(DomainResult.Success(clean("s1")))))
        val useCase = RequestChallengeUseCase(repo, filter)

        val result = useCase(profile, ChallengeType.TRUTH, difficulty = null, todayEpochDay = today)

        assertThat(result.getOrNull()?.signature).isEqualTo("s1")
        assertThat(repo.served.map { it.signature }).containsExactly("s1")
    }

    @Test
    fun `retries past a rejected challenge until one passes`() = runTest {
        val repo = FakeChallengeRepository(
            ArrayDeque(listOf(DomainResult.Success(adult("bad")), DomainResult.Success(clean("good")))),
        )
        val useCase = RequestChallengeUseCase(repo, filter)

        val result = useCase(profile, ChallengeType.TRUTH, difficulty = null, todayEpochDay = today)

        assertThat(result.getOrNull()?.signature).isEqualTo("good")
        assertThat(repo.generateCalls).isEqualTo(2)
        assertThat(repo.served.map { it.signature }).containsExactly("good")
    }

    @Test
    fun `retries through a network error`() = runTest {
        val repo = FakeChallengeRepository(
            ArrayDeque(listOf(DomainResult.Failure(DomainError.Network), DomainResult.Success(clean("ok")))),
        )
        val useCase = RequestChallengeUseCase(repo, filter)

        val result = useCase(profile, ChallengeType.TRUTH, difficulty = null, todayEpochDay = today)
        assertThat(result.getOrNull()?.signature).isEqualTo("ok")
    }

    @Test
    fun `fails after exhausting attempts and never records a served challenge`() = runTest {
        val repo = FakeChallengeRepository(
            ArrayDeque(
                listOf(
                    DomainResult.Success(adult("a1")),
                    DomainResult.Success(adult("a2")),
                    DomainResult.Success(adult("a3")),
                ),
            ),
        )
        val useCase = RequestChallengeUseCase(repo, filter)

        val result = useCase(profile, ChallengeType.TRUTH, difficulty = null, todayEpochDay = today, maxAttempts = 3)

        assertThat(result.errorOrNull()).isEqualTo(DomainError.Underage)
        assertThat(repo.served).isEmpty()
    }
}
