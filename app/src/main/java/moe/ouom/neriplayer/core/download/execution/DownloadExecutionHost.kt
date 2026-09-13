package moe.ouom.neriplayer.core.download.execution

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTrace
import moe.ouom.neriplayer.core.download.observability.DownloadOperationTracePhase
import moe.ouom.neriplayer.core.download.observability.DownloadPumpSelectionMetrics
import moe.ouom.neriplayer.core.download.observability.DownloadPumpSelectionTrace
import moe.ouom.neriplayer.core.download.observability.DownloadStartupTrace
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.currentDownloadParallelism
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import moe.ouom.neriplayer.data.model.stableKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DefaultDownloadExecutionHost(
    private val operationStore: DownloadExecutionOperationStore =
        DownloadExecutionOperationStore(),
    private val entryPoint: DownloadOperationEntryPoint =
        ExistingDownloadOperationEntryPoint,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val downloadParallelismProvider: (Context) -> Int =
        ::currentDownloadParallelism,
    private val pendingUidtGraceDelayProvider: ((Context, DownloadExecutionRequest) -> Long)? = null,
    private val transferPermitOwnersProvider: () -> Set<String> = {
        AudioDownloadManager.transferPermitSnapshot().heldPermitOwners
    },
    private val retryDeadlineWakeCoordinator: DownloadRetryDeadlineWakeCoordinator =
        DownloadRetryDeadlineWakeCoordinator { context, delayMs ->
            ForegroundDownloadWorker.schedulePump(context, initialDelayMs = delayMs)
        }
) : DownloadExecutionHost {
    private val operationIdsBySongKey = ConcurrentHashMap<String, String>()
    private val executingOperationIds = ConcurrentHashMap.newKeySet<String>()
    /** 传输槽位只保留到 Core Commit，后续 enrichment 不再占用共享泵窗口 */
    private val activeTransferOwners = ConcurrentHashMap<String, TransferSlotOwner>()
    /** 泵已选中但尚未完成 execute claim 的保留位，避免外部 worker 竞态超发 */
    private val transferReservationOwners = ConcurrentHashMap<String, TransferSlotOwner>()
    private val transferOwnerSequence = AtomicLong(0L)
    private val transferReleaseInFlightTokens = ConcurrentHashMap.newKeySet<Long>()
    /** Core Commit 的 durable 释放失败时保留 owner，等待同一 token 的回调重试 */
    private val transferReleasePendingTokens = ConcurrentHashMap.newKeySet<Long>()
    /**
     * 释放通知只负责唤醒泵，具体 operation 身份放在集合里保留，避免多个
     * Core Commit 在同一帧内发送时被 CONFLATED 通道覆盖
     */
    private val transferReleaseSignals = Channel<String>(Channel.CONFLATED)
    private val pendingTransferReleaseOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val systemRetryStopOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val explicitSchedulerStopOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val executionAdmissionLock = Any()
    private val backendOwnershipLock = Any()
    private val hostAdmissionOwners = ConcurrentHashMap<String, ScheduleTicket>()
    private val backendOwners = ConcurrentHashMap<String, BackendOwner>()
    private val scheduleOwners = ConcurrentHashMap<String, ScheduleTicket>()
    private val deferredRequests = DeferredDownloadScheduleQueue()
    /** 把延后队列和运行标记作为一个状态机检查，避免入队与退出检查丢失唤醒 */
    private val deferredSchedulingLock = Any()
    private val deferredSchedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferredSchedulingRunning = AtomicBoolean(false)
    /** WorkManager、UIDT 和进程内唤醒可能同时触发泵，统一串行读取和接管队列 */
    private val pumpMutex = Mutex()

    private data class PumpCandidateSelection(
        val requests: List<DownloadExecutionRequest>,
        val hasSchedulableRequest: Boolean,
        val shortestPendingUidtGraceDelayMs: Long?,
        val nextRetryAtMs: Long?,
        val nextCursor: DownloadExecutionPumpCursor?,
        val exhausted: Boolean,
        val pendingPage: PumpPendingPage?
    )

    private data class PumpPendingPage(
        val requests: List<DownloadExecutionRequest>,
        val continuationCursor: DownloadExecutionPumpCursor?
    )

    private data class PumpExecutionCompletion(
        val operationId: String,
        val execution: Deferred<DownloadExecutionResult>,
        val result: DownloadExecutionResult
    )

    private data class PumpTransferRelease(
        val operationIds: Set<String>
    )

    /** 调度期间绑定的清空代次和 operation 身份，避免长 I/O 返回后越过新代次 */
    private data class ScheduleTicket(
        val operationId: String,
        val stableKey: String,
        val attemptId: Long?,
        val attemptBound: Boolean,
        val clearEpoch: Long
    )

    private data class BackendOwner(
        val ticket: ScheduleTicket,
        val backend: DownloadExecutionSchedule.Backend
    )

    /** transfer lane 的唯一 owner，避免旧 execute 的 finally 释放新 attempt 的槽位 */
    private data class TransferSlotOwner(
        val token: Long,
        val attemptId: Long?,
        val ticket: ScheduleTicket?,
        /** 仅生产传输会携带真实 permit owner，测试和旧调用保持 null */
        val transferPermitOwnerKey: String? = null
    )

    override fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule {
        val appContext = context.applicationContext
        val traceToken = DownloadOperationTrace.begin(
            operationId = request.operationId,
            attemptId = request.attemptId
        )
        DownloadOperationTrace.mark(
            traceToken,
            DownloadOperationTracePhase.ENQUEUED
        )
        val ticket = captureScheduleTicket(appContext, request)
            ?: return DownloadExecutionSchedule.Rejected(
                "download clear is in progress"
            )
        return PersistentDownloadClearFenceStore.withSchedulingPermit(
            context = appContext,
            onFenceActive = {
                DownloadExecutionSchedule.Rejected("download clear is in progress")
            },
            stableKey = request.song.stableKey(),
            operationId = request.operationId
        ) {
            scheduleWithTicket(
                context = appContext,
                request = request,
                ticket = ticket
            )
        }
    }

    private fun scheduleWithTicket(
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
            // 同一 operation 可能已经被共享泵或 UIDT 并发刷新 attempt。
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

    private fun captureScheduleTicket(
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

    private fun bindPersistedScheduleTicket(
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

    private fun isPersistedScheduleTicketCurrent(
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

    private fun isScheduleTicketCurrent(
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

    private fun rejectStaleSchedule(
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

    /**
     * clear epoch 没变化时，ticket 失效只是并发 handoff 的身份刷新。
     * 这种情况不能把 durable operation 标记为失败，更不能请求取消；绑定最新 attempt
     * 后交给已有 backend 或 deferred queue 继续执行。
     */
    private fun deferStaleScheduleRace(
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

    private fun sameScheduleGeneration(
        first: ScheduleTicket,
        second: ScheduleTicket
    ): Boolean {
        return first.operationId == second.operationId &&
            first.stableKey == second.stableKey &&
            first.clearEpoch == second.clearEpoch
    }

    private fun rebindCompatibleScheduleOwners(
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

    /**
     * 已取得 durable claim 的执行可以采用同一 operation 的最新 attempt，
     * 但清空代次、operationId 和 stableKey 仍必须保持不变
     */
    private fun rebindExecutionOwners(
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

    /** 删除与当前执行 ticket 对应的后端 owner */
    private fun removeBackendOwnerIfMatches(
        operationId: String,
        ticket: ScheduleTicket
    ): Boolean {
        return synchronized(backendOwnershipLock) {
            val owner = backendOwners[operationId] ?: return@synchronized false
            if (owner.ticket != ticket) return@synchronized false
            backendOwners.remove(operationId, owner)
        }
    }

    /** 失效 ticket 不再进入下载入口，并释放仍属于它的内存和持久准入 */
    private fun rejectStaleExecution(
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

    private fun isPersistedRequestIdentityCurrent(
        context: Context,
        request: DownloadExecutionRequest
    ): Boolean {
        val persisted = operationStore.read(context, request.operationId) ?: return false
        return persisted.operationId == request.operationId &&
            persisted.song.stableKey() == request.song.stableKey() &&
            request.attemptId?.takeIf { it > 0L } != null &&
            persisted.attemptId == request.attemptId
    }

    private fun cancelBackendIfOwned(
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

    private fun scheduledBackendCancellation(context: Context, operationId: String) {
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            runCatching { cancelUidt(context, operationId) }
        }
        runCatching { ForegroundDownloadWorker.cancel(context, operationId) }
    }

    private companion object {
        private const val PUMP_MAX_BATCHES_PER_RUN = 256
        private val SCHEDULABLE_OPERATION_STATES = setOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE"
        )
    }

    override fun cancel(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        scheduleOwners.remove(normalizedId)
        synchronized(backendOwnershipLock) {
            backendOwners.remove(normalizedId)
        }
        withDeferredSchedulingLock {
            deferredRequests.remove(normalizedId)
        }
        WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
        val request = operationStore.read(appContext, normalizedId)
        val cancelAccepted = request != null &&
            operationStore.requestCancel(appContext, normalizedId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(appContext, normalizedId)
        }
        ForegroundDownloadWorker.cancel(appContext, normalizedId)
        if (cancelAccepted) {
            GlobalDownloadManager.cancelDownloadOperationFromHost(
                songKey = request.song.stableKey(),
                operationId = normalizedId
            )
        }
        request?.song?.stableKey()?.let { songKey ->
            operationIdsBySongKey.remove(songKey, normalizedId)
        }
        releaseHostAdmissionIfIdle(appContext, normalizedId)
    }

    override fun cancelForSong(
        context: Context,
        songKey: String
    ) {
        val appContext = context.applicationContext
        buildList {
            operationIdsBySongKey[songKey]?.let(::add)
            addAll(operationStore.findOperationIdsForSong(appContext, songKey))
        }.distinct().forEach { operationId ->
            cancel(appContext, operationId)
        }
    }

    override fun cancelAll(
        context: Context,
        operationIds: Collection<String>
    ) {
        val normalizedIds = operationIds.mapNotNull(::normalizeDownloadOperationId).toSet()
        if (normalizedIds.isEmpty()) return
        val appContext = context.applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            UidtDownloadJobService.cancelAll(appContext, normalizedIds)
        }
        ForegroundDownloadWorker.cancelAll(appContext, normalizedIds)
        WifiBoundDownloadWakeWorker.cancelAll(appContext, normalizedIds)
        normalizedIds.forEach { operationId ->
            scheduleOwners.remove(operationId)
            synchronized(backendOwnershipLock) {
                backendOwners.remove(operationId)
            }
        }
        val idleOperationIds = synchronized(executionAdmissionLock) {
            normalizedIds.filterNot(executingOperationIds::contains)
        }
        operationIdsBySongKey.entries.removeIf { entry -> entry.value in normalizedIds }
        withDeferredSchedulingLock {
            deferredRequests.removeAll(normalizedIds)
        }
        if (idleOperationIds.isNotEmpty()) {
            idleOperationIds.forEach { operationId ->
                releaseHostAdmissionIfIdle(appContext, operationId)
            }
        }
    }

    internal fun cancelAllOwned(context: Context) {
        val appContext = context.applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            UidtDownloadJobService.cancelAllOwned(appContext)
        }
        ForegroundDownloadWorker.cancelAllOwned(appContext)
        WifiBoundDownloadWakeWorker.cancelAllOwned(appContext)
        val ownedOperationIds = buildSet {
            addAll(operationIdsBySongKey.values)
            addAll(
                withDeferredSchedulingLock {
                    deferredRequests.operationIds()
                }
            )
            synchronized(executionAdmissionLock) {
                addAll(hostAdmissionOwners.keys)
            }
        }
        val idleOperationIds = synchronized(executionAdmissionLock) {
            ownedOperationIds.filterNot(executingOperationIds::contains)
        }
        idleOperationIds.forEach { operationId ->
            releaseHostAdmissionIfIdle(appContext, operationId)
        }
        scheduleOwners.clear()
        synchronized(backendOwnershipLock) {
            backendOwners.clear()
        }
        operationIdsBySongKey.clear()
        withDeferredSchedulingLock {
            deferredRequests.clear()
        }
    }

    override fun stopForSong(
        context: Context,
        songKey: String,
        preventReschedule: Boolean
    ) {
        val appContext = context.applicationContext
        buildList {
            operationIdsBySongKey[songKey]?.let(::add)
            addAll(operationStore.findOperationIdsForSong(appContext, songKey))
        }.distinct().forEach { operationId ->
            stop(appContext, operationId, preventReschedule)
        }
    }

    override fun stop(
        context: Context,
        operationId: String,
        preventReschedule: Boolean
    ) {
        stopInternal(
            context = context,
            operationId = operationId,
            preventReschedule = preventReschedule,
            cancelExecutionBackends = true
        )
    }

    internal fun stopForSystemRetry(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        prepareSchedulerStop(normalizedId, preventReschedule = false)
        stopInternal(
            context = context,
            operationId = normalizedId,
            preventReschedule = false,
            cancelExecutionBackends = false
        )
    }

    internal fun prepareSchedulerStop(
        operationId: String,
        preventReschedule: Boolean
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        synchronized(executionAdmissionLock) {
            if (!executingOperationIds.contains(normalizedId)) return
            if (preventReschedule) {
                explicitSchedulerStopOperationIds.add(normalizedId)
                systemRetryStopOperationIds.remove(normalizedId)
            } else if (!explicitSchedulerStopOperationIds.contains(normalizedId)) {
                systemRetryStopOperationIds.add(normalizedId)
            }
        }
        // 先于 Job/Worker 的取消建立保留标记，避免宿主回调与下载收尾并发时删除 staging
        if (AudioDownloadManager.isCoreCommittedOperation(normalizedId)) {
            // core 已经提交后，onStopJob 只能中断宿主，不得撤销增强阶段的引用所有权
            AudioDownloadManager.clearOperationPauseForExecutionHost(normalizedId)
        } else {
            AudioDownloadManager.pauseOperationDownloadForExecutionHost(normalizedId)
        }
    }

    private fun stopInternal(
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

    private fun cancelExecutionBackends(context: Context, operationId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(context, operationId)
        }
        ForegroundDownloadWorker.cancel(context, operationId)
    }

    override fun externallyStoppedSongKeys(context: Context): Set<String> {
        return operationStore.stoppedSongKeys(context.applicationContext)
    }

    override fun requiresExplicitResume(
        context: Context,
        operationId: String?
    ): Boolean {
        val normalizedId = operationId?.let(::normalizeDownloadOperationId) ?: return false
        val appContext = context.applicationContext
        val request = operationStore.read(appContext, normalizedId) ?: return false
        if (!request.userInitiated || sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }
        val state = operationStore.currentState(appContext, normalizedId)
        return shouldRequireExplicitResume(
            userInitiated = request.userInitiated,
            state = state,
            hasPendingUidtJob = hasPendingUidtJob(appContext, normalizedId),
            stopRequestedByUser = operationStore.isStopped(appContext, normalizedId),
            cancellationRequestedByUser = operationStore.isUserCancellationRequested(
                appContext,
                normalizedId
            ),
            resumePending = operationStore.isExplicitResumePending(appContext, normalizedId)
        )
    }

    override fun operationIdForSong(context: Context, songKey: String): String? {
        return operationStore.findOperationIdForSong(
            context.applicationContext,
            songKey
        )
    }

    override fun isExecuting(operationId: String): Boolean {
        return executingOperationIds.contains(operationId)
    }

    override fun onTransferStarted(
        context: Context,
        operationId: String,
        attemptId: Long?,
        transferPermitOwnerKey: String?
    ): Long? {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return null
        if (attemptId != null && attemptId <= 0L) return null
        val normalizedAttemptId = attemptId
        val normalizedPermitOwnerKey = transferPermitOwnerKey
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        if (transferPermitOwnerKey != null && normalizedPermitOwnerKey == null) {
            logTransferAdmissionRejected(
                operationId = normalizedId,
                attemptId = normalizedAttemptId,
                reason = "blank_permit_owner"
            )
            return null
        }
        val persistedRequest = try {
            operationStore.read(context.applicationContext, normalizedId)
        } catch (error: Throwable) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "读取 transfer attempt 失败，拒绝启动回调: " +
                    "operationId=$normalizedId, error=${error.message}",
                error
            )
            return null
        } ?: return null
        if (persistedRequest.attemptId != normalizedAttemptId) {
            logTransferAdmissionRejected(
                operationId = normalizedId,
                attemptId = normalizedAttemptId,
                reason = "persisted_attempt_mismatch"
            )
            return null
        }
        // 网络 permit 才是物理并发的唯一权威。Core Commit、网络断开或宿主停止
        // 的回调即使遗漏，也不能让旧的内存镜像永久占住下一首的补位窗口
        reconcileInactiveTransferOwners(context.applicationContext)
        val configuredCapacity = configuredDownloadParallelism(context.applicationContext)
        return synchronized(executionAdmissionLock) {
            if (!executingOperationIds.contains(normalizedId)) {
                logTransferAdmissionRejected(
                    operationId = normalizedId,
                    attemptId = normalizedAttemptId,
                    reason = "operation_not_executing"
                )
                return@synchronized null
            }
            val activeOwner = activeTransferOwners[normalizedId]
            if (activeOwner != null) {
                return@synchronized activeOwner
                    .takeIf { owner -> owner.attemptId == normalizedAttemptId }
                    ?.token
            }
            var reservation = transferReservationOwners[normalizedId]
            if (reservation != null && reservation.attemptId != normalizedAttemptId) {
                reservation = rebindTransferReservationForCurrentAttempt(
                    context = context.applicationContext,
                    operationId = normalizedId,
                    persistedRequest = persistedRequest,
                    reservation = reservation,
                    attemptId = normalizedAttemptId
                ) ?: run {
                    logTransferAdmissionRejected(
                        operationId = normalizedId,
                        attemptId = normalizedAttemptId,
                        reason = "reservation_attempt_mismatch"
                    )
                    return@synchronized null
                }
            }
            // 兼容旧调用时仍保持原有保护。生产调用已经持有真实 permit，重复
            // 使用宿主镜像限流会在网络切换或回调遗漏后产生错误拒绝
            if (
                normalizedPermitOwnerKey == null &&
                    activeTransferOwners.size >= configuredCapacity
            ) {
                logTransferAdmissionRejected(
                    operationId = normalizedId,
                    attemptId = normalizedAttemptId,
                    reason = "legacy_host_capacity_full"
                )
                return@synchronized null
            }
            val owner = reservation ?: TransferSlotOwner(
                token = nextTransferOwnerToken(),
                attemptId = normalizedAttemptId,
                ticket = hostAdmissionOwners[normalizedId]
            )
            if (reservation != null) {
                transferReservationOwners.remove(normalizedId, reservation)
            }
            activeTransferOwners[normalizedId] = owner.copy(
                attemptId = normalizedAttemptId,
                ticket = hostAdmissionOwners[normalizedId] ?: owner.ticket,
                transferPermitOwnerKey = normalizedPermitOwnerKey
            )
            owner.token
        }
    }

    /**
     * 真实 permit 已释放时，回收没有机会收到 Core Commit 或 finally 回调的内存
     * 槽位。仅处理携带 permit 身份的生产 owner，不触碰旧入口和测试的显式 owner
     */
    private fun reconcileInactiveTransferOwners(context: Context? = null): Set<String> {
        val heldPermitOwners = runCatching {
            transferPermitOwnersProvider()
        }.onFailure { error ->
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "读取真实下载 permit 快照失败，跳过宿主槽位修复: ${error.message}",
                error
            )
        }.getOrNull() ?: return emptySet()
        val releasedOperationIds = synchronized(executionAdmissionLock) {
            activeTransferOwners.entries
                .toList()
                .mapNotNull { (operationId, owner) ->
                    val permitOwnerKey = owner.transferPermitOwnerKey ?: return@mapNotNull null
                    if (permitOwnerKey in heldPermitOwners) return@mapNotNull null
                    if (!activeTransferOwners.remove(operationId, owner)) return@mapNotNull null
                    transferReleaseInFlightTokens.remove(owner.token)
                    transferReleasePendingTokens.remove(owner.token)
                    operationId
                }
                .toSet()
        }
        releasedOperationIds.forEach(::signalTransferRelease)
        // Core Commit 的宿主准入持久化释放可能先失败。真实 permit 已经消失时，
        // 不能只清理内存 owner，否则后续多个失败会把 Room 准入窗口逐步占满
        context?.applicationContext?.let { appContext ->
            releasedOperationIds.forEach { operationId ->
                releaseHostAdmissionIfIdle(appContext, operationId)
            }
        }
        if (releasedOperationIds.isNotEmpty()) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "真实 permit 已释放，回收失联传输槽位: " +
                    "operations=${releasedOperationIds.size}"
            )
        }
        return releasedOperationIds
    }

    private fun logTransferAdmissionRejected(
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

    override fun onCoreCommitted(
        context: Context,
        operationId: String,
        attemptId: Long?,
        transferOwnerToken: Long?
    ): Boolean {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return false
        if (attemptId != null && attemptId <= 0L) return false
        if (transferOwnerToken == null) return false
        val normalizedAttemptId = attemptId
        val owner = synchronized(executionAdmissionLock) {
            val current = activeTransferOwners[normalizedId] ?: return@synchronized null
            if (current.attemptId != normalizedAttemptId) return@synchronized null
            if (transferOwnerToken != current.token) {
                return@synchronized null
            }
            if (!transferReleaseInFlightTokens.add(current.token)) {
                return@synchronized null
            }
            current
        } ?: return false

        // core commit 回调携带的 owner token 才是传输槽位的权威身份。持久
        // attempt 可能已经被重试/用户意图刷新，不能用它阻塞下一首补位。
        // 只有清空代次或歌曲身份发生变化时，才保留新代次的宿主准入。
        val admissionOwner = synchronized(executionAdmissionLock) {
            hostAdmissionOwners[normalizedId]
        }
        val shouldReleaseAdmission = admissionOwner == null ||
            owner.ticket?.let { ticket ->
                sameScheduleGeneration(admissionOwner, ticket)
            } == true
        if (!shouldReleaseAdmission) {
            moe.ouom.neriplayer.core.logging.NPLogger.d(
                "DownloadExecutionHost",
                "Core Commit 仅释放旧 transfer lane，保留新代次宿主准入: " +
                    "operationId=$normalizedId, callbackAttempt=$normalizedAttemptId, " +
                    "admissionOwner=$admissionOwner"
            )
        }

        val admissionReleased = if (!shouldReleaseAdmission) {
            true
        } else {
            runCatching {
                // execute() 仍可能在 enrichment 阶段运行，不能调用只接受 idle 的释放入口
                operationStore.releaseHostAdmission(context.applicationContext, normalizedId)
            }.onFailure { error ->
                synchronized(executionAdmissionLock) {
                    transferReleaseInFlightTokens.remove(owner.token)
                    transferReleasePendingTokens.add(owner.token)
                }
                moe.ouom.neriplayer.core.logging.NPLogger.w(
                    "DownloadExecutionHost",
                    "Core Commit 后释放宿主准入失败，保留传输 owner 等待 finally/retry: " +
                        "operationId=$normalizedId, error=${error.message}",
                    error
                )
            }.isSuccess
        }
        if (!admissionReleased) return false

        val released = synchronized(executionAdmissionLock) {
            val current = activeTransferOwners[normalizedId]
            if (current?.token != owner.token) {
                transferReleaseInFlightTokens.remove(owner.token)
                false
            } else {
                activeTransferOwners.remove(normalizedId, current)
                transferReleaseInFlightTokens.remove(owner.token)
                transferReleasePendingTokens.remove(owner.token)
                if (shouldReleaseAdmission) {
                    admissionOwner?.let { ticket ->
                        hostAdmissionOwners.remove(normalizedId, ticket)
                    }
                }
                true
            }
        }
        if (!released) return false
        signalTransferRelease(normalizedId)
        triggerDeferredSchedules(context.applicationContext)
        return true
    }

    /** 把多个释放事件折叠成一次唤醒，但不丢掉任何 operation 身份 */
    private fun signalTransferRelease(operationId: String) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        pendingTransferReleaseOperationIds.add(normalizedId)
        transferReleaseSignals.trySend(normalizedId)
    }

    private fun drainTransferReleaseOperationIds(signalOperationId: String): Set<String> {
        val operationIds = linkedSetOf<String>()
        normalizeDownloadOperationId(signalOperationId)?.let(operationIds::add)
        operationIds += pendingTransferReleaseOperationIds
        operationIds.forEach(pendingTransferReleaseOperationIds::remove)
        return operationIds
    }

    private fun nextTransferOwnerToken(): Long {
        return transferOwnerSequence.incrementAndGet().coerceAtLeast(1L)
    }

    private fun reserveTransferSlot(
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

    private fun bindTransferReservationAttempt(
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

    /**
     * 批量任务会先创建下载 task，随后在真正传输前把同一 operation 的 attempt
     * 刷新为 task 的当前身份。只要歌曲和清空代次未变，泵的预留位必须跟随该
     * 已持久化的身份，不能让旧 attempt 永久占住并发窗口
     */
    private fun rebindTransferReservationForCurrentAttempt(
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

    private fun releaseTransferReservation(
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

    /** execute 结束时回收仍未提升为 active owner 的泵预留位 */
    private fun releaseUnclaimedTransferReservation(
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

    private fun transferLaneOccupancy(context: Context): Int {
        reconcileInactiveTransferOwners(context.applicationContext)
        return synchronized(executionAdmissionLock) {
            activeTransferOwners.size + transferReservationOwners.size
        }
    }

    private fun releaseTransferSlot(
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

    override fun markUserRequestedProcessExitOperations(context: Context): Set<String> {
        if (sdkInt < Build.VERSION_CODES.R) return emptySet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return emptySet()
        val latestExit = latestProcessExit(activityManager, context.packageName) ?: return emptySet()
        // 从最近任务移除和强行停止都可能报告 REASON_USER_REQUESTED，这属于进程生命周期
        // 变化而不是取消下载，持久 operation 要等下次打开应用后恢复
        if (!isUserRequestedProcessExitReason(latestExit.reason)) {
            return emptySet()
        }
        val preferences = context.getSharedPreferences(
            PROCESS_EXIT_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val lastHandledTimestamp = preferences.getLong(PROCESS_EXIT_TIMESTAMP_KEY, 0L)
        if (latestExit.timestamp <= lastHandledTimestamp) {
            return emptySet()
        }
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            preferences.edit {
                putLong(PROCESS_EXIT_TIMESTAMP_KEY, latestExit.timestamp)
            }
            return emptySet()
        }
        val activeStates = listOf("PENDING_QUEUE", "QUEUED", "RETRYABLE") +
            INTERRUPTED_DOWNLOAD_OPERATION_STATES
        val entries = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                DownloadExecutionRoomStore.listByStatesAnyLibrary(
                    context = context,
                    states = activeStates,
                    excludeUserStoppedOperations = true
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "读取用户停止进程的 durable operation 失败，保留退出标记待下次重试: " +
                    error.message,
                error
            )
            return emptySet()
        }
        val recoveredKeys = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                DownloadExecutionRoomStore.markUserRequestedProcessExitOperations(
                    context = context.applicationContext,
                    entries = entries
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "写入用户停止标记失败，保留退出时间戳待下次重试: ${error.message}",
                error
            )
            return emptySet()
        }
        val expectedUserInitiatedCount = entries
            .asSequence()
            .filter { it.request.userInitiated }
            .map { it.request.song.stableKey() }
            .toSet()
            .size
        if (recoveredKeys.size < expectedUserInitiatedCount) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "部分下载 operation 未完成进程退出恢复，保留退出时间戳待下次重试"
            )
            return emptySet()
        }
        preferences.edit {
            putLong(PROCESS_EXIT_TIMESTAMP_KEY, latestExit.timestamp)
        }
        return recoveredKeys
    }

    override suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult = withContext(Dispatchers.IO) {
        val normalizedId = normalizeDownloadOperationId(operationId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        val appContext = context.applicationContext
        val initialRequest = operationStore.readSuspending(appContext, normalizedId)
            ?: run {
                operationStore.updateStateSuspending(
                    context = appContext,
                    operationId = normalizedId,
                    state = "INVALID",
                    errorCode = "INVALID_OPERATION_PAYLOAD"
                )
                moe.ouom.neriplayer.core.logging.NPLogger.w(
                    "NERI-DownloadHost",
                    "下载 operation 读取失败: operationId=$normalizedId, reason=missing_or_unreadable"
                )
                releaseHostAdmissionIfIdleSuspending(appContext, normalizedId)
                return@withContext DownloadExecutionResult.MissingOperation
            }
        var operationTraceToken = DownloadOperationTrace.begin(
            operationId = normalizedId,
            attemptId = initialRequest.attemptId
        )
        if (
            PersistentDownloadClearFenceStore.isBlocked(
                context = appContext,
                stableKey = initialRequest.song.stableKey(),
                operationId = normalizedId
            )
        ) {
            try {
                operationStore.requestCancelSuspending(appContext, normalizedId)
            } catch (_: Throwable) {
                // 清空栅栏已经生效，取消标记失败时由下一轮恢复继续收敛
            }
            releaseHostAdmissionIfIdleSuspending(appContext, normalizedId)
            return@withContext DownloadExecutionResult.Cancelled
        }
        val initialTicket = captureScheduleTicket(appContext, initialRequest)
            ?: run {
                releaseHostAdmissionIfIdleSuspending(appContext, normalizedId)
                return@withContext DownloadExecutionResult.Cancelled
            }
        var executionTicket = bindPersistedScheduleTicket(
            context = appContext,
            ticket = initialTicket,
            allowAttemptRebind = true
        ) ?: run {
            releaseHostAdmissionIfIdleSuspending(appContext, normalizedId)
            return@withContext DownloadExecutionResult.Retry
        }
        // 调度线程和实际 OS 宿主启动之间允许同一 clear epoch 内刷新 attempt。
        // 把仍属于同一 operation generation 的 owner 一并前移，避免 worker 因为
        // 只差 attemptId 就把有效下载判成并发取消。
        rebindCompatibleScheduleOwners(normalizedId, executionTicket)
        if (operationStore.isStoppedSuspending(appContext, normalizedId)) {
            releaseHostAdmissionIfIdleSuspending(appContext, normalizedId)
            return@withContext DownloadExecutionResult.UserStopped
        }
        resolvePreExecutionResult(
            operationStore.currentStateSuspending(appContext, normalizedId)
        )?.let { result ->
            releaseHostAdmissionIfIdleSuspending(appContext, normalizedId)
            return@withContext result
        }
        if (
            !isScheduleTicketCurrent(appContext, executionTicket) ||
            !isPersistedScheduleTicketCurrent(appContext, executionTicket)
        ) {
            return@withContext rejectStaleExecution(
                context = appContext,
                request = initialRequest,
                ticket = executionTicket
            )
        }
        if (synchronized(executionAdmissionLock) { executingOperationIds.contains(normalizedId) }) {
            return@withContext resolveConcurrentExecutionResult(
                systemRetryStopPending = systemRetryStopOperationIds.contains(normalizedId)
            )
        }
        // Room 访问必须发生在短内存临界区之外，避免清空或进度回调被
        // 一个挂起的数据库操作长期阻塞
        DownloadOperationTrace.mark(
            operationTraceToken,
            DownloadOperationTracePhase.HOST_ADMISSION_REQUESTED
        )
        val stateBeforeHostAdmission = operationStore.currentStateSuspending(
            appContext,
            normalizedId
        )
        val hostAdmissionRequired = requiresTransferHostAdmission(stateBeforeHostAdmission)
        val hostAdmissionAcquired = !hostAdmissionRequired ||
            tryAcquireHostAdmissionSuspending(appContext, normalizedId)
        if (hostAdmissionRequired && hostAdmissionAcquired) {
            DownloadOperationTrace.mark(
                operationTraceToken,
                DownloadOperationTracePhase.HOST_ADMISSION_GRANTED
            )
        }
        val stateBeforeClaim = operationStore.currentStateSuspending(appContext, normalizedId)
        if (!hostAdmissionAcquired) {
            resolvePreExecutionResult(stateBeforeClaim)?.let { result ->
                return@withContext result
            }
            // 宿主窗口暂满时保留 operation，但不要让它停留在 QUEUED 等待
            // 普通退避；Core Commit 或 owner 释放会立即唤醒共享泵
            operationStore.updateStateSuspending(
                context = appContext,
                operationId = normalizedId,
                state = "RETRYABLE",
                errorCode = "HOST_ADMISSION_FULL"
            )
            return@withContext DownloadExecutionResult.Retry
        }
        var executionClaimed = false
        var executionReservationToken: Long? = null
        val claimResult = synchronized(executionAdmissionLock) {
            when {
                !executingOperationIds.add(normalizedId) -> resolveConcurrentExecutionResult(
                    systemRetryStopPending = systemRetryStopOperationIds.contains(normalizedId)
                )
                else -> {
                    val existingOwner = hostAdmissionOwners[normalizedId]
                    when {
                        existingOwner == null -> {
                            hostAdmissionOwners[normalizedId] = executionTicket
                            executionReservationToken =
                                transferReservationOwners[normalizedId]?.token
                            executionClaimed = true
                            null
                        }
                        existingOwner == executionTicket -> {
                            executionReservationToken =
                                transferReservationOwners[normalizedId]?.token
                            executionClaimed = true
                            null
                        }
                        else -> {
                            executingOperationIds.remove(normalizedId)
                            DownloadExecutionResult.Cancelled
                        }
                    }
                }
            }
        }
        if (claimResult != null) {
            if (hostAdmissionRequired && hostAdmissionAcquired && !executionClaimed) {
                releaseLostExecutionAdmissionIfUnowned(appContext, normalizedId)
            }
            return@withContext claimResult
        }
        try {
            if (!operationStore.tryStartSuspending(
                    context = appContext,
                    operationId = normalizedId,
                    allowExistingRunning = true,
                    currentNetworkGeneration =
                        AudioDownloadManager.currentDownloadNetworkGeneration()
                )
            ) {
                return@withContext resolveClaimFailureResult(
                    currentState = operationStore.currentStateSuspending(appContext, normalizedId),
                    userStopped = operationStore.isStoppedSuspending(appContext, normalizedId)
                )
            }
            val request = operationStore.readSuspending(appContext, normalizedId)
                ?.takeIf { latest ->
                    latest.operationId == initialRequest.operationId &&
                        latest.song.stableKey() == initialRequest.song.stableKey()
                }
                ?: return@withContext DownloadExecutionResult.MissingOperation
            val reboundTicket = bindPersistedScheduleTicket(
                context = appContext,
                ticket = executionTicket,
                allowAttemptRebind = true
            ) ?: return@withContext rejectStaleExecution(
                context = appContext,
                request = request,
                ticket = executionTicket
            )
            if (
                !isScheduleTicketCurrent(appContext, reboundTicket) ||
                !isPersistedScheduleTicketCurrent(appContext, reboundTicket)
            ) {
                return@withContext rejectStaleExecution(
                    context = appContext,
                    request = request,
                    ticket = reboundTicket
                )
            }
            if (!rebindExecutionOwners(
                    operationId = normalizedId,
                    previous = executionTicket,
                    next = reboundTicket
                )
            ) {
                return@withContext rejectStaleExecution(
                    context = appContext,
                    request = request,
                    ticket = reboundTicket
                )
            }
            executionTicket = reboundTicket
            if (!bindTransferReservationAttempt(normalizedId, executionTicket)) {
                return@withContext rejectStaleExecution(
                    context = appContext,
                    request = request,
                    ticket = executionTicket
                )
            }
            operationTraceToken = DownloadOperationTrace.begin(
                operationId = normalizedId,
                attemptId = executionTicket.attemptId
            ) ?: operationTraceToken
            if (operationStore.isStoppedSuspending(appContext, normalizedId)) {
                return@withContext DownloadExecutionResult.UserStopped
            }
            when (operationStore.currentStateSuspending(appContext, normalizedId)) {
                "CANCEL_REQUESTED",
                "CANCELLED" -> return@withContext DownloadExecutionResult.Cancelled
                "RUNNING",
                "CORE_COMMITTED",
                "ASSETS_ENRICHING",
                "DEGRADED_COMPLETE" -> Unit
                else -> return@withContext DownloadExecutionResult.AlreadyHandled
            }
            if (
                !isScheduleTicketCurrent(appContext, executionTicket) ||
                !isPersistedScheduleTicketCurrent(appContext, executionTicket)
            ) {
                return@withContext rejectStaleExecution(
                    context = appContext,
                    request = request,
                    ticket = executionTicket
                )
            }
            operationIdsBySongKey[request.song.stableKey()] = normalizedId
            DownloadOperationTrace.mark(
                operationTraceToken,
                DownloadOperationTracePhase.BACKEND_STARTED
            )
            val result = entryPoint.start(
                context = context.applicationContext,
                request = request
            )
            var returnedResult = result
            var clearBlockedResult = false
            PersistentDownloadClearFenceStore.withSchedulingPermitSuspending(
                context = appContext,
                onFenceActive = {
                    clearBlockedResult = true
                    try {
                        operationStore.requestCancelSuspending(appContext, normalizedId)
                    } catch (_: Throwable) {
                        // 清空栅栏已经生效，取消标记失败时由下一轮恢复继续收敛
                    }
                },
                stableKey = request.song.stableKey(),
                operationId = normalizedId
            ) {
                when (result) {
                    DownloadExecutionResult.Accepted -> {
                        operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "COMPLETED"
                        )
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                        operationStore.pruneTerminalOperationsSuspending(
                            context = context.applicationContext,
                            cutoffMs = System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MS,
                            limit = TERMINAL_OPERATION_PRUNE_LIMIT
                        )
                    }
                    DownloadExecutionResult.AlreadyHandled -> Unit
                    DownloadExecutionResult.Cancelled -> {
                        operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "CANCELLED",
                            errorCode = "USER_CANCELLED"
                        )
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                        operationStore.pruneTerminalOperationsSuspending(
                            context = context.applicationContext,
                            cutoffMs = System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MS,
                            limit = TERMINAL_OPERATION_PRUNE_LIMIT
                        )
                    }
                    DownloadExecutionResult.UserStopped -> {
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                    }
                    DownloadExecutionResult.UserActionRequired -> {
                        val persisted = operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = METADATA_ACTION_REQUIRED_OPERATION_STATE,
                            errorCode = METADATA_EMBEDDING_UNSUPPORTED_CONTAINER_ERROR
                        )
                        if (!persisted) {
                            returnedResult = DownloadExecutionResult.Retry
                        } else {
                            operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                        }
                    }
                    is DownloadExecutionResult.Failed -> {
                        operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "RETRYABLE",
                            errorCode = result.error.javaClass.simpleName
                        )
                    }
                    DownloadExecutionResult.Retry -> {
                        operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "RETRYABLE"
                        )
                    }
                    DownloadExecutionResult.NetworkPolicyWaiting -> {
                        operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "RETRYABLE",
                            errorCode = "NETWORK_POLICY_WAITING"
                        )
                        val wakeRearmed = !request.requiresWifiNetwork ||
                            WifiBoundDownloadWakeWorker.rearmAfterNetworkPolicyWait(
                                context = context.applicationContext,
                                operationId = normalizedId
                            )
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                        if (!wakeRearmed) {
                            returnedResult = DownloadExecutionResult.Retry
                        }
                    }
                    DownloadExecutionResult.MissingOperation -> {
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                    }
                }
            }
            if (clearBlockedResult) {
                DownloadExecutionResult.Cancelled
            } else {
                returnedResult
            }
        } catch (cancellation: CancellationException) {
            if (PersistentDownloadClearFenceStore.isActive(appContext)) {
                withContext(NonCancellable) {
                    try {
                        operationStore.requestCancelSuspending(appContext, normalizedId)
                    } catch (_: Throwable) {
                        // 清空栅栏已经生效，取消标记失败时由恢复流程补写
                    }
                }
                throw cancellation
            }
            if (systemRetryStopOperationIds.contains(normalizedId)) {
                throw cancellation
            }
            val latestState = operationStore.currentStateSuspending(
                context.applicationContext,
                normalizedId
            )
            resolveExecutionCancellationResult(latestState)?.let { result ->
                return@withContext result
            }
            if (shouldHandleHostStop(latestState)) {
                val explicitlyStopped =
                    explicitSchedulerStopOperationIds.contains(normalizedId) ||
                        operationStore.isStoppedSuspending(
                            context.applicationContext,
                            normalizedId
                        )
                val retryPrepared = if (!explicitlyStopped) {
                    var clearBlockedStop = false
                    val persisted: Boolean = PersistentDownloadClearFenceStore.withSchedulingPermitSuspending(
                        context = appContext,
                        onFenceActive = {
                            clearBlockedStop = true
                            try {
                                operationStore.requestCancelSuspending(appContext, normalizedId)
                            } catch (_: Throwable) {
                                // 清空栅栏已经生效，保留取消语义等待恢复路径重试
                            }
                            false
                        },
                        stableKey = initialRequest.song.stableKey(),
                        operationId = normalizedId
                    ) {
                        operationStore.updateStateSuspending(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "RETRYABLE",
                            errorCode = "HOST_CANCELLED"
                        )
                    }
                    !clearBlockedStop && persisted == true
                } else {
                    false
                }
                GlobalDownloadManager.stopDownloadOperation(
                    context = context.applicationContext,
                    songKey = initialRequest.song.stableKey(),
                    expectedAttemptId = initialRequest.attemptId,
                    rememberForRetry = retryPrepared,
                    operationId = normalizedId,
                    knownOperationState = latestState
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            var clearBlockedFailure = false
            PersistentDownloadClearFenceStore.withSchedulingPermitSuspending(
                context = appContext,
                onFenceActive = {
                    clearBlockedFailure = true
                    try {
                        operationStore.requestCancelSuspending(appContext, normalizedId)
                    } catch (_: Throwable) {
                        // 清空栅栏已经生效，失败记录由恢复流程补写
                    }
                },
                stableKey = initialRequest.song.stableKey(),
                operationId = normalizedId
            ) {
                operationStore.updateStateSuspending(
                    context = context.applicationContext,
                    operationId = normalizedId,
                    state = "RETRYABLE",
                    errorCode = error.javaClass.simpleName
                )
            }
            if (clearBlockedFailure) {
                DownloadExecutionResult.Cancelled
            } else {
                DownloadExecutionResult.Failed(error)
            }
        } finally {
            DownloadOperationTrace.mark(
                operationTraceToken,
                DownloadOperationTracePhase.TERMINAL
            )
            val finishedTicket = executionTicket
            releaseUnclaimedTransferReservation(
                operationId = normalizedId,
                reservationToken = executionReservationToken
            )
            if (executionClaimed) {
                // 先释放传输 owner，再撤销 execution 标记，避免新代次在
                // 两步之间抢到同一 operation 后被旧 owner 拒绝
                releaseTransferSlot(
                    operationId = normalizedId,
                    attemptId = finishedTicket.attemptId,
                    ticket = finishedTicket
                )
                scheduleOwners.remove(normalizedId, finishedTicket)
                removeBackendOwnerIfMatches(normalizedId, finishedTicket)
                synchronized(executionAdmissionLock) {
                    executingOperationIds.remove(normalizedId)
                    systemRetryStopOperationIds.remove(normalizedId)
                    explicitSchedulerStopOperationIds.remove(normalizedId)
                }
                releaseHostAdmissionIfIdleSuspending(
                    context = appContext,
                    operationId = normalizedId,
                    ticket = executionTicket
                )
            }
        }
    }

    override suspend fun pump(
        context: Context
    ): DownloadExecutionPumpResult = pumpMutex.withLock {
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            retryDeadlineWakeCoordinator.onPumpStarted()
            if (ForegroundDownloadWorker.isPumpBlocked(appContext)) {
                return@withContext DownloadExecutionPumpResult.Completed
            }
            DownloadStartupTrace.markQueueReady()
            // 使用滑动窗口而不是批次屏障：某个慢 operation 收尾时，已经完成的
            // operation 立即释放位置并补入下一首，避免并行数在批次尾部降到 0
            supervisorScope {
                var completedOperations = 0
                // 旧实现每轮最多执行 PUMP_MAX_BATCHES_PER_RUN 个完整窗口。
                // 滑动窗口按单个完成计数，保持同等上限，避免大队列被过早切成
                // 多次 WorkManager pump 并重复扫描已读页面
                val maxCompletedOperations = PUMP_MAX_BATCHES_PER_RUN *
                    configuredDispatchWindow(appContext)
                var sawRetry = false
                var waitedForPendingUidtGrace = false
                var queueExhausted = false
                var lastSelection: PumpCandidateSelection? = null
                var nextRetryAtMs: Long? = null
                val deferredTransferOperationIds = mutableSetOf<String>()
                val attemptedOperationIds = mutableSetOf<String>()
                val attemptedStableKeys = mutableSetOf<String>()
                var pumpCursor: DownloadExecutionPumpCursor? = null
                var pumpPendingPage: PumpPendingPage? = null
                var blockedLaneProbeUsed = false
                // Core Commit 可能在 deferred 放入 transferRunning 前完成；
                // 先记住释放身份，插入时直接走 enrichment side channel
                val releasedBeforeTransferRegistration = mutableSetOf<String>()
                val transferRunning = linkedMapOf<String, Deferred<DownloadExecutionResult>>()
                val sideChannelRunning = linkedMapOf<String, Deferred<DownloadExecutionResult>>()

                while (completedOperations < maxCompletedOperations) {
                    if (
                        ForegroundDownloadWorker.isPumpBlocked(appContext) &&
                            transferRunning.isEmpty() && sideChannelRunning.isEmpty()
                    ) {
                        return@supervisorScope DownloadExecutionPumpResult.Completed
                    }

                    // 每次只填满当前剩余容量。collectPumpCandidates 会保留页内
                    // 未选中的请求，因此下一轮不会跳过任何 durable operation
                    while (!queueExhausted) {
                        // 网络 permit 严格限制真实传输数；这里使用带少量预热名额的
                        // 调度窗口，让源解析和文件准备不会挤占用户配置的传输槽位
                        val configuredCapacity = configuredDispatchWindow(appContext)
                        val occupancy = transferLaneOccupancy(appContext)
                        val laneHasCapacity = occupancy < configuredCapacity
                        val mayProbeBlockedLane = !laneHasCapacity &&
                            !blockedLaneProbeUsed &&
                            transferRunning.isEmpty() &&
                            sideChannelRunning.isEmpty()
                        if (!laneHasCapacity && !mayProbeBlockedLane) break
                        if (mayProbeBlockedLane) blockedLaneProbeUsed = true
                        // 槽位已满时仍探测一页，才能把“有 durable 请求但被外部
                        // UIDT 占位”区分为暂缓，而不是错误地收敛成 Completed
                        val capacity = (configuredCapacity - occupancy).coerceAtLeast(1)
                        val selection = collectPumpCandidates(
                            context = appContext,
                            capacity = capacity,
                            attemptedOperationIds = attemptedOperationIds,
                            attemptedStableKeys = attemptedStableKeys,
                            afterCursor = pumpCursor,
                            pendingPage = pumpPendingPage
                        )
                        lastSelection = selection
                        nextRetryAtMs = selection.nextRetryAtMs?.let { deadlineMs ->
                            nextRetryAtMs?.coerceAtMost(deadlineMs) ?: deadlineMs
                        }
                        pumpCursor = selection.nextCursor
                        pumpPendingPage = selection.pendingPage
                        if (selection.requests.isEmpty()) {
                            if (selection.exhausted) queueExhausted = true
                            break
                        }
                        waitedForPendingUidtGrace = false
                        selection.requests.forEach { request ->
                            val reservationToken = reserveTransferSlot(
                                operationId = request.operationId,
                                attemptId = request.attemptId,
                                capacity = configuredCapacity
                            ) ?: run {
                                // 外部 UIDT/Worker 可能在候选扫描后先占满槽位。不要
                                // 把这首标记成已尝试，否则槽位释放后本轮无法补位；
                                // 同时回退到有界 successor，避免空转 WorkManager
                                // worker 持续以毫秒级间隔互相接力
                                deferredTransferOperationIds += request.operationId
                                queueExhausted = false
                                pumpPendingPage = PumpPendingPage(
                                    requests = listOf(request) +
                                        pumpPendingPage?.requests.orEmpty(),
                                    continuationCursor = pumpPendingPage?.continuationCursor
                                )
                                return@forEach
                            }
                            deferredTransferOperationIds.remove(request.operationId)
                            attemptedOperationIds += request.operationId
                            attemptedStableKeys += request.song.stableKey()
                            val execution = async(Dispatchers.IO) {
                                try {
                                    executePumpCandidateIsolated(request.operationId) {
                                        execute(appContext, request.operationId)
                                    }
                                } finally {
                                    releaseTransferReservation(
                                        operationId = request.operationId,
                                        reservationToken = reservationToken
                                    )
                                }
                            }
                            if (releasedBeforeTransferRegistration.remove(request.operationId)) {
                                sideChannelRunning[request.operationId] = execution
                            } else {
                                transferRunning[request.operationId] = execution
                            }
                        }
                        if (selection.exhausted && deferredTransferOperationIds.isEmpty()) {
                            queueExhausted = true
                        }
                        if (
                            deferredTransferOperationIds.isNotEmpty() &&
                                transferRunning.isEmpty() &&
                                sideChannelRunning.isEmpty()
                        ) {
                            break
                        }
                    }

                    if (transferRunning.isNotEmpty() || sideChannelRunning.isNotEmpty()) {
                        // 任一 operation 完成就继续填充窗口，不等待同一轮其它慢任务
                        val completed = select<Any> {
                            transferRunning.forEach { (operationId, execution) ->
                                execution.onAwait { result ->
                                    PumpExecutionCompletion(
                                        operationId = operationId,
                                        execution = execution,
                                        result = result
                                    )
                                }
                            }
                            sideChannelRunning.forEach { (operationId, execution) ->
                                execution.onAwait { result ->
                                    PumpExecutionCompletion(
                                        operationId = operationId,
                                        execution = execution,
                                        result = result
                                    )
                                }
                            }
                            transferReleaseSignals.onReceive { operationId ->
                                PumpTransferRelease(
                                    drainTransferReleaseOperationIds(operationId)
                                )
                            }
                        }
                        when (completed) {
                            is PumpTransferRelease -> {
                                // Core Commit 释放 transfer lane 后，把 execute deferred
                                // 移到 enrichment side channel，不再把它当作传输中的任务
                                completed.operationIds.forEach { operationId ->
                                    transferRunning.remove(operationId)?.let { execution ->
                                        sideChannelRunning[operationId] = execution
                                    } ?: releasedBeforeTransferRegistration.add(operationId)
                                }
                                continue
                            }

                            is PumpExecutionCompletion -> {
                                val removed = when {
                                    transferRunning[completed.operationId] === completed.execution -> {
                                        transferRunning.remove(completed.operationId)
                                    }
                                    sideChannelRunning[completed.operationId] === completed.execution -> {
                                        sideChannelRunning.remove(completed.operationId)
                                    }
                                    else -> null
                                }
                                if (removed == null) continue
                                completedOperations++
                                sawRetry = sawRetry || requiresPumpRetry(completed.result)
                            }
                        }
                        if (transferRunning.isNotEmpty() || sideChannelRunning.isNotEmpty()) continue

                        val selection = lastSelection
                        val graceDelayMs = selection?.shortestPendingUidtGraceDelayMs
                        if (queueExhausted && graceDelayMs != null && !waitedForPendingUidtGrace) {
                            waitedForPendingUidtGrace = true
                            // UIDT 延后项可能位于当前游标之前，等待后从队首重读
                            pumpCursor = null
                            pumpPendingPage = null
                            queueExhausted = false
                            delay(graceDelayMs)
                            continue
                        }
                        if (queueExhausted) {
                            val retryWakeResult = nextRetryAtMs?.let { deadlineMs ->
                                retryDeadlineWakeCoordinator.schedule(appContext, deadlineMs)
                            }
                            if (
                                retryWakeResult == DownloadRetryDeadlineWakeCoordinator.ScheduleResult.FAILED
                            ) {
                                return@supervisorScope DownloadExecutionPumpResult.Retry
                            }
                            if (deferredTransferOperationIds.isNotEmpty()) {
                                // 这是槽位竞争而不是网络/传输失败，使用短唤醒让释放后的
                                // 补位不必等待常规重试窗口
                                return@supervisorScope DownloadExecutionPumpResult.ContinueAfterContention
                            }
                            return@supervisorScope if (sawRetry) {
                                DownloadExecutionPumpResult.ContinueAfterRetry
                            } else if (selection?.hasSchedulableRequest == true &&
                                selection.requests.isEmpty()
                            ) {
                                // durable 行仍在队列中，但本轮已尝试过或正在 UIDT
                                // grace 中，短唤醒即可，不能触发系统长 backoff
                                if (graceDelayMs != null) {
                                    DownloadExecutionPumpResult.ContinueAfterContention
                                } else {
                                    DownloadExecutionPumpResult.ContinueSoon
                                }
                            } else {
                                DownloadExecutionPumpResult.Completed
                            }
                        }
                    } else {
                        val selection = lastSelection
                        val graceDelayMs = selection?.shortestPendingUidtGraceDelayMs
                        if (graceDelayMs != null && !waitedForPendingUidtGrace) {
                            waitedForPendingUidtGrace = true
                            if (selection.exhausted) {
                                pumpCursor = null
                                pumpPendingPage = null
                                queueExhausted = false
                            }
                            delay(graceDelayMs)
                            continue
                        }
                        if (selection?.hasSchedulableRequest != true) {
                            val retryWakeResult = nextRetryAtMs?.let { deadlineMs ->
                                retryDeadlineWakeCoordinator.schedule(appContext, deadlineMs)
                            }
                            if (
                                retryWakeResult == DownloadRetryDeadlineWakeCoordinator.ScheduleResult.FAILED
                            ) {
                                return@supervisorScope DownloadExecutionPumpResult.Retry
                            }
                            if (deferredTransferOperationIds.isNotEmpty()) {
                                return@supervisorScope DownloadExecutionPumpResult.ContinueAfterContention
                            }
                            return@supervisorScope if (sawRetry) {
                                DownloadExecutionPumpResult.ContinueAfterRetry
                            } else {
                                DownloadExecutionPumpResult.Completed
                            }
                        }
                        return@supervisorScope if (deferredTransferOperationIds.isNotEmpty()) {
                            DownloadExecutionPumpResult.ContinueAfterContention
                        } else if (sawRetry) {
                            DownloadExecutionPumpResult.ContinueAfterRetry
                        } else {
                            if (graceDelayMs != null) {
                                DownloadExecutionPumpResult.ContinueAfterContention
                            } else {
                                DownloadExecutionPumpResult.ContinueSoon
                            }
                        }
                    }
                }
                DownloadExecutionPumpResult.ContinueSoon
            }
        }
    }

    private suspend fun collectPumpCandidates(
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
        var shortestPendingUidtGraceDelayMs: Long? = null
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
                    // 即使同曲目的 replacement 已经尝试过，UIDT grace 仍需
                    // 被记录，否则旧 predecessor 会让泵错误地提前收口
                    hasSchedulableRequest = true
                    rowsDeferredUidt++
                    shortestPendingUidtGraceDelayMs =
                        shortestPendingUidtGraceDelayMs?.coerceAtMost(graceDelayMs) ?: graceDelayMs
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
            shortestPendingUidtGraceDelayMs = shortestPendingUidtGraceDelayMs,
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

    private fun pendingUidtGraceDelayMs(
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

    private fun tryAcquireHostAdmission(
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

    private suspend fun tryAcquireHostAdmissionSuspending(
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

    private fun configuredDownloadParallelism(context: Context): Int {
        return downloadParallelismProvider(context).coerceIn(1, MAX_DOWNLOAD_PARALLELISM)
    }

    private fun configuredDispatchWindow(context: Context): Int {
        return resolveDownloadDispatchWindow(configuredDownloadParallelism(context))
    }

    private fun enqueueDeferredSchedule(
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

    private fun triggerDeferredSchedules(context: Context) {
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

    private fun deferredRetryLimit(): Int {
        return withDeferredSchedulingLock {
            deferredRequests.size()
                .coerceAtLeast(1)
                .coerceAtMost(MAX_DEFERRED_SCHEDULES_PER_PASS)
        }
    }

    private fun <T> withDeferredSchedulingLock(action: () -> T): T {
        return synchronized(deferredSchedulingLock, action)
    }

    internal fun releaseHandoffAdmissionIfIdle(
        context: Context,
        operationId: String
    ) {
        releaseHostAdmissionIfIdle(context.applicationContext, operationId)
    }

    /** 并发 claim 失败时，仅回收本次孤立准入，不碰仍有 owner 的执行 */
    private suspend fun releaseLostExecutionAdmissionIfUnowned(
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

    private fun releaseHostAdmissionIfIdle(
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

    private suspend fun releaseHostAdmissionIfIdleSuspending(
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

    private fun hasPendingTransferReleaseLocked(operationId: String): Boolean {
        val owner = activeTransferOwners[operationId] ?: return false
        return transferReleasePendingTokens.contains(owner.token)
    }
}

internal fun shouldHandleHostStop(operationState: String?): Boolean {
    return operationState in setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE", "STOPPED") ||
        operationState in INTERRUPTED_DOWNLOAD_OPERATION_STATES
}

internal fun resolveExecutionCancellationResult(
    operationState: String?
): DownloadExecutionResult? {
    return if (operationState == WAITING_STORAGE_MUTATION_OPERATION_STATE) {
        // 空间不足或目录迁移使用 CancellationException 退出传输，但它不是用户取消
        // 不能让共享泵把同批仍可运行的 operation 一起取消
        DownloadExecutionResult.AlreadyHandled
    } else {
        null
    }
}

internal fun requiresPumpRetry(result: DownloadExecutionResult): Boolean {
    return result == DownloadExecutionResult.Retry || result is DownloadExecutionResult.Failed
}

/** 宿主被系统回收后重新排入同一个持久 operation */
internal fun canScheduleDownloadOperation(currentState: String?): Boolean {
    return currentState == null ||
        currentState in setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE") ||
        currentState in INTERRUPTED_DOWNLOAD_OPERATION_STATES
}

/** core 音频已持久提交后只占用资产收尾并发，不再挤占传输宿主窗口 */
internal fun requiresTransferHostAdmission(currentState: String?): Boolean {
    return currentState !in setOf(
        "CORE_COMMITTED",
        "ASSETS_ENRICHING",
        "DEGRADED_COMPLETE"
    )
}

internal fun shouldBlockExistingDownloadOperation(
    existingOperationId: String?,
    requestedOperationId: String,
    existingState: String?,
    existingReadable: Boolean,
    cancellationRequested: Boolean
): Boolean {
    return existingOperationId != null &&
        existingOperationId != requestedOperationId &&
        existingState in setOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE",
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE",
            WAITING_STORAGE_MUTATION_OPERATION_STATE
        ) &&
        existingReadable &&
        !cancellationRequested
}

internal fun shouldBlockHostReschedule(
    preventReschedule: Boolean,
    alreadyStoppedByUser: Boolean
): Boolean = preventReschedule || alreadyStoppedByUser

internal fun resolveConcurrentExecutionResult(
    systemRetryStopPending: Boolean
): DownloadExecutionResult {
    return if (systemRetryStopPending) {
        DownloadExecutionResult.Retry
    } else {
        DownloadExecutionResult.AlreadyHandled
    }
}

internal fun resolvePreExecutionResult(
    currentState: String?
): DownloadExecutionResult? {
    return when (currentState) {
        null,
        "INVALID" -> DownloadExecutionResult.MissingOperation

        "CANCEL_REQUESTED",
        "CANCELLED" -> DownloadExecutionResult.Cancelled

        METADATA_ACTION_REQUIRED_OPERATION_STATE ->
            DownloadExecutionResult.UserActionRequired

        WAITING_STORAGE_MUTATION_OPERATION_STATE,
        "FINALIZED",
        "COMPLETED" -> DownloadExecutionResult.AlreadyHandled

        else -> null
    }
}

/** 争抢失败时保留仍可调度的 operation，等待赢家收尾后再次接管 */
internal fun resolveClaimFailureResult(
    currentState: String?,
    userStopped: Boolean
): DownloadExecutionResult {
    if (userStopped || currentState == "STOPPED") {
        return DownloadExecutionResult.UserStopped
    }
    resolvePreExecutionResult(currentState)?.let { return it }
    return when (currentState) {
        "PENDING_QUEUE",
        "QUEUED",
        "RETRYABLE" -> DownloadExecutionResult.Retry

        else -> DownloadExecutionResult.AlreadyHandled
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun hasPendingUidtJob(context: Context, operationId: String): Boolean {
    return UidtDownloadJobService.hasPendingJob(context, operationId)
}

@RequiresApi(Build.VERSION_CODES.R)
private fun latestProcessExit(
    activityManager: ActivityManager,
    packageName: String
): ApplicationExitInfo? {
    return runCatching {
        activityManager.getHistoricalProcessExitReasons(packageName, 0, 5)
            .firstOrNull()
    }.getOrNull()
}

@RequiresApi(Build.VERSION_CODES.R)
internal fun isUserRequestedProcessExitReason(reason: Int): Boolean {
    return reason == ApplicationExitInfo.REASON_USER_STOPPED
}

private const val PROCESS_EXIT_PREFERENCES = "download_execution_host"
private const val PROCESS_EXIT_TIMESTAMP_KEY = "last_user_requested_exit_timestamp"

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun scheduleUidt(
    context: Context,
    operationId: String,
    pendingJobLimit: Int
): Boolean {
    return UidtDownloadJobService.schedule(
        context = context,
        operationId = operationId,
        pendingJobLimit = pendingJobLimit
    )
}

internal fun selectDownloadExecutionBackend(
    sdkInt: Int,
    userInitiated: Boolean
): DownloadExecutionSchedule.Backend {
    return if (
        userInitiated &&
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        DownloadExecutionSchedule.Backend.UIDT_JOB
    } else {
        DownloadExecutionSchedule.Backend.FOREGROUND_WORK
    }
}

private fun scheduleUidtIfSupported(
    context: Context,
    operationId: String,
    sdkInt: Int,
    pendingJobLimit: Int
): Boolean {
    if (
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        return false
    }
    return scheduleUidt(context, operationId, pendingJobLimit)
}

private const val TERMINAL_OPERATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
private const val TERMINAL_OPERATION_PRUNE_LIMIT = 64
private const val HOST_ADMISSION_RETRY_DELAY_MS = 200L
private const val MAX_DEFERRED_SCHEDULES_PER_PASS = 32

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun cancelUidt(
    context: Context,
    operationId: String
) {
    UidtDownloadJobService.cancel(context, operationId)
}
