package moe.ouom.neriplayer.core.download.execution.host

import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.ScheduleTicket
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.BackendOwner
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Deferred
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.data.model.stableKey


internal fun DefaultDownloadExecutionHost.scheduleWithTicket(
    context: Context,
    request: DownloadExecutionRequest,
    ticket: ScheduleTicket
): DownloadExecutionSchedule {
    var hostAdmissionAcquired = false
    var scheduledBackend: DownloadExecutionSchedule.Backend? = null
    var currentTicket = ticket
    try {
        if (!isScheduleTicketCurrent(context, ticket)) {
            return rejectStaleSchedule(
                context,
                request,
                hostAdmissionAcquired,
                ticket = ticket
            )
        }
        val songKey = request.song.stableKey()
        // Room 日志负责按根目录调度，内存状态可能跨目录切换残留
        val existingOperationId = operationStore.findOperationIdForSong(context, songKey)
        if (!isScheduleTicketCurrent(context, ticket)) {
            return rejectStaleSchedule(
                context,
                request,
                hostAdmissionAcquired,
                ticket = ticket
            )
        }
        val existingState = existingOperationId?.let { id ->
            operationStore.currentState(context, id)
        }
        val existingReadable = existingOperationId?.let { id ->
            operationStore.read(context, id) != null
        } == true
        val existingCancellationRequested = existingOperationId?.let { id ->
            operationStore.isUserCancellationRequested(context, id)
        } == true
        if (shouldBlockExistingDownloadOperation(
                existingOperationId = existingOperationId,
                requestedOperationId = request.operationId,
                existingState = existingState,
                existingReadable = existingReadable,
                cancellationRequested = existingCancellationRequested
            )
        ) {
            return DownloadExecutionSchedule.Rejected(
                "download operation already scheduled"
            )
        }
        val currentState = operationStore.currentState(context, request.operationId)
        if (!canScheduleDownloadOperation(currentState)) {
            return DownloadExecutionSchedule.Rejected(
                "operation is no longer schedulable: $currentState"
            )
        }
        if (!isScheduleTicketCurrent(context, ticket)) {
            return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = ticket
            )
        }
        operationStore.save(context, request)
        // 同一 operation 可能已经被共享泵或 用户发起的数据传输任务 并发刷新 attempt。
        // 这里允许在 clear epoch 不变时绑定最新 attempt，避免把普通 handoff 竞态
        // 错判成“被清空覆盖”。真正的 clear 仍由 isScheduleTicketCurrent 拦截。
        val boundTicket = bindPersistedScheduleTicket(
            context = context,
            ticket = ticket,
            allowAttemptRebind = true
        )
            ?: return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = ticket
            )
        currentTicket = boundTicket
        val operationTraceToken = DownloadOperationTrace.begin(
            operationId = boundTicket.operationId,
            attemptId = boundTicket.attemptId
        )
        DownloadOperationTrace.mark(
            operationTraceToken,
            DownloadOperationTracePhase.ENQUEUED
        )
        val previousScheduleOwner = scheduleOwners.putIfAbsent(
            request.operationId,
            boundTicket
        )
        if (previousScheduleOwner != null && previousScheduleOwner != boundTicket) {
            return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = boundTicket
            )
        }
        if (!isScheduleTicketCurrent(context, boundTicket)) {
            return rejectStaleSchedule(context, request, hostAdmissionAcquired, boundTicket)
        }
        val dispatchWindow = configuredDispatchWindow(context)
        if (!isScheduleTicketCurrent(context, boundTicket)) {
            return rejectStaleSchedule(context, request, hostAdmissionAcquired, boundTicket)
        }
        DownloadOperationTrace.mark(
            operationTraceToken,
            DownloadOperationTracePhase.HOST_ADMISSION_REQUESTED
        )
        val hostAdmissionRequired = requiresTransferHostAdmission(currentState)
        if (hostAdmissionRequired && !tryAcquireHostAdmission(
                context = context,
                operationId = request.operationId,
                capacity = dispatchWindow
            )
        ) {
            if (!isScheduleTicketCurrent(context, boundTicket)) {
                return rejectStaleSchedule(context, request, hostAdmissionAcquired, boundTicket)
            }
            scheduleOwners.remove(request.operationId, boundTicket)
            enqueueDeferredSchedule(
                context = context,
                request = request,
                ticket = boundTicket
            )
            return DownloadExecutionSchedule.Deferred(
                "download host admission window is full"
            )
        }
        hostAdmissionAcquired = hostAdmissionRequired
        if (hostAdmissionRequired) {
            DownloadOperationTrace.mark(
                operationTraceToken,
                DownloadOperationTracePhase.HOST_ADMISSION_GRANTED
            )
        }
        val previousAdmissionOwner = synchronized(executionAdmissionLock) {
            hostAdmissionOwners.putIfAbsent(request.operationId, boundTicket)
        }
        if (previousAdmissionOwner != null && previousAdmissionOwner != boundTicket) {
            return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = boundTicket
            )
        }
        if (!isScheduleTicketCurrent(context, boundTicket) ||
            !isPersistedScheduleTicketCurrent(context, boundTicket)
        ) {
            return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = boundTicket
            )
        }
        val selectedBackend = selectDownloadExecutionBackend(
            sdkInt = sdkInt,
            userInitiated = request.userInitiated
        )
        scheduledBackend = when (selectedBackend) {
            DownloadExecutionSchedule.Backend.UIDT_JOB -> {
                if (!isScheduleTicketCurrent(context, boundTicket)) {
                    return rejectStaleSchedule(
                        context = context,
                        request = request,
                        hostAdmissionAcquired = hostAdmissionAcquired,
                        ticket = boundTicket
                    )
                }
                if (
                    scheduleUidtIfSupported(
                        context = context,
                        operationId = request.operationId,
                        sdkInt = sdkInt,
                        pendingJobLimit = dispatchWindow
                    )
                ) {
                    DownloadExecutionSchedule.Backend.UIDT_JOB
                } else if (
                    isScheduleTicketCurrent(context, boundTicket) &&
                    ForegroundDownloadWorker.schedule(context, request.operationId)
                ) {
                    DownloadExecutionSchedule.Backend.FOREGROUND_WORK
                } else {
                    null
                }
            }

            DownloadExecutionSchedule.Backend.FOREGROUND_WORK -> {
                if (!isScheduleTicketCurrent(context, boundTicket)) {
                    return rejectStaleSchedule(
                        context = context,
                        request = request,
                        hostAdmissionAcquired = hostAdmissionAcquired,
                        ticket = boundTicket
                    )
                }
                ForegroundDownloadWorker.schedule(context, request.operationId)
                    .takeIf { it }
                    ?.let { DownloadExecutionSchedule.Backend.FOREGROUND_WORK }
            }
        }
        if (scheduledBackend == null) {
            scheduleOwners.remove(request.operationId, boundTicket)
            releaseHostAdmissionIfIdle(
                context = context,
                operationId = request.operationId,
                ticket = boundTicket
            )
            hostAdmissionAcquired = false
            if (!isScheduleTicketCurrent(context, boundTicket)) {
                return rejectStaleSchedule(
                    context = context,
                    request = request,
                    hostAdmissionAcquired = hostAdmissionAcquired,
                    ticket = boundTicket
                )
            }
            enqueueDeferredSchedule(
                context = context,
                request = request,
                ticket = boundTicket
            )
            return DownloadExecutionSchedule.Deferred(
                "${selectedBackend.name} host temporarily rejected operation"
            )
        }
        val backendOwnerRegistered = synchronized(backendOwnershipLock) {
            val existingOwner = backendOwners[request.operationId]
            if (existingOwner == null || existingOwner.ticket == boundTicket) {
                backendOwners[request.operationId] = BackendOwner(
                    ticket = boundTicket,
                    backend = requireNotNull(scheduledBackend)
                )
                true
            } else {
                false
            }
        }
        if (!backendOwnerRegistered) {
            return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = boundTicket
            )
        }
        // 后端 API 返回后再次复核，避免清空刚好发生在发布调用期间
        if (!isScheduleTicketCurrent(context, boundTicket) ||
            !isPersistedScheduleTicketCurrent(context, boundTicket)
        ) {
            return rejectStaleSchedule(context, request, hostAdmissionAcquired, boundTicket)
        }
        operationIdsBySongKey[songKey] = request.operationId
        DownloadOperationTrace.mark(
            operationTraceToken,
            DownloadOperationTracePhase.BACKEND_SCHEDULED
        )
        withDeferredSchedulingLock {
            deferredRequests.remove(request)
        }
        return DownloadExecutionSchedule.Scheduled(scheduledBackend)
    } catch (error: Throwable) {
        if (!isScheduleTicketCurrent(context, ticket)) {
            return rejectStaleSchedule(
                context = context,
                request = request,
                hostAdmissionAcquired = hostAdmissionAcquired,
                ticket = currentTicket
            )
        }
        if (
            hostAdmissionAcquired ||
                hostAdmissionOwners[request.operationId] == currentTicket
        ) {
            releaseHostAdmissionIfIdle(
                context = context,
                operationId = request.operationId,
                ticket = currentTicket
            )
        }
        enqueueDeferredSchedule(
            context = context,
            request = request,
            ticket = currentTicket
        )
        return DownloadExecutionSchedule.Deferred(
            error.message ?: error.javaClass.simpleName
        )
    }
}

internal fun DefaultDownloadExecutionHost.captureScheduleTicket(
    context: Context,
    request: DownloadExecutionRequest
): ScheduleTicket? {
    val stableKey = request.song.stableKey().trim().takeIf(String::isNotBlank)
        ?: return null
    val operationId = normalizeDownloadOperationId(request.operationId) ?: return null
    val normalizedAttemptId = request.attemptId?.takeIf { it > 0L }
    val ticket = ScheduleTicket(
        operationId = operationId,
        stableKey = stableKey,
        attemptId = normalizedAttemptId,
        attemptBound = normalizedAttemptId != null,
        clearEpoch = PersistentDownloadClearFenceStore.currentEpoch(context)
    )
    return ticket.takeIf { isScheduleTicketCurrent(context, it) }
}

internal fun DefaultDownloadExecutionHost.newestScheduleAttempt(
    context: Context,
    request: DownloadExecutionRequest
): DownloadExecutionRequest {
    val persisted = operationStore.read(context, request.operationId)
        ?.takeIf { current ->
            current.operationId == request.operationId &&
                current.song.stableKey() == request.song.stableKey()
        }
        ?: return request
    val requestedAttempt = request.attemptId?.takeIf { it > 0L } ?: 0L
    val persistedAttempt = persisted.attemptId?.takeIf { it > 0L } ?: 0L
    return if (persistedAttempt > requestedAttempt) persisted else request
}

internal fun DefaultDownloadExecutionHost.bindPersistedScheduleTicket(
    context: Context,
    ticket: ScheduleTicket,
    allowAttemptRebind: Boolean = false
): ScheduleTicket? {
    if (!isScheduleTicketCurrent(context, ticket)) return null
    val persisted = operationStore.read(context, ticket.operationId) ?: return null
    if (
        persisted.operationId != ticket.operationId ||
        persisted.song.stableKey() != ticket.stableKey ||
        !allowAttemptRebind &&
            ticket.attemptId != null && persisted.attemptId != ticket.attemptId
    ) {
        return null
    }
    return ticket.copy(
        attemptId = if (allowAttemptRebind) {
            persisted.attemptId?.takeIf { it > 0L }
        } else {
            ticket.attemptId ?: persisted.attemptId?.takeIf { it > 0L }
        },
        attemptBound = true
    )
}

internal fun DefaultDownloadExecutionHost.isPersistedScheduleTicketCurrent(
    context: Context,
    ticket: ScheduleTicket
): Boolean {
    val persisted = operationStore.read(context, ticket.operationId) ?: return false
    return persisted.operationId == ticket.operationId &&
        persisted.song.stableKey() == ticket.stableKey &&
        if (ticket.attemptBound) {
            persisted.attemptId == ticket.attemptId
        } else {
            persisted.attemptId == null
        }
}

internal fun DefaultDownloadExecutionHost.isScheduleTicketCurrent(
    context: Context,
    ticket: ScheduleTicket
): Boolean {
    if (
        normalizeDownloadOperationId(ticket.operationId) != ticket.operationId ||
        ticket.stableKey.isBlank()
    ) {
        return false
    }
    if (
        PersistentDownloadClearFenceStore.isBlocked(
            context = context,
            stableKey = ticket.stableKey,
            operationId = ticket.operationId
        )
    ) {
        return false
    }
    return PersistentDownloadClearFenceStore.currentEpoch(context) == ticket.clearEpoch
}

internal fun DefaultDownloadExecutionHost.rejectStaleSchedule(
    context: Context,
    request: DownloadExecutionRequest,
    hostAdmissionAcquired: Boolean,
    ticket: ScheduleTicket? = null
): DownloadExecutionSchedule {
    val supersededByClear = ticket?.let { currentTicket ->
        PersistentDownloadClearFenceStore.isBlocked(
            context = context,
            stableKey = currentTicket.stableKey,
            operationId = currentTicket.operationId
        ) || PersistentDownloadClearFenceStore.currentEpoch(context) != currentTicket.clearEpoch
    } ?: PersistentDownloadClearFenceStore.isBlocked(
        context = context,
        stableKey = request.song.stableKey(),
        operationId = request.operationId
    )

    if (!supersededByClear) {
        return deferStaleScheduleRace(
            context = context,
            request = request,
            hostAdmissionAcquired = hostAdmissionAcquired,
            ticket = ticket
        )
    }

    if (
        hostAdmissionAcquired ||
            ticket != null && hostAdmissionOwners[request.operationId] == ticket
    ) {
        releaseHostAdmissionIfIdle(
            context = context,
            operationId = request.operationId,
            ticket = ticket
        )
    }
    if (ticket != null) {
        if (scheduleOwners.remove(request.operationId, ticket)) {
            operationIdsBySongKey.remove(request.song.stableKey(), request.operationId)
        }
    } else {
        operationIdsBySongKey.remove(request.song.stableKey(), request.operationId)
    }
    withDeferredSchedulingLock {
        deferredRequests.remove(request)
    }
    val persistedIdentityMatches = ticket?.let {
        it.attemptBound && isPersistedScheduleTicketCurrent(context, it)
    } ?: isPersistedRequestIdentityCurrent(context, request)
    if (persistedIdentityMatches) {
        runCatching {
            operationStore.requestCancel(context, request.operationId)
        }
    }
    ticket?.let { cancelBackendIfOwned(context, request.operationId, it) }
    return DownloadExecutionSchedule.Rejected(
        reason = "download schedule superseded by clear",
        retryable = false
    )
}

internal fun DefaultDownloadExecutionHost.deferStaleScheduleRace(
    context: Context,
    request: DownloadExecutionRequest,
    hostAdmissionAcquired: Boolean,
    ticket: ScheduleTicket?
): DownloadExecutionSchedule {
    val operationId = request.operationId
    val stableKey = request.song.stableKey()
    val latestRequest = operationStore.read(context, operationId)
        ?.takeIf { latest ->
            latest.operationId == operationId && latest.song.stableKey() == stableKey
        }
        ?: return DownloadExecutionSchedule.Rejected(
            reason = "download operation identity was replaced during handoff",
            retryable = false
        )
    val latestTicket = captureScheduleTicket(context, latestRequest)
        ?.let { captured ->
            bindPersistedScheduleTicket(
                context = context,
                ticket = captured,
                allowAttemptRebind = true
            )
        }
        ?: return DownloadExecutionSchedule.Rejected(
            reason = "download operation is no longer schedulable",
            retryable = false
        )

    if (
        hostAdmissionAcquired ||
            ticket != null && hostAdmissionOwners[operationId] == ticket
    ) {
        releaseHostAdmissionIfIdle(
            context = context,
            operationId = operationId,
            ticket = ticket
        )
    }

    val scheduleOwner = scheduleOwners[operationId]
    if (scheduleOwner == null || sameScheduleGeneration(scheduleOwner, latestTicket)) {
        if (scheduleOwner != null) {
            scheduleOwners.replace(operationId, scheduleOwner, latestTicket)
        }
    } else {
        ticket?.let { staleTicket ->
            scheduleOwners.remove(operationId, staleTicket)
        }
    }

    synchronized(executionAdmissionLock) {
        val admissionOwner = hostAdmissionOwners[operationId]
        if (admissionOwner != null && sameScheduleGeneration(admissionOwner, latestTicket)) {
            hostAdmissionOwners[operationId] = latestTicket
        }
    }

    val backendAlreadyScheduled = synchronized(backendOwnershipLock) {
        val backendOwner = backendOwners[operationId]
        when {
            backendOwner == null -> false
            sameScheduleGeneration(backendOwner.ticket, latestTicket) -> {
                backendOwners[operationId] = backendOwner.copy(ticket = latestTicket)
                true
            }
            else -> false
        }
    }

    withDeferredSchedulingLock {
        deferredRequests.remove(request)
    }
    if (!backendAlreadyScheduled) {
        enqueueDeferredSchedule(
            context = context,
            request = latestRequest,
            ticket = latestTicket
        )
    }
    return DownloadExecutionSchedule.Deferred(
        "download schedule identity changed during host handoff"
    )
}

internal fun DefaultDownloadExecutionHost.sameScheduleGeneration(
    first: ScheduleTicket,
    second: ScheduleTicket
): Boolean {
    return first.operationId == second.operationId &&
        first.stableKey == second.stableKey &&
        first.clearEpoch == second.clearEpoch
}

internal fun DefaultDownloadExecutionHost.rebindCompatibleScheduleOwners(
    operationId: String,
    next: ScheduleTicket
) {
    scheduleOwners[operationId]?.let { owner ->
        if (sameScheduleGeneration(owner, next)) {
            scheduleOwners.replace(operationId, owner, next)
        }
    }
    synchronized(executionAdmissionLock) {
        hostAdmissionOwners[operationId]?.let { owner ->
            if (sameScheduleGeneration(owner, next)) {
                hostAdmissionOwners[operationId] = next
            }
        }
    }
    synchronized(backendOwnershipLock) {
        backendOwners[operationId]?.let { owner ->
            if (sameScheduleGeneration(owner.ticket, next)) {
                backendOwners[operationId] = owner.copy(ticket = next)
            }
        }
    }
}

internal fun DefaultDownloadExecutionHost.rebindExecutionOwners(
    operationId: String,
    previous: ScheduleTicket,
    next: ScheduleTicket
): Boolean {
    if (previous.operationId != operationId || next.operationId != operationId) {
        return false
    }
    val admissionRebound = synchronized(executionAdmissionLock) {
        val owner = hostAdmissionOwners[operationId] ?: return@synchronized false
        if (owner != previous) return@synchronized false
        hostAdmissionOwners[operationId] = next
        true
    }
    if (!admissionRebound) return false
    scheduleOwners.replace(operationId, previous, next)
    return synchronized(backendOwnershipLock) {
        val owner = backendOwners[operationId]
        when {
            owner == null || owner.ticket == next -> true
            owner.ticket != previous -> false
            else -> {
                backendOwners[operationId] = owner.copy(ticket = next)
                true
            }
        }
    }
}

internal fun DefaultDownloadExecutionHost.removeBackendOwnerIfMatches(
    operationId: String,
    ticket: ScheduleTicket
): Boolean {
    return synchronized(backendOwnershipLock) {
        val owner = backendOwners[operationId] ?: return@synchronized false
        if (owner.ticket != ticket) return@synchronized false
        backendOwners.remove(operationId, owner)
    }
}

internal fun DefaultDownloadExecutionHost.rejectStaleExecution(
    context: Context,
    request: DownloadExecutionRequest,
    ticket: ScheduleTicket
): DownloadExecutionResult {
    val operationId = request.operationId
    scheduleOwners.remove(operationId, ticket)
    synchronized(executionAdmissionLock) {
        executingOperationIds.remove(operationId)
    }
    val backendOwned = removeBackendOwnerIfMatches(operationId, ticket)
    val persistedIdentityMatches = isPersistedScheduleTicketCurrent(context, ticket)
    if (persistedIdentityMatches) {
        runCatching { operationStore.requestCancel(context, operationId) }
    }
    if (backendOwned) {
        scheduledBackendCancellation(context, operationId)
    }
    releaseHostAdmissionIfIdle(
        context = context,
        operationId = operationId,
        ticket = ticket
    )
    return DownloadExecutionResult.Cancelled
}

internal fun DefaultDownloadExecutionHost.isPersistedRequestIdentityCurrent(
    context: Context,
    request: DownloadExecutionRequest
): Boolean {
    val persisted = operationStore.read(context, request.operationId) ?: return false
    return persisted.operationId == request.operationId &&
        persisted.song.stableKey() == request.song.stableKey() &&
        request.attemptId?.takeIf { it > 0L } != null &&
        persisted.attemptId == request.attemptId
}

internal fun DefaultDownloadExecutionHost.cancelBackendIfOwned(
    context: Context,
    operationId: String,
    ticket: ScheduleTicket
): Boolean {
    val owned = synchronized(backendOwnershipLock) {
        val owner = backendOwners[operationId] ?: return@synchronized false
        if (owner.ticket != ticket || !backendOwners.remove(operationId, owner)) {
            return@synchronized false
        }
        true
    }
    if (owned) {
        scheduledBackendCancellation(context, operationId)
    }
    return owned
}

internal fun DefaultDownloadExecutionHost.scheduledBackendCancellation(context: Context, operationId: String) {
    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        runCatching { cancelUidt(context, operationId) }
    }
    runCatching { ForegroundDownloadWorker.cancel(context, operationId) }
}
