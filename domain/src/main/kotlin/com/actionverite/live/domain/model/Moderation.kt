package com.actionverite.live.domain.model

/** What to do with a piece of content after screening. */
enum class ModerationVerdict { ALLOW, FLAG, BLOCK }

/** Categories of harmful content the moderator screens for. */
enum class ModerationCategory {
    HATE, HARASSMENT, SEXUAL, SEXUAL_MINORS, VIOLENCE, SELF_HARM,
    ILLEGAL, PERSONAL_INFO, ADULT,
}

/**
 * Outcome of screening a piece of text or a generated challenge. [score] is a
 * 0f..1f severity estimate; [verdict] is the action to take.
 */
data class ModerationResult(
    val verdict: ModerationVerdict,
    val categories: Set<ModerationCategory> = emptySet(),
    val score: Float = 0f,
    val reason: String? = null,
) {
    val isAllowed: Boolean get() = verdict == ModerationVerdict.ALLOW

    companion object {
        val ALLOWED = ModerationResult(ModerationVerdict.ALLOW)
    }
}

enum class ReportReason {
    INAPPROPRIATE_CONTENT, HARASSMENT, NUDITY, HATE_SPEECH, UNDERAGE,
    CHEATING, SPAM, OTHER,
}

/** A user-submitted report against another player. */
data class Report(
    val id: String,
    val reporterUid: String,
    val targetUid: String,
    val roomId: String? = null,
    val reason: ReportReason,
    val details: String? = null,
    val createdAtEpochMs: Long = 0L,
)
