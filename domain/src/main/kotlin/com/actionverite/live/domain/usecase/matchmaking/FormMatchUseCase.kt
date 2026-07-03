package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.Gender
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.MatchResult
import com.actionverite.live.domain.model.Room
import javax.inject.Inject
import kotlin.math.abs

/**
 * Forms the best possible group of 2–6 players for the seeker by applying the
 * preference hard-filters, scoring the survivors with [MatchmakingScorer], and
 * selecting up to the desired size.
 *
 * When [MatchPreferences.balanceGender] is on, selection greedily favours the
 * currently under-represented binary gender at each step (best-effort balancing,
 * "équilibrer hommes/femmes lorsque possible") while still picking the
 * highest-scored candidate of that gender; otherwise it takes the top scorers.
 *
 * Returns `null` when not enough compatible candidates exist to reach the
 * minimum group size.
 */
class FormMatchUseCase @Inject constructor(
    private val scorer: MatchmakingScorer,
) {

    operator fun invoke(
        self: MatchProfile,
        candidates: List<MatchProfile>,
        preferences: MatchPreferences = MatchPreferences(),
    ): MatchResult? {
        val eligible = candidates
            .asSequence()
            .filter { it.uid != self.uid }
            .filter { passesHardFilters(self, it, preferences) }
            .map { it to scorer.score(self, it, preferences) }
            // Highest score first; tie-break by uid for deterministic results.
            .sortedWith(compareByDescending<Pair<MatchProfile, Double>> { it.second }.thenBy { it.first.uid })
            .toList()

        val slots = preferences.desiredSize - 1 // self is one member
        val others = if (preferences.balanceGender) {
            selectGenderBalanced(self, eligible, slots)
        } else {
            eligible.take(slots)
        }

        val members = listOf(self) + others.map { it.first }
        if (members.size < Room.MIN_PLAYERS) return null

        val averageScore = if (others.isEmpty()) 0.0 else others.map { it.second }.average()
        return MatchResult(members = members, averageScore = averageScore)
    }

    /**
     * Greedy balanced pick: at each step, if one binary gender is under-represented
     * in the group so far, take the highest-scored remaining candidate of that
     * gender (falling back to the overall best when none remain). [Gender.UNSPECIFIED]
     * candidates are chosen purely on score and don't shift the balance.
     */
    private fun selectGenderBalanced(
        self: MatchProfile,
        sortedByScore: List<Pair<MatchProfile, Double>>,
        slots: Int,
    ): List<Pair<MatchProfile, Double>> {
        if (slots <= 0) return emptyList()
        val remaining = sortedByScore.toMutableList()
        val chosen = ArrayList<Pair<MatchProfile, Double>>(slots)
        var male = if (self.gender == Gender.MALE) 1 else 0
        var female = if (self.gender == Gender.FEMALE) 1 else 0

        while (chosen.size < slots && remaining.isNotEmpty()) {
            val target = when {
                male < female -> Gender.MALE
                female < male -> Gender.FEMALE
                else -> null // balanced (or only UNSPECIFIED so far) -> take the best
            }
            val pick = target
                ?.let { g -> remaining.firstOrNull { it.first.gender == g } }
                ?: remaining.first()
            remaining.remove(pick)
            chosen.add(pick)
            when (pick.first.gender) {
                Gender.MALE -> male++
                Gender.FEMALE -> female++
                Gender.UNSPECIFIED -> Unit
            }
        }
        return chosen
    }

    private fun passesHardFilters(
        self: MatchProfile,
        candidate: MatchProfile,
        prefs: MatchPreferences,
    ): Boolean {
        if (prefs.sameLanguageOnly && self.languageCode != candidate.languageCode) return false
        if (prefs.sameCountryOnly && self.countryCode != candidate.countryCode) return false
        if (prefs.adultOnly && !candidate.allowAdult) return false
        if (abs(self.level - candidate.level) > prefs.maxLevelGap) return false
        if (candidate.pingMs > prefs.maxPingMs) return false
        if (self.age != null && candidate.age != null && abs(self.age - candidate.age) > prefs.maxAgeGap) {
            return false
        }
        return true
    }
}
