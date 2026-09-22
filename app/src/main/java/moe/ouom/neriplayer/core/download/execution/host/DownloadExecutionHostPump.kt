package moe.ouom.neriplayer.core.download.execution.host

import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionPumpCursor
import moe.ouom.neriplayer.core.download.execution.uidt.UidtDownloadJobService
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.PumpCandidateSelection
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.PumpPendingPage
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.ScheduleTicket
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.observability.DownloadPumpSelectionMetrics
import moe.ouom.neriplayer.core.download.observability.DownloadPumpSelectionTrace
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import moe.ouom.neriplayer.data.model.stableKey


internal suspend fun DefaultDownloadExecutionHost.collectPumpCandidates(
    context: Context,
    capacity: Int,
    attemptedOperationIds: Set<String>,
    attemptedStableKeys: Set<String>,
    afterCursor: DownloadExecutionPumpCursor?,
    pendingPage: PumpPendingPage?
): PumpCandidateSelection {
    val selectionStartedNs = System.nanoTime()
    val pumpQueryLimit = configuredDispatchWindow(context)
    val candidates = mutableListOf<DownloadExecutionRequest>()
    val observedOperationIds = mutableSetOf<String>()
    val observedStableKeys = mutableSetOf<String>()
    var hasSchedulableRequest = false
    val pendingUidtGraceDeadlinesNs = linkedMapOf<String, Long>()
    var nextRetryAtMs: Long? = null
    var rowsRead = 0
    var pagesRead = 0
    var rowsFilteredAttempted = 0
    var rowsFilteredDuplicateOperation = 0
    var rowsFilteredStableKey = 0
    var rowsDeferredUidt = 0
    var roomQueryNs = 0L
    var cursor = afterCursor
    var exhausted = false
    var pendingRequests = ArrayDeque<DownloadExecutionRequest>().apply {
        pendingPage?.requests?.forEach(::addLast)
    }
    var pendingContinuationCursor = pendingPage?.continuationCursor
    while (candidates.size < capacity) {
        if (pendingRequests.isEmpty()) {
            val queryStartedNs = System.nanoTime()
            val page = operationStore.listSchedulableForPumpPageSuspending(
                context = context,
                afterCursor = cursor,
                limit = pumpQueryLimit
            )
            roomQueryNs += (System.nanoTime() - queryStartedNs).coerceAtLeast(0L)
            pagesRead++
            rowsRead += page.requests.size
            pendingRequests.addAll(page.requests)
            pendingContinuationCursor = page.nextCursor
            page.nextRetryAtMs?.let { pageDeadlineMs ->
                nextRetryAtMs = nextRetryAtMs
                    ?.coerceAtMost(pageDeadlineMs)
                    ?: pageDeadlineMs
            }
            if (pendingRequests.isEmpty() && pendingContinuationCursor == null) {
                exhausted = true
                break
            }
        }
        while (pendingRequests.isNotEmpty() && candidates.size < capacity) {
            val request = pendingRequests.removeFirst()
            val graceDelayMs = pendingUidtGraceDelayMs(context, request)
            if (graceDelayMs > 0L) {
                // 即使同曲目的 replacement 已经尝试过，用户发起的数据传输任务 grace 仍需
                // 被记录，否则旧 predecessor 会让泵错误地提前收口
                hasSchedulableRequest = true
                rowsDeferredUidt++
                pendingUidtGraceDeadlinesNs[request.operationId] =
                    System.nanoTime() + graceDelayMs * 1_000_000L
                continue
            }
            if (
                request.operationId in attemptedOperationIds
            ) {
                rowsFilteredAttempted++
                continue
            }
            if (!observedOperationIds.add(request.operationId)) {
                rowsFilteredDuplicateOperation++
                continue
            }
            if (request.song.stableKey() in attemptedStableKeys) {
                rowsFilteredStableKey++
                continue
            }
            if (
                candidates.size < capacity &&
                    observedStableKeys.add(request.song.stableKey())
            ) {
                hasSchedulableRequest = true
                candidates += request
                val traceToken = DownloadOperationTrace.begin(
                    operationId = request.operationId,
                    attemptId = request.attemptId
                )
                DownloadOperationTrace.mark(
                    traceToken,
                    DownloadOperationTracePhase.QUEUE_SELECTED
                )
            } else {
                rowsFilteredStableKey++
            }
        }
        if (candidates.size >= capacity) {
            // 页内剩余请求留在内存窗口，避免把未选中的行跳过
            if (pendingRequests.isEmpty()) {
                if (pendingContinuationCursor != null && pendingContinuationCursor != cursor) {
                    cursor = pendingContinuationCursor
                } else {
                    exhausted = pendingContinuationCursor == null
                }
            }
            break
        }
        if (pendingRequests.isNotEmpty()) {
            continue
        }
        val nextCursor = pendingContinuationCursor
        if (nextCursor == null) {
            exhausted = true
            break
        }
        if (nextCursor == cursor) {
            exhausted = true
            break
        }
        cursor = nextCursor
        pendingContinuationCursor = null
    }
    DownloadPumpSelectionTrace.record(
        DownloadPumpSelectionMetrics(
            capacity = capacity,
            pagesRead = pagesRead,
            rowsRead = rowsRead,
            rowsFilteredAttempted = rowsFilteredAttempted,
            rowsFilteredDuplicateOperation = rowsFilteredDuplicateOperation,
            rowsFilteredStableKey = rowsFilteredStableKey,
            rowsDeferredUidt = rowsDeferredUidt,
            candidateCount = candidates.size,
            roomQueryNs = roomQueryNs,
            selectionNs = (System.nanoTime() - selectionStartedNs).coerceAtLeast(0L)
        )
    )
    return PumpCandidateSelection(
        requests = candidates,
        hasSchedulableRequest = hasSchedulableRequest,
        pendingUidtGraceDeadlinesNs = pendingUidtGraceDeadlinesNs,
        nextRetryAtMs = nextRetryAtMs,
        nextCursor = cursor,
        exhausted = exhausted,
        pendingPage = pendingRequests
            .takeIf { it.isNotEmpty() }
            ?.let { remaining ->
                PumpPendingPage(
                    requests = remaining.toList(),
                    continuationCursor = pendingContinuationCursor
                )
            }
    )
}

internal fun DefaultDownloadExecutionHost.pendingUidtGraceDelayMs(
    context: Context,
    request: DownloadExecutionRequest
): Long {
    pendingUidtGraceDelayProvider?.invoke(context, request)?.let { delayMs ->
        return delayMs.coerceAtLeast(0L)
    }
    if (
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !request.userInitiated
    ) {
        return 0L
    }
    return UidtDownloadJobService.pendingJobGraceRemainingMs(
        context = context,
        operationId = request.operationId
    )
}

internal fun DefaultDownloadExecutionHost.tryAcquireHostAdmission(
    context: Context,
    operationId: String,
    capacity: Int = configuredDispatchWindow(context)
): Boolean {
    return operationStore.tryAcquireHostAdmission(
        context = context,
        operationId = operationId,
        capacity = capacity
    )
}

internal suspend fun DefaultDownloadExecutionHost.tryAcquireHostAdmissionSuspending(
    context: Context,
    operationId: String,
    capacity: Int = configuredDispatchWindow(context)
): Boolean {
    return operationStore.tryAcquireHostAdmissionSuspending(
        context = context,
        operationId = operationId,
        capacity = capacity
    )
}

internal fun DefaultDownloadExecutionHost.configuredDownloadParallelism(context: Context): Int {
    return downloadParallelismProvider(context).coerceIn(1, MAX_DOWNLOAD_PARALLELISM)
}

internal fun DefaultDownloadExecutionHost.configuredDispatchWindow(context: Context): Int {
    return resolveDownloadDispatchWindow(configuredDownloadParallelism(context))
}

internal fun DefaultDownloadExecutionHost.enqueueDeferredSchedule(
    context: Context,
    request: DownloadExecutionRequest,
    ticket: ScheduleTicket? = null
): Boolean {
    if (ticket != null && !isScheduleTicketCurrent(context, ticket)) {
        withDeferredSchedulingLock {
            deferredRequests.remove(request)
        }
        return false
    }
    withDeferredSchedulingLock {
        deferredRequests.enqueue(request)
    }
    if (ticket != null && !isScheduleTicketCurrent(context, ticket)) {
        withDeferredSchedulingLock {
            deferredRequests.remove(request)
        }
        return false
    }
    triggerDeferredSchedules(context.applicationContext)
    return true
}

internal fun DefaultDownloadExecutionHost.triggerDeferredSchedules(context: Context) {
    val shouldStart = synchronized(deferredSchedulingLock) {
        deferredSchedulingRunning.compareAndSet(false, true)
    }
    if (!shouldStart) return
    val appContext = context.applicationContext
    deferredSchedulingScope.launch {
        var deferredRetryCount = 0
        try {
            while (true) {
                val request = withDeferredSchedulingLock {
                    deferredRequests.poll()
                }
                if (request == null) {
                    val queueEmpty = withDeferredSchedulingLock {
                        deferredRequests.isEmpty()
                    }
                    if (queueEmpty) {
                        return@launch
                    }
                    delay(HOST_ADMISSION_RETRY_DELAY_MS)
                    continue
                }
                when (val result = schedule(appContext, request)) {
                    is DownloadExecutionSchedule.Scheduled -> {
                        withDeferredSchedulingLock {
                            deferredRequests.remove(request)
                        }
                        deferredRetryCount = 0
                    }

                    is DownloadExecutionSchedule.Deferred -> {
                        withDeferredSchedulingLock {
                            deferredRequests.requeue(request)
                        }
                        deferredRetryCount++
                    }

                    is DownloadExecutionSchedule.Rejected -> {
                        if (result.retryable) {
                            withDeferredSchedulingLock {
                                deferredRequests.requeue(request)
                            }
                            deferredRetryCount++
                        } else {
                            withDeferredSchedulingLock {
                                deferredRequests.remove(request)
                            }
                            deferredRetryCount = 0
                        }
                    }
                }
                if (deferredRetryCount >= deferredRetryLimit()) {
                    deferredRetryCount = 0
                    delay(HOST_ADMISSION_RETRY_DELAY_MS)
                }
            }
        } finally {
            val shouldRestart = synchronized(deferredSchedulingLock) {
                deferredSchedulingRunning.set(false)
                !deferredRequests.isEmpty()
            }
            if (shouldRestart) {
                triggerDeferredSchedules(appContext)
            }
        }
    }
}

internal fun DefaultDownloadExecutionHost.deferredRetryLimit(): Int {
    return withDeferredSchedulingLock {
        deferredRequests.size()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_DEFERRED_SCHEDULES_PER_PASS)
    }
}

internal fun <T> DefaultDownloadExecutionHost.withDeferredSchedulingLock(action: () -> T): T {
    return synchronized(deferredSchedulingLock, action)
}

internal suspend fun DefaultDownloadExecutionHost.releaseLostExecutionAdmissionIfUnowned(
    context: Context,
    operationId: String
) {
    val unowned = synchronized(executionAdmissionLock) {
        !hostAdmissionOwners.containsKey(operationId)
    }
    if (!unowned) return
    try {
        operationStore.releaseHostAdmissionSuspending(context, operationId)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        moe.ouom.neriplayer.core.logging.NPLogger.w(
            "DownloadExecutionHost",
            "并发 claim 失败后回收孤立宿主准入失败: " +
                "operationId=$operationId, error=${error.message}",
            error
        )
    }
}

internal fun DefaultDownloadExecutionHost.releaseHostAdmissionIfIdle(
    context: Context,
    operationId: String,
    ticket: ScheduleTicket? = null
) {
    val releaseDecision = synchronized(executionAdmissionLock) {
        if (
            executingOperationIds.contains(operationId) ||
                hasPendingTransferReleaseLocked(operationId)
        ) {
            false to null
        } else if (ticket != null) {
            val owner = hostAdmissionOwners[operationId]
                ?.takeIf { owner -> owner == ticket }
            (owner != null) to owner
        } else {
            true to hostAdmissionOwners[operationId]
        }
    }
    if (!releaseDecision.first) return
    val ownerToRelease = releaseDecision.second
    val released = runCatching {
        operationStore.releaseHostAdmission(context, operationId)
    }.onFailure { error ->
        moe.ouom.neriplayer.core.logging.NPLogger.w(
            "DownloadExecutionHost",
            "释放空闲宿主准入失败，保留 owner 供后续重试: " +
                "operationId=$operationId, error=${error.message}",
            error
        )
    }.isSuccess
    if (!released) return
    synchronized(executionAdmissionLock) {
        if (ticket != null) {
            hostAdmissionOwners.remove(operationId, ticket)
        } else if (ownerToRelease != null) {
            hostAdmissionOwners.remove(operationId, ownerToRelease)
        }
    }
    triggerDeferredSchedules(context.applicationContext)
}

internal suspend fun DefaultDownloadExecutionHost.releaseHostAdmissionIfIdleSuspending(
    context: Context,
    operationId: String,
    ticket: ScheduleTicket? = null
) {
    val releaseDecision = synchronized(executionAdmissionLock) {
        if (
            executingOperationIds.contains(operationId) ||
                hasPendingTransferReleaseLocked(operationId)
        ) {
            false to null
        } else if (ticket != null) {
            val owner = hostAdmissionOwners[operationId]
                ?.takeIf { owner -> owner == ticket }
            (owner != null) to owner
        } else {
            true to hostAdmissionOwners[operationId]
        }
    }
    if (!releaseDecision.first) return
    val ownerToRelease = releaseDecision.second
    val released = try {
        operationStore.releaseHostAdmissionSuspending(context, operationId)
        true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        moe.ouom.neriplayer.core.logging.NPLogger.w(
            "DownloadExecutionHost",
            "释放空闲宿主准入失败，保留 owner 供后续重试: " +
                "operationId=$operationId, error=${error.message}",
            error
        )
        false
    }
    if (!released) return
    synchronized(executionAdmissionLock) {
        if (ticket != null) {
            hostAdmissionOwners.remove(operationId, ticket)
        } else if (ownerToRelease != null) {
            hostAdmissionOwners.remove(operationId, ownerToRelease)
        }
    }
    triggerDeferredSchedules(context.applicationContext)
}

internal fun DefaultDownloadExecutionHost.hasPendingTransferReleaseLocked(operationId: String): Boolean {
    val owner = activeTransferOwners[operationId] ?: return false
    return transferReleasePendingTokens.contains(owner.token)
}
