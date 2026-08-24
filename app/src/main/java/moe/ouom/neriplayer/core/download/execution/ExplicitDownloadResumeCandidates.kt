package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ExplicitDownloadResumeCandidate
import moe.ouom.neriplayer.core.download.visibleExplicitResumeCandidates
import moe.ouom.neriplayer.data.model.stableKey

private val EXPLICIT_RESUME_OPERATION_STATES = listOf(
    "PENDING_QUEUE",
    "QUEUED",
    "RUNNING",
    "COMMITTING",
    "RETRYABLE",
    "STOPPED"
)

internal suspend fun loadExplicitDownloadResumeCandidates(
    context: Context
): List<ExplicitDownloadResumeCandidate> = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val entries = DownloadExecutionRoomStore.listByStates(
        context = appContext,
        states = EXPLICIT_RESUME_OPERATION_STATES
    )
    val candidates = entries.mapNotNull { entry ->
        val request = entry.request
        if (!request.userInitiated) {
            return@mapNotNull null
        }
        if (!DownloadExecutionHosts.default.requiresExplicitResume(
                context = appContext,
                operationId = request.operationId
            )
        ) {
            return@mapNotNull null
        }
        ExplicitDownloadResumeCandidate(
            operationId = request.operationId,
            song = request.song,
            queueOrder = entry.queueOrder
        )
    }
    visibleExplicitResumeCandidates(
        candidates = candidates,
        activeSongKeys = emptySet()
    )
}

/** schedules the original operation after the user explicitly chooses continue */
internal suspend fun resumeExplicitDownload(
    context: Context,
    candidate: ExplicitDownloadResumeCandidate
): DownloadExecutionSchedule = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val operationStore = DownloadExecutionOperationStore()
    val request = buildExplicitResumeRequest(candidate)
    operationStore.clearUserStopForStableKeys(
        context = appContext,
        stableKeys = setOf(candidate.song.stableKey())
    )
    val schedule = DownloadExecutionHosts.default.schedule(
        context = appContext,
        request = request
    )
    if (schedule is DownloadExecutionSchedule.Rejected) {
        // keep the candidate visible when the OS host is temporarily unavailable
        runCatching {
            DownloadExecutionRoomStore.upsert(
                context = appContext,
                request = request,
                state = "QUEUED"
            )
            DownloadExecutionRoomStore.markStopped(appContext, candidate.operationId)
        }
    }
    schedule
}

internal fun buildExplicitResumeRequest(
    candidate: ExplicitDownloadResumeCandidate
): DownloadExecutionRequest {
    return DownloadExecutionRequest(
        operationId = candidate.operationId,
        song = candidate.song,
        preserveStaging = true,
        userInitiated = true
    )
}
