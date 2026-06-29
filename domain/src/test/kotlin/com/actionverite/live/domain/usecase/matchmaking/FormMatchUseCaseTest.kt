package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
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
        val farLevel = candidate("lvl") { copy(level = 30) }       // gap 20 > 10
        val farAge = candidate("age") { copy(age = 40) }           // gap 15 > 8
        assertThat(formMatch(self, listOf(farLevel, farAge))).isNull()
    }

    @Test
    fun `returns null when nobody compatible is available`() {
        assertThat(formMatch(self, emptyList())).isNull()
    }

    @Test
    fun `ties are broken deterministically by uid`() {
        val b = candidate("bbb")
        val a = candidate("aaa")
        val result = formMatch(self, listOf(b, a)) // both perfect score
        assertThat(result!!.members.map { it.uid }).containsExactly("self", "aaa")
    }
}
