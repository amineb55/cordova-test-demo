package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ModerationResult
import com.actionverite.live.domain.model.Report

interface ModerationRepository {
    /** Server-side moderation (e.g. a moderation model) for free text. */
    suspend fun screenText(text: String): DomainResult<ModerationResult>

    suspend fun submitReport(report: Report): DomainResult<Unit>
}
