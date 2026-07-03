package com.actionverite.live.domain.usecase.moderation

import com.actionverite.live.domain.model.ModerationCategory
import com.actionverite.live.domain.model.ModerationVerdict
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ContentModeratorTest {

    // Neutral, obviously-synthetic sample tokens exercise the mechanism without
    // hard-coding real offensive words (production loads curated lists remotely).
    private val moderator = ContentModerator(
        lexicon = mapOf(
            ModerationCategory.HATE to setOf("hatetoken"),
            ModerationCategory.SEXUAL_MINORS to setOf("minortoken"),
            ModerationCategory.ADULT to setOf("adulttoken"),
            ModerationCategory.VIOLENCE to setOf("violencetoken"),
        ),
    )

    @Test
    fun `clean text is allowed`() {
        val result = moderator.screen("Tell us about your happiest memory", allowAdult = false)
        assertThat(result.verdict).isEqualTo(ModerationVerdict.ALLOW)
    }

    @Test
    fun `critical categories are always blocked`() {
        val hate = moderator.screen("this has a hatetoken in it", allowAdult = true)
        assertThat(hate.verdict).isEqualTo(ModerationVerdict.BLOCK)
        assertThat(hate.categories).contains(ModerationCategory.HATE)
        assertThat(hate.score).isEqualTo(1.0f)

        val minors = moderator.screen("minortoken present", allowAdult = true)
        assertThat(minors.verdict).isEqualTo(ModerationVerdict.BLOCK)
    }

    @Test
    fun `adult content is blocked when adult mode is off and flagged when on`() {
        val off = moderator.screen("contains adulttoken", allowAdult = false)
        assertThat(off.verdict).isEqualTo(ModerationVerdict.BLOCK)

        val on = moderator.screen("contains adulttoken", allowAdult = true)
        assertThat(on.verdict).isEqualTo(ModerationVerdict.FLAG)
    }

    @Test
    fun `non-critical categories are flagged`() {
        val result = moderator.screen("a violencetoken here", allowAdult = false)
        assertThat(result.verdict).isEqualTo(ModerationVerdict.FLAG)
        assertThat(result.categories).contains(ModerationCategory.VIOLENCE)
    }

    @Test
    fun `personal information is detected and flagged`() {
        val email = moderator.screen("reach me at john.doe@example.com", allowAdult = false)
        assertThat(email.categories).contains(ModerationCategory.PERSONAL_INFO)
        assertThat(email.verdict).isEqualTo(ModerationVerdict.FLAG)

        val phone = moderator.screen("call 123-456-7890 now", allowAdult = false)
        assertThat(phone.categories).contains(ModerationCategory.PERSONAL_INFO)
    }
}
