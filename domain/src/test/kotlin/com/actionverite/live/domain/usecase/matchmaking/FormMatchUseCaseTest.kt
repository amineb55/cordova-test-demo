package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.Gender
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.ReputationTier
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormMatchUseCaseTest {

    private val formMatch = FormMatchUseCase(MatchmakingScorer())

    private val self = MatchProfile(
        uid = "self",
        languageCode = "en",
        countryCode = "US",
        age = 25,
        level = 10,
        interests = setOf(Interest.MUSIC, Interest.GAMING),
        allowAdult = true,
        reputationTier = ReputationTier.EXCELLENT,
    )

    private fun candidate(uid: String, block: MatchProfile.() -> MatchProfile = { this }) =
        self.copy(uid = uid).block()

    @Test
    fun `picks the highest scoring compatible candidate`() {
        val high = candidate("high")
        val mid = candidate("mid") { copy(countryCode = "CA", level = 12, interests = setOf(Interest.MUSIC)) }

        val result = formMatch(self, listOf(mid, high))
        assertThat(result).isNotNull()
        assertThat(result!!.members.map { it.uid }).containsExactly("self", "high").inOrder()
        assertThat(result.averageScore).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `respects desired group size`() {
        val candidates = listOf(
            candidate("c1"),
            candidate("c2") { copy(level = 11) },
            candidate("c3") { copy(level = 12) },
            candidate("c4") { copy(level = 13) },
        )
        val result = formMatch(self, candidates, MatchPreferences(desiredSize = 4))
        assertThat(result).isNotNull()
        assertThat(result!!.size).isEqualTo(4) // self + 3 others
    }

    @Test
    fun `filters out other-language candidates by default`() {
        val french = candidate("fr") { copy(languageCode = "fr", countryCode = "FR") }
        assertThat(formMatch(self, listOf(french))).isNull()
    }

    @Test
    fun `filters out candidates that exceed the ping limit`() {
        val laggy = candidate("lag") { copy(pingMs = 400) }
        val good = candidate("good")
        val result = formMatch(self, listOf(laggy, good))
        assertThat(result!!.members.map { it.uid }).containsExactly("self", "good")
    }

    @Test
    fun `filters out candidates beyond the level and age gaps`() {
        val farLevel = candidate("lvl") { copy(level = 30) }
        val farAge = candidate("age") { copy(age = 40) }
        assertThat(formMatch(self, listOf(farLevel, farAge))).isNull()
    }

    @Test
    fun `returns null when nobody compatible is available`() {
        assertThat(formMatch(self, emptyList())).isNull()
    }

    @Test
    fun `gender balancing prefers the under-represented gender`() {
        val maleSelf = self.copy(gender = Gender.MALE)
        // Equal-scoring candidates (same everything but uid + gender).
        val a = candidate("a") { copy(gender = Gender.MALE) }
        val b = candidate("b") { copy(gender = Gender.MALE) }
        val c = candidate("c") { copy(gender = Gender.FEMALE) }
        val prefs = MatchPreferences(desiredSize = 3, balanceGender = true)

        val balanced = formMatch(maleSelf, listOf(a, b, c), prefs)
        // Should pull in the female candidate to balance, plus the best remaining male.
        assertThat(balanced!!.members.map { it.uid }).containsExactly("self", "c", "a").inOrder()
    }

    @Test
    fun `gender balancing can be disabled to take pure top scorers`() {
        val maleSelf = self.copy(gender = Gender.MALE)
        val a = candidate("a") { copy(gender = Gender.MALE) }
        val b = candidate("b") { copy(gender = Gender.MALE) }
        val c = candidate("c") { copy(gender = Gender.FEMALE) }
        val prefs = MatchPreferences(desiredSize = 3, balanceGender = false)

        val unbalanced = formMatch(maleSelf, listOf(a, b, c), prefs)
        assertThat(unbalanced!!.members.map { it.uid }).containsExactly("self", "a", "b").inOrder()
    }

    @Test
    fun `ties are broken deterministically by uid`() {
        val b = candidate("bbb")
        val a = candidate("aaa")
        val result = formMatch(self, listOf(b, a))
        assertThat(result!!.members.map { it.uid }).containsExactly("self", "aaa")
    }
}
