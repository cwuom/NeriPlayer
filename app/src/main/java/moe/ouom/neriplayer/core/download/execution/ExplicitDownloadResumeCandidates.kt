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
    "CORE_COMMITTED",
    "ASSETS_ENRICHING",
    "DEGRADED_COMPLETE",
    "RETRYABLE",
    "STOPPED"
)

internal suspend fun loadExplicitDownloadResumeCandidates(
    context: Context
): List<ExplicitDownloadResumeCandidate> = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    if (PersistentDownloadClearFenceStore.isActive(appContext)) {
        return@withContext emptyList()
    }
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
    val stableKey = candidate.song.stableKey()
    if (PersistentDownloadClearFenceStore.isActive(appContext)) {
        return@withContext DownloadExecutionSchedule.Rejected(
            "download clear is in progress"
        )
    }
    if (!DownloadExecutionRoomStore.prepareExplicitResume(
            context = appContext,
            operationId = candidate.operationId,
            stableKey = stableKey
        )
    ) {
        return@withContext DownloadExecutionSchedule.Rejected(
            "operation is no longer resumable"
        )
    }
    val persistedRequest = DownloadExecutionRoomStore.read(
        context = appContext,
        operationId = candidate.operationId
    )?.takeIf { request -> request.song.stableKey() == stableKey }
        ?: run {
            DownloadExecutionRoomStore.restoreExplicitStop(
                context = appContext,
                operationId = candidate.operationId,
                stableKey = stableKey,
                errorCode = "EXPLICIT_RESUME_MISSING_OPERATION"
            )
            return@withContext DownloadExecutionSchedule.Rejected(
                "operation is no longer resumable"
            )
        }
    val request = buildExplicitResumeRequest(candidate, persistedRequest)
    if (PersistentDownloadClearFenceStore.isActive(appContext)) {
        cancelExplicitResumeDuringClear(
            context = appContext,
            operationId = candidate.operationId
        )
        return@withContext DownloadExecutionSchedule.Rejected(
            "download clear is in progress"
        )
    }
    val schedule = DownloadExecutionHosts.default.schedule(
        context = appContext,
        request = request
    )
    if (schedule is DownloadExecutionSchedule.Rejected) {
        if (PersistentDownloadClearFenceStore.isActive(appContext)) {
            cancelExplicitResumeDuringClear(
                context = appContext,
                operationId = candidate.operationId
            )
        } else {
            runCatching {
                DownloadExecutionRoomStore.restoreExplicitStop(
                    context = appContext,
                    operationId = candidate.operationId,
                    stableKey = stableKey,
                    errorCode = "EXPLICIT_RESUME_HOST_REJECTED"
                )
            }
        }
    }
    schedule
}

private suspend fun cancelExplicitResumeDuringClear(
    context: Context,
    operationId: String
) {
    runCatching {
        DownloadExecutionRoomStore.requestCancel(
            context = context,
            operationId = operationId
        )
    }
}

internal fun buildExplicitResumeRequest(
    candidate: ExplicitDownloadResumeCandidate,
    persistedRequest: DownloadExecutionRequest
): DownloadExecutionRequest {
    require(persistedRequest.operationId == candidate.operationId) {
        "persisted operation must match the resume candidate"
    }
    require(persistedRequest.song.stableKey() == candidate.song.stableKey()) {
        "persisted song must match the resume candidate"
    }
    return persistedRequest.copy(
        preserveStaging = true,
        userInitiated = true
    )
}
