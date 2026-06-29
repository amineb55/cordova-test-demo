package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.MatchResult
import com.actionverite.live.domain.model.Room
import javax.inject.Inject
import kotlin.math.abs

/**
 * Forms the best possible group of 2–6 players for the seeker by applying the
 * preference hard-filters, scoring the survivors with [MatchmakingScorer], and
 * greedily taking the highest-scoring candidates up to the desired size.
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

        // self counts as one member, so we need (size - 1) others.
        val others = eligible.take(preferences.desiredSize - 1)
        val members = listOf(self) + others.map { it.first }
        if (members.size < Room.MIN_PLAYERS) return null

        val averageScore = if (others.isEmpty()) 0.0 else others.map { it.second }.average()
        return MatchResult(members = members, averageScore = averageScore)
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
