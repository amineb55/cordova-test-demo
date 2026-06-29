package com.actionverite.live.domain.usecase.moderation

import com.actionverite.live.domain.model.ModerationCategory
import com.actionverite.live.domain.model.ModerationResult
import com.actionverite.live.domain.model.ModerationVerdict
import javax.inject.Inject

/**
 * Fast, deterministic, on-device first line of moderation. It is **not** a
 * replacement for the server-side moderation model ([com.actionverite.live.domain
 * .repository.ModerationRepository.screenText]); it catches obvious cases
 * instantly and acts as defense-in-depth.
 *
 * The lexicon is injected (default empty) rather than hard-coded so that:
 *  - production loads curated, localized word-lists from a remote config, and
 *  - tests exercise the *mechanism* with neutral sample tokens.
 *
 * Detection is mechanism-only here; the actual word lists live outside the
 * codebase.
 */
class ContentModerator @Inject constructor(
    private val lexicon: Map<ModerationCategory, Set<String>> = emptyMap(),
) {

    fun screen(text: String, allowAdult: Boolean): ModerationResult {
        val normalized = normalize(text)
        val tokens = normalized.split(WORD_SPLIT).filter { it.isNotBlank() }.toSet()

        val matched = buildSet {
            for ((category, words) in lexicon) {
                if (words.any { it in tokens || it in normalized }) add(category)
            }
            if (PERSONAL_INFO_REGEX.containsMatchIn(text)) add(ModerationCategory.PERSONAL_INFO)
        }

        return verdictFor(matched, allowAdult)
    }

    private fun verdictFor(
        categories: Set<ModerationCategory>,
        allowAdult: Boolean,
    ): ModerationResult {
        if (categories.isEmpty()) return ModerationResult.ALLOWED

        val hasCritical = categories.any { it in CRITICAL }
        if (hasCritical) {
            return ModerationResult(
                verdict = ModerationVerdict.BLOCK,
                categories = categories,
                score = 1.0f,
                reason = "Contains prohibited content.",
            )
        }

        val adultLike = ModerationCategory.SEXUAL in categories || ModerationCategory.ADULT in categories
        if (adultLike && !allowAdult) {
            return ModerationResult(
                verdict = ModerationVerdict.BLOCK,
                categories = categories,
                score = 0.9f,
                reason = "Adult content is disabled for this account.",
            )
        }

        // Everything else (harassment, violence, personal info, allowed adult) → flag for review.
        return ModerationResult(
            verdict = ModerationVerdict.FLAG,
            categories = categories,
            score = 0.6f,
            reason = "Flagged for review.",
        )
    }

    private fun normalize(text: String): String =
        text.lowercase().trim().replace(MULTISPACE, " ")

    companion object {
        /** Categories that always force a BLOCK regardless of user settings. */
        val CRITICAL = setOf(
            ModerationCategory.SEXUAL_MINORS,
            ModerationCategory.HATE,
            ModerationCategory.ILLEGAL,
            ModerationCategory.SELF_HARM,
        )

        private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val MULTISPACE = Regex("\\s+")

        // Email or 8+ digit sequences (phone / card-like) → likely personal info sharing.
        private val PERSONAL_INFO_REGEX = Regex(
            "([\\w.+-]+@[\\w-]+\\.[\\w.-]+)|(\\b\\d[\\d\\s-]{6,}\\d\\b)",
        )
    }
}
