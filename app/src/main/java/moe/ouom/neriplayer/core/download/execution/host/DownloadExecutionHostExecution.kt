package moe.ouom.neriplayer.core.download.execution.host

import moe.ouom.neriplayer.core.download.execution.clear.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.worker.ForegroundDownloadWorker
import moe.ouom.neriplayer.core.download.execution.worker.WifiBoundDownloadWakeWorker
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.ScheduleTicket
import moe.ouom.neriplayer.core.download.execution.host.DefaultDownloadExecutionHost.TransferSlotOwner
import android.content.Context
import android.os.Build
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey


internal fun DefaultDownloadExecutionHost.stopInternal(
    context: Context,
    operationId: String,
    preventReschedule: Boolean,
    cancelExecutionBackends: Boolean
) {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return
    val appContext = context.applicationContext
    val request = operationStore.read(appContext, normalizedId) ?: return
    PersistentDownloadClearFenceStore.withSchedulingPermit(
        context = appContext,
        onFenceActive = {
            cancel(appContext, normalizedId)
        },
        stableKey = request.song.stableKey(),
        operationId = normalizedId
    ) {
        val currentState = operationStore.currentState(appContext, normalizedId)
        if (!shouldHandleHostStop(currentState)) {
            // onStopJob 可能先于这里建立暂停标记。核心已提交时只清掉
            // 这个过期标记，不能让后台资产收尾被误判为用户取消
            AudioDownloadManager.pauseOperationDownloadForExecutionHost(
                operationId = normalizedId,
                durableState = currentState
            )
            if (preventReschedule) {
                WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
            }
            if (cancelExecutionBackends) {
                cancelExecutionBackends(appContext, normalizedId)
            }
            operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withSchedulingPermit
        }
        operationIdsBySongKey[request.song.stableKey()] = normalizedId
        val rescheduleBlocked = shouldBlockHostReschedule(
            preventReschedule = preventReschedule,
            alreadyStoppedByUser = operationStore.isStopped(appContext, normalizedId)
        )
        if (rescheduleBlocked) {
            WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
        }
        val retryPrepared = if (rescheduleBlocked) {
            operationStore.markStopped(appContext, normalizedId)
            false
        } else {
            // 让暂停的 operation 成为队列刷新时唯一可恢复的任务
            operationStore.updateState(
                context = appContext,
                operationId = normalizedId,
                state = "RETRYABLE",
                errorCode = "HOST_STOPPED"
            )
        }
        if (cancelExecutionBackends) {
            cancelExecutionBackends(appContext, normalizedId)
        }
        GlobalDownloadManager.stopDownloadOperation(
            context = appContext,
            songKey = request.song.stableKey(),
            expectedAttemptId = request.attemptId,
            rememberForRetry = retryPrepared,
            operationId = normalizedId,
            knownOperationState = currentState
        )
        if (rescheduleBlocked || !retryPrepared) {
            operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
        }
        releaseHostAdmissionIfIdle(appContext, normalizedId)
    }
}

internal fun DefaultDownloadExecutionHost.cancelExecutionBackends(context: Context, operationId: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        cancelUidt(context, operationId)
    }
    ForegroundDownloadWorker.cancel(context, operationId)
}

internal fun DefaultDownloadExecutionHost.logTransferAdmissionRejected(
    operationId: String,
    attemptId: Long?,
    reason: String
) {
    moe.ouom.neriplayer.core.logging.NPLogger.w(
        "DownloadExecutionHost",
        "拒绝传输槽位: operationId=$operationId, attemptId=$attemptId, " +
            "reason=$reason, active=${activeTransferOwners.size}, " +
            "reservations=${transferReservationOwners.size}"
    )
}

internal fun DefaultDownloadExecutionHost.signalTransferRelease(operationId: String) {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return
    pendingTransferReleaseOperationIds.add(normalizedId)
    transferReleaseSignals.trySend(normalizedId)
}

internal fun DefaultDownloadExecutionHost.drainTransferReleaseOperationIds(signalOperationId: String): Set<String> {
    val operationIds = linkedSetOf<String>()
    normalizeDownloadOperationId(signalOperationId)?.let(operationIds::add)
    operationIds += pendingTransferReleaseOperationIds
    operationIds.forEach(pendingTransferReleaseOperationIds::remove)
    return operationIds
}

internal fun DefaultDownloadExecutionHost.nextTransferOwnerToken(): Long {
    return transferOwnerSequence.incrementAndGet().coerceAtLeast(1L)
}

internal fun DefaultDownloadExecutionHost.reserveTransferSlot(
    operationId: String,
    attemptId: Long?,
    capacity: Int
): Long? {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return null
    if (attemptId != null && attemptId <= 0L) return null
    synchronized(executionAdmissionLock) {
        if (
            executingOperationIds.contains(normalizedId) ||
                activeTransferOwners.containsKey(normalizedId) ||
                transferReservationOwners.containsKey(normalizedId)
        ) {
            return null
        }
        if (activeTransferOwners.size + transferReservationOwners.size >= capacity) {
            return null
        }
        val token = nextTransferOwnerToken()
        transferReservationOwners[normalizedId] = TransferSlotOwner(
            token = token,
            attemptId = attemptId,
            ticket = null
        )
        return token
    }
}

internal fun DefaultDownloadExecutionHost.bindTransferReservationAttempt(
    operationId: String,
    ticket: ScheduleTicket
): Boolean {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
    synchronized(executionAdmissionLock) {
        val reservation = transferReservationOwners[normalizedId] ?: return true
        if (
            reservation.attemptId != null &&
                reservation.attemptId != ticket.attemptId
        ) {
            return false
        }
        transferReservationOwners[normalizedId] = reservation.copy(
            attemptId = ticket.attemptId,
            ticket = ticket
        )
        return true
    }
}

internal fun DefaultDownloadExecutionHost.rebindTransferReservationForCurrentAttempt(
    context: Context,
    operationId: String,
    persistedRequest: DownloadExecutionRequest,
    reservation: TransferSlotOwner,
    attemptId: Long?
): TransferSlotOwner? {
    val normalizedAttemptId = attemptId?.takeIf { it > 0L } ?: return null
    val ticket = reservation.ticket ?: return null
    if (
        reservation.attemptId != ticket.attemptId ||
            ticket.operationId != operationId ||
            ticket.stableKey != persistedRequest.song.stableKey() ||
            !isScheduleTicketCurrent(context, ticket)
    ) {
        return null
    }
    val admissionOwner = hostAdmissionOwners[operationId]
    if (admissionOwner != null && !sameScheduleGeneration(admissionOwner, ticket)) {
        return null
    }
    val rebound = reservation.copy(attemptId = normalizedAttemptId)
    transferReservationOwners[operationId] = rebound
    moe.ouom.neriplayer.core.logging.NPLogger.d(
        "DownloadExecutionHost",
        "传输预留位跟随当前 durable attempt: operationId=$operationId, " +
            "from=${reservation.attemptId}, to=$normalizedAttemptId"
    )
    return rebound
}

internal fun DefaultDownloadExecutionHost.releaseTransferReservation(
    operationId: String,
    reservationToken: Long
) {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return
    val released = synchronized(executionAdmissionLock) {
        val reservation = transferReservationOwners[normalizedId]
        if (reservation?.token != reservationToken) {
            false
        } else if (
            executingOperationIds.contains(normalizedId) &&
                !activeTransferOwners.containsKey(normalizedId)
        ) {
            // 另一个宿主可能已经赢得 execute claim，保留泵预留位，
            // 让它在真正开始网络传输时完成交接
            false
        } else {
            transferReservationOwners.remove(normalizedId, reservation)
            true
        }
    }
    if (released) signalTransferRelease(normalizedId)
}

internal fun DefaultDownloadExecutionHost.releaseUnclaimedTransferReservation(
    operationId: String,
    reservationToken: Long?
) {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return
    val token = reservationToken ?: return
    val released = synchronized(executionAdmissionLock) {
        val reservation = transferReservationOwners[normalizedId]
        if (reservation?.token != token) {
            false
        } else {
            transferReservationOwners.remove(normalizedId, reservation)
            true
        }
    }
    if (released) signalTransferRelease(normalizedId)
}

internal fun DefaultDownloadExecutionHost.transferLaneOccupancy(context: Context): Int {
    reconcileInactiveTransferOwners(context.applicationContext)
    return synchronized(executionAdmissionLock) {
        activeTransferOwners.size + transferReservationOwners.size
    }
}

internal fun DefaultDownloadExecutionHost.releaseTransferSlot(
    operationId: String,
    attemptId: Long? = null,
    ticket: ScheduleTicket? = null
): Boolean {
    val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
    if (attemptId != null && attemptId <= 0L) return false
    val released = synchronized(executionAdmissionLock) {
        val owner = activeTransferOwners[normalizedId] ?: return@synchronized false
        if (owner.attemptId != attemptId) return@synchronized false
        if (ticket != null && owner.ticket != null && owner.ticket != ticket) {
            return@synchronized false
        }
        if (transferReleaseInFlightTokens.contains(owner.token)) {
            return@synchronized false
        }
        if (transferReleasePendingTokens.contains(owner.token)) {
            return@synchronized false
        }
        activeTransferOwners.remove(normalizedId, owner)
    }
    if (released) signalTransferRelease(normalizedId)
    return released
}
