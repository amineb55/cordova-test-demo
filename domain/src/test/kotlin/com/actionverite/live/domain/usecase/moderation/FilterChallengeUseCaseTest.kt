package com.actionverite.live.domain.usecase.moderation

import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeCategory
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.ContentLimits
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilterChallengeUseCaseTest {

    private val filter = FilterChallengeUseCase(ContentModerator())

    private fun challenge(
        type: ChallengeType = ChallengeType.TRUTH,
        category: ChallengeCategory = ChallengeCategory.FUNNY,
        difficulty: Int = 1,
        isAdult: Boolean = false,
        text: String = "What is your favourite song?",
    ) = Challenge(
        id = "c",
        type = type,
        text = text,
        category = category,
        difficulty = difficulty,
        isAdult = isAdult,
    )

    @Test
    fun `clean challenge within limits is allowed`() {
        val result = filter(challenge(), ContentLimits.DEFAULT, age = 25)
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
    }

    @Test
    fun `adult challenge blocked when adult content disabled`() {
        val result = filter(challenge(isAdult = true), ContentLimits(allowAdult = false), age = 30)
        assertThat(result.errorOrNull()).isEqualTo(DomainError.Underage)
    }

    @Test
    fun `adult challenge blocked for minors even when adult allowed`() {
        val result = filter(challenge(isAdult = true), ContentLimits(allowAdult = true), age = 16)
        assertThat(result.errorOrNull()).isEqualTo(DomainError.Underage)
    }

    @Test
    fun `adult challenge allowed for opted-in adult`() {
        val result = filter(
            challenge(isAdult = true, category = ChallengeCategory.ADULT),
            ContentLimits(allowAdult = true),
            age = 30,
        )
        assertThat(result).isInstanceOf(DomainResult.Success::class.java)
    }

    @Test
    fun `disabled type is rejected`() {
        val truthOff = filter(challenge(type = ChallengeType.TRUTH), ContentLimits(allowTruth = false), age = 20)
        assertThat(truthOff.errorOrNull()).isInstanceOf(DomainError.Moderation::class.java)

        val dareOff = filter(challenge(type = ChallengeType.DARE), ContentLimits(allowDare = false), age = 20)
        assertThat(dareOff.errorOrNull()).isInstanceOf(DomainError.Moderation::class.java)
    }

    @Test
    fun `blocked category and excessive difficulty are rejected`() {
        val blockedCat = filter(
            challenge(category = ChallengeCategory.PHYSICAL),
            ContentLimits(blockedCategories = setOf(ChallengeCategory.PHYSICAL)),
            age = 20,
        )
        assertThat(blockedCat.errorOrNull()).isInstanceOf(DomainError.Moderation::class.java)

        val tooHard = filter(challenge(difficulty = 5), ContentLimits(maxDifficulty = 3), age = 20)
        assertThat(tooHard.errorOrNull()).isInstanceOf(DomainError.Moderation::class.java)
    }

    @Test
    fun `text that leaks personal info is rejected`() {
        val result = filter(
            challenge(text = "Message me at hacker@example.com to continue"),
            ContentLimits.DEFAULT,
            age = 25,
        )
        assertThat(result.errorOrNull()).isInstanceOf(DomainError.Moderation::class.java)
    }
}
