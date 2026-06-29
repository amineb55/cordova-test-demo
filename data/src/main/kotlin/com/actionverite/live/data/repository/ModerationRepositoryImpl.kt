package com.actionverite.live.data.repository

import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.FunctionsService
import com.actionverite.live.data.remote.dto.ReportDto
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ModerationResult
import com.actionverite.live.domain.model.Report
import com.actionverite.live.domain.repository.ModerationRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModerationRepositoryImpl @Inject constructor(
    private val functions: FunctionsService,
    private val firestore: FirebaseFirestore,
) : ModerationRepository {

    override suspend fun screenText(text: String): DomainResult<ModerationResult> = firebaseCall {
        functions.moderateText(text)
    }

    override suspend fun submitReport(report: Report): DomainResult<Unit> = firebaseCall {
        val ref = firestore.collection(FirestorePaths.REPORTS).document()
        ref.set(
            ReportDto(
                id = ref.id,
                reporterUid = report.reporterUid,
                targetUid = report.targetUid,
                roomId = report.roomId,
                reason = report.reason.name,
                details = report.details,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        ).await()
        Unit
    }
}
