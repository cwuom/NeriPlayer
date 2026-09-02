package moe.ouom.neriplayer.core.download.execution

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.currentDownloadParallelism
import moe.ouom.neriplayer.core.player.download.resolveDownloadDispatchWindow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 管理用户下载的持久调度和 operation 身份 */
interface DownloadExecutionHost {
    fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule

    fun cancel(
        context: Context,
        operationId: String
    )

    fun cancelForSong(
        context: Context,
        songKey: String
    )

    fun cancelAll(
        context: Context,
        operationIds: Collection<String>
    )

    fun stopForSong(
        context: Context,
        songKey: String,
        preventReschedule: Boolean = false
    )

    fun stop(
        context: Context,
        operationId: String,
        preventReschedule: Boolean = true
    )

    fun externallyStoppedSongKeys(
        context: Context
    ): Set<String>

    fun requiresExplicitResume(
        context: Context,
        operationId: String?
    ): Boolean

    fun operationIdForSong(
        context: Context,
        songKey: String
    ): String?

    fun markUserRequestedProcessExitOperations(
        context: Context
    ): Set<String>

    fun isExecuting(operationId: String): Boolean = false

    suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult

    /** 从持久 operation 表接管一小批任务，供唯一 WorkManager 泵使用 */
    suspend fun pump(
        context: Context
    ): DownloadExecutionPumpResult = DownloadExecutionPumpResult.Completed
}

data class DownloadExecutionRequest(
    val operationId: String,
    val song: SongItem,
    val preserveStaging: Boolean = false,
    val requiresWifiNetwork: Boolean = true,
    val attemptId: Long? = null,
    val artifactLeaseId: String = UUID.randomUUID().toString(),
    val userInitiated: Boolean = true,
    val downloadAudioQuality: DownloadAudioQualitySelection? = null
) {
    init {
        require(normalizeDownloadOperationId(operationId) == operationId) {
            "operationId must be a safe, non-empty identifier"
        }
        require(artifactLeaseId.isNotBlank()) {
            "artifactLeaseId must be non-empty"
        }
    }
}

sealed interface DownloadExecutionSchedule {
    data class Scheduled(val backend: Backend) : DownloadExecutionSchedule

    /** operation 保持持久状态，取得有限宿主槽位后再重试 */
    data class Deferred(val reason: String) : DownloadExecutionSchedule

    data class Rejected(
        val reason: String,
        val retryable: Boolean = false
    ) : DownloadExecutionSchedule

    enum class Backend {
        UIDT_JOB,
        FOREGROUND_WORK
    }
}

sealed interface DownloadExecutionResult {
    data object Accepted : DownloadExecutionResult
    data object AlreadyHandled : DownloadExecutionResult
    data object MissingOperation : DownloadExecutionResult
    data object Retry : DownloadExecutionResult
    data object NetworkPolicyWaiting : DownloadExecutionResult
    data object Cancelled : DownloadExecutionResult
    data object UserStopped : DownloadExecutionResult
    data object UserActionRequired : DownloadExecutionResult
    data class Failed(val error: Throwable) : DownloadExecutionResult
}

enum class DownloadExecutionPumpResult {
    Completed,
    Retry
}

fun interface DownloadOperationEntryPoint {
    suspend fun start(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionResult
}

private object ExistingDownloadOperationEntryPoint : DownloadOperationEntryPoint {
    override suspend fun start(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionResult {
        return GlobalDownloadManager.startDownload(
            context = context,
            song = request.song,
            operationId = request.operationId,
            preserveStaging = request.preserveStaging,
            preparedAttemptId = request.attemptId
        )
    }
}

class DefaultDownloadExecutionHost(
    private val operationStore: DownloadExecutionOperationStore =
        DownloadExecutionOperationStore(),
    private val entryPoint: DownloadOperationEntryPoint =
        ExistingDownloadOperationEntryPoint,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val downloadParallelismProvider: (Context) -> Int =
        ::currentDownloadParallelism,
    private val pendingUidtGraceDelayProvider: ((Context, DownloadExecutionRequest) -> Long)? = null
) : DownloadExecutionHost {
    private val operationIdsBySongKey = ConcurrentHashMap<String, String>()
    private val executingOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val systemRetryStopOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val explicitSchedulerStopOperationIds = ConcurrentHashMap.newKeySet<String>()
    private val executionAdmissionLock = Any()
    private val backendOwnershipLock = Any()
    private val hostAdmissionOwners = ConcurrentHashMap<String, ScheduleTicket>()
    private val backendOwners = ConcurrentHashMap<String, BackendOwner>()
    private val scheduleOwners = ConcurrentHashMap<String, ScheduleTicket>()
    private val deferredRequests = DeferredDownloadScheduleQueue()
    private val deferredSchedulingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deferredSchedulingRunning = AtomicBoolean(false)

    private data class PumpCandidateSelection(
        val requests: List<DownloadExecutionRequest>,
        val hasSchedulableRequest: Boolean,
        val shortestPendingUidtGraceDelayMs: Long?
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

    override fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule {
        val appContext = context.applicationContext
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
            if (existingOperationId != null &&
                existingOperationId != request.operationId &&
                existingState in BLOCKING_SCHEDULING_STATES &&
                existingReadable
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
            // save 可能跨越清空代次，先把持久 attempt 绑定到本次 ticket
            val boundTicket = bindPersistedScheduleTicket(context, ticket)
                ?: return rejectStaleSchedule(
                    context = context,
                    request = request,
                    hostAdmissionAcquired = hostAdmissionAcquired,
                    ticket = ticket
                )
            currentTicket = boundTicket
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
            if (!tryAcquireHostAdmission(
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
            hostAdmissionAcquired = true
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
            deferredRequests.remove(request)
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
            if (hostAdmissionAcquired) {
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
    ): DownloadExecutionSchedule.Rejected {
        if (hostAdmissionAcquired) {
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
        deferredRequests.remove(request)
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
        private const val PUMP_QUERY_LIMIT = 64
        private const val PUMP_MAX_BATCHES_PER_RUN = 256
        private val SCHEDULABLE_OPERATION_STATES = setOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE"
        )
        private val ACTIVE_SCHEDULING_STATES = SCHEDULABLE_OPERATION_STATES +
            INTERRUPTED_DOWNLOAD_OPERATION_STATES
        private val BLOCKING_SCHEDULING_STATES = ACTIVE_SCHEDULING_STATES +
            WAITING_STORAGE_MUTATION_OPERATION_STATE
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
        deferredRequests.remove(normalizedId)
        WifiBoundDownloadWakeWorker.cancel(appContext, normalizedId)
        val request = operationStore.read(appContext, normalizedId)
        val cancelAccepted = request != null &&
            operationStore.requestCancel(appContext, normalizedId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(appContext, normalizedId)
        }
        ForegroundDownloadWorker.cancel(appContext, normalizedId)
        if (cancelAccepted) {
            request.song.stableKey().let(GlobalDownloadManager::cancelDownloadOperationFromHost)
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
            normalizedIds.filterNot(executingOperationIds::contains).also { idleIds ->
                idleIds.forEach(hostAdmissionOwners::remove)
            }
        }
        operationIdsBySongKey.entries.removeIf { entry -> entry.value in normalizedIds }
        deferredRequests.removeAll(normalizedIds)
        if (idleOperationIds.isNotEmpty()) {
            operationStore.releaseHostAdmissions(appContext, idleOperationIds)
            triggerDeferredSchedules(appContext)
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
            addAll(deferredRequests.operationIds())
            synchronized(executionAdmissionLock) {
                addAll(hostAdmissionOwners.keys)
            }
        }
        val idleOperationIds = synchronized(executionAdmissionLock) {
            ownedOperationIds.filterNot(executingOperationIds::contains).also { idleIds ->
                idleIds.forEach(hostAdmissionOwners::remove)
            }
        }
        operationStore.releaseHostAdmissions(appContext, idleOperationIds)
        scheduleOwners.clear()
        synchronized(backendOwnershipLock) {
            backendOwners.clear()
        }
        operationIdsBySongKey.clear()
        deferredRequests.clear()
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
                rememberForRetry = retryPrepared
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
                DownloadExecutionRoomStore.listByStatesAnyLibrary(context, activeStates)
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
        val stoppedKeys = try {
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
        val expectedUserInitiatedCount = entries.count { it.request.userInitiated }
        if (stoppedKeys.size < expectedUserInitiatedCount) {
            moe.ouom.neriplayer.core.logging.NPLogger.w(
                "DownloadExecutionHost",
                "部分下载 operation 未写入用户停止标记，保留退出时间戳待下次重试"
            )
            return emptySet()
        }
        preferences.edit {
            putLong(PROCESS_EXIT_TIMESTAMP_KEY, latestExit.timestamp)
        }
        return stoppedKeys
    }

    override suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult = withContext(Dispatchers.IO) {
        val normalizedId = normalizeDownloadOperationId(operationId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        val appContext = context.applicationContext
        val initialRequest = operationStore.read(appContext, normalizedId)
            ?: run {
                operationStore.updateState(
                    context = appContext,
                    operationId = normalizedId,
                    state = "INVALID",
                    errorCode = "INVALID_OPERATION_PAYLOAD"
                )
                moe.ouom.neriplayer.core.logging.NPLogger.w(
                    "NERI-DownloadHost",
                    "下载 operation 读取失败: operationId=$normalizedId, reason=missing_or_unreadable"
                )
                releaseHostAdmissionIfIdle(appContext, normalizedId)
                return@withContext DownloadExecutionResult.MissingOperation
            }
        if (
            PersistentDownloadClearFenceStore.isBlocked(
                context = appContext,
                stableKey = initialRequest.song.stableKey(),
                operationId = normalizedId
            )
        ) {
            runCatching {
                operationStore.requestCancel(appContext, normalizedId)
            }
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withContext DownloadExecutionResult.Cancelled
        }
        val initialTicket = captureScheduleTicket(appContext, initialRequest)
            ?: run {
                releaseHostAdmissionIfIdle(appContext, normalizedId)
                return@withContext DownloadExecutionResult.Cancelled
            }
        var executionTicket = bindPersistedScheduleTicket(
            context = appContext,
            ticket = initialTicket
        ) ?: run {
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withContext DownloadExecutionResult.Retry
        }
        if (operationStore.isStopped(appContext, normalizedId)) {
            releaseHostAdmissionIfIdle(appContext, normalizedId)
            return@withContext DownloadExecutionResult.UserStopped
        }
        resolvePreExecutionResult(
            operationStore.currentState(appContext, normalizedId)
        )?.let { result ->
            releaseHostAdmissionIfIdle(appContext, normalizedId)
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
        // Room 访问必须发生在短内存临界区之外，避免清空或进度回调被
        // 一个挂起的数据库操作长期阻塞
        val hostAdmissionAcquired = tryAcquireHostAdmission(appContext, normalizedId)
        val stateBeforeClaim = operationStore.currentState(appContext, normalizedId)
        val claimResult = synchronized(executionAdmissionLock) {
            when {
                !hostAdmissionAcquired -> resolvePreExecutionResult(stateBeforeClaim)
                    ?: DownloadExecutionResult.Retry
                !executingOperationIds.add(normalizedId) -> resolveConcurrentExecutionResult(
                    systemRetryStopPending = systemRetryStopOperationIds.contains(normalizedId)
                )
                else -> {
                    val existingOwner = hostAdmissionOwners[normalizedId]
                    when {
                        existingOwner == null -> {
                            hostAdmissionOwners[normalizedId] = executionTicket
                            null
                        }
                        existingOwner == executionTicket -> null
                        else -> {
                            executingOperationIds.remove(normalizedId)
                            DownloadExecutionResult.Cancelled
                        }
                    }
                }
            }
        }
        if (claimResult != null) {
            return@withContext claimResult
        }
        try {
            if (!operationStore.tryStart(
                    context = appContext,
                    operationId = normalizedId,
                    allowExistingRunning = true
                )
            ) {
                return@withContext DownloadExecutionResult.AlreadyHandled
            }
            val request = operationStore.read(appContext, normalizedId)
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
            if (operationStore.isStopped(appContext, normalizedId)) {
                return@withContext DownloadExecutionResult.UserStopped
            }
            when (operationStore.currentState(appContext, normalizedId)) {
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
            val result = entryPoint.start(
                context = context.applicationContext,
                request = request
            )
            var returnedResult = result
            var clearBlockedResult = false
            PersistentDownloadClearFenceStore.withSchedulingPermit(
                context = appContext,
                onFenceActive = {
                    clearBlockedResult = true
                    runCatching {
                        operationStore.requestCancel(appContext, normalizedId)
                    }
                },
                stableKey = request.song.stableKey(),
                operationId = normalizedId
            ) {
                when (result) {
                    DownloadExecutionResult.Accepted -> {
                        operationStore.updateState(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "COMPLETED"
                        )
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                        operationStore.pruneTerminalOperations(
                            context = context.applicationContext,
                            cutoffMs = System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MS,
                            limit = TERMINAL_OPERATION_PRUNE_LIMIT
                        )
                    }
                    DownloadExecutionResult.AlreadyHandled -> Unit
                    DownloadExecutionResult.Cancelled -> {
                        operationStore.updateState(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "CANCELLED",
                            errorCode = "USER_CANCELLED"
                        )
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                        operationStore.pruneTerminalOperations(
                            context = context.applicationContext,
                            cutoffMs = System.currentTimeMillis() - TERMINAL_OPERATION_RETENTION_MS,
                            limit = TERMINAL_OPERATION_PRUNE_LIMIT
                        )
                    }
                    DownloadExecutionResult.UserStopped -> {
                        operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
                    }
                    DownloadExecutionResult.UserActionRequired -> {
                        val persisted = operationStore.updateState(
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
                        operationStore.updateState(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "RETRYABLE",
                            errorCode = result.error.javaClass.simpleName
                        )
                    }
                    DownloadExecutionResult.Retry -> {
                        operationStore.updateState(
                            context = context.applicationContext,
                            operationId = normalizedId,
                            state = "RETRYABLE"
                        )
                    }
                    DownloadExecutionResult.NetworkPolicyWaiting -> {
                        operationStore.updateState(
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
                    DownloadExecutionResult.MissingOperation -> Unit
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
                    runCatching {
                        operationStore.requestCancel(appContext, normalizedId)
                    }
                }
                throw cancellation
            }
            if (systemRetryStopOperationIds.contains(normalizedId)) {
                throw cancellation
            }
            val latestState = operationStore.currentState(
                context.applicationContext,
                normalizedId
            )
            if (shouldHandleHostStop(latestState)) {
                val explicitlyStopped =
                    explicitSchedulerStopOperationIds.contains(normalizedId) ||
                        operationStore.isStopped(
                            context.applicationContext,
                            normalizedId
                        )
                val retryPrepared = if (!explicitlyStopped) {
                    var clearBlockedStop = false
                    val persisted: Boolean = PersistentDownloadClearFenceStore.withSchedulingPermit(
                        context = appContext,
                        onFenceActive = {
                            clearBlockedStop = true
                            runCatching {
                                operationStore.requestCancel(appContext, normalizedId)
                            }
                            false
                        },
                        stableKey = initialRequest.song.stableKey(),
                        operationId = normalizedId
                    ) {
                        operationStore.updateState(
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
                    rememberForRetry = retryPrepared
                )
            }
            throw cancellation
        } catch (error: Throwable) {
            var clearBlockedFailure = false
            PersistentDownloadClearFenceStore.withSchedulingPermit(
                context = appContext,
                onFenceActive = {
                    clearBlockedFailure = true
                    runCatching {
                        operationStore.requestCancel(appContext, normalizedId)
                    }
                },
                stableKey = initialRequest.song.stableKey(),
                operationId = normalizedId
            ) {
                operationStore.updateState(
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
            val finishedTicket = executionTicket
            scheduleOwners.remove(normalizedId, finishedTicket)
            removeBackendOwnerIfMatches(normalizedId, finishedTicket)
            synchronized(executionAdmissionLock) {
                executingOperationIds.remove(normalizedId)
                systemRetryStopOperationIds.remove(normalizedId)
                explicitSchedulerStopOperationIds.remove(normalizedId)
            }
            releaseHostAdmissionIfIdle(
                context = appContext,
                operationId = normalizedId,
                ticket = executionTicket
            )
        }
    }

    override suspend fun pump(
        context: Context
    ): DownloadExecutionPumpResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        var completedBatches = 0
        var sawRetry = false
        var waitedForPendingUidtGrace = false
        val attemptedOperationIds = mutableSetOf<String>()
        while (completedBatches < PUMP_MAX_BATCHES_PER_RUN) {
            val dispatchWindow = configuredDispatchWindow(appContext)
            val selection = collectPumpCandidates(
                context = appContext,
                capacity = dispatchWindow,
                attemptedOperationIds = attemptedOperationIds
            )
            val candidates = selection.requests
            if (candidates.isEmpty() && !selection.hasSchedulableRequest) {
                return@withContext if (sawRetry) {
                    DownloadExecutionPumpResult.Retry
                } else {
                    DownloadExecutionPumpResult.Completed
                }
            }
            if (candidates.isEmpty()) {
                val graceDelayMs = selection.shortestPendingUidtGraceDelayMs
                if (graceDelayMs != null && !waitedForPendingUidtGrace) {
                    waitedForPendingUidtGrace = true
                    delay(graceDelayMs)
                    continue
                }
                // 已尝试的 operation 或仍在 UIDT 队列的 operation 需要下一轮重新评估
                return@withContext DownloadExecutionPumpResult.Retry
            }
            waitedForPendingUidtGrace = false
            attemptedOperationIds += candidates.map(DownloadExecutionRequest::operationId)
            val results = coroutineScope {
                candidates.map { request ->
                    async(Dispatchers.IO) {
                        execute(appContext, request.operationId)
                    }
                }.awaitAll()
            }
            completedBatches++
            sawRetry = sawRetry || results.any(::requiresPumpRetry)
        }
        DownloadExecutionPumpResult.Retry
    }

    private fun collectPumpCandidates(
        context: Context,
        capacity: Int,
        attemptedOperationIds: Set<String>
    ): PumpCandidateSelection {
        val candidates = mutableListOf<DownloadExecutionRequest>()
        val observedOperationIds = mutableSetOf<String>()
        var hasSchedulableRequest = false
        var shortestPendingUidtGraceDelayMs: Long? = null
        var cursor: DownloadExecutionPumpCursor? = null
        while (candidates.size < capacity) {
            val page = operationStore.listSchedulableForPumpPage(
                context = context,
                afterCursor = cursor,
                limit = PUMP_QUERY_LIMIT
            )
            page.requests.forEach { request ->
                hasSchedulableRequest = true
                if (
                    request.operationId in attemptedOperationIds ||
                        !observedOperationIds.add(request.operationId)
                ) {
                    return@forEach
                }
                val graceDelayMs = pendingUidtGraceDelayMs(context, request)
                if (graceDelayMs > 0L) {
                    shortestPendingUidtGraceDelayMs =
                        shortestPendingUidtGraceDelayMs?.coerceAtMost(graceDelayMs) ?: graceDelayMs
                } else if (candidates.size < capacity) {
                    candidates += request
                }
            }
            if (candidates.size >= capacity) {
                break
            }
            val nextCursor = page.nextCursor ?: break
            if (nextCursor == cursor) {
                break
            }
            cursor = nextCursor
        }
        return PumpCandidateSelection(
            requests = candidates,
            hasSchedulableRequest = hasSchedulableRequest,
            shortestPendingUidtGraceDelayMs = shortestPendingUidtGraceDelayMs
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
            deferredRequests.remove(request)
            return false
        }
        deferredRequests.enqueue(request)
        if (ticket != null && !isScheduleTicketCurrent(context, ticket)) {
            deferredRequests.remove(request)
            return false
        }
        triggerDeferredSchedules(context.applicationContext)
        return true
    }

    private fun triggerDeferredSchedules(context: Context) {
        if (!deferredSchedulingRunning.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        deferredSchedulingScope.launch {
            var deferredRetryCount = 0
            try {
                while (true) {
                    val request = deferredRequests.poll()
                    if (request == null) {
                        if (deferredRequests.isEmpty()) {
                            return@launch
                        }
                        delay(HOST_ADMISSION_RETRY_DELAY_MS)
                        continue
                    }
                    when (val result = schedule(appContext, request)) {
                        is DownloadExecutionSchedule.Scheduled -> {
                            deferredRequests.remove(request)
                            deferredRetryCount = 0
                        }

                        is DownloadExecutionSchedule.Deferred -> {
                            deferredRequests.requeue(request)
                            deferredRetryCount++
                        }

                        is DownloadExecutionSchedule.Rejected -> {
                            if (result.retryable) {
                                deferredRequests.requeue(request)
                                deferredRetryCount++
                            } else {
                                deferredRequests.remove(request)
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
                deferredSchedulingRunning.set(false)
                if (!deferredRequests.isEmpty()) {
                    triggerDeferredSchedules(appContext)
                }
            }
        }
    }

    private fun deferredRetryLimit(): Int {
        return deferredRequests.size()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_DEFERRED_SCHEDULES_PER_PASS)
    }

    internal fun releaseHandoffAdmissionIfIdle(
        context: Context,
        operationId: String
    ) {
        releaseHostAdmissionIfIdle(context.applicationContext, operationId)
    }

    private fun releaseHostAdmissionIfIdle(
        context: Context,
        operationId: String,
        ticket: ScheduleTicket? = null
    ) {
        val released = synchronized(executionAdmissionLock) {
            if (executingOperationIds.contains(operationId)) {
                false
            } else if (ticket != null) {
                hostAdmissionOwners.remove(operationId, ticket)
            } else {
                hostAdmissionOwners.remove(operationId)
                true
            }
        }
        if (released) {
            runCatching {
                operationStore.releaseHostAdmission(context, operationId)
            }
            triggerDeferredSchedules(context.applicationContext)
        }
    }
}

internal fun shouldHandleHostStop(operationState: String?): Boolean {
    return operationState in setOf("PENDING_QUEUE", "QUEUED", "RETRYABLE", "STOPPED") ||
        operationState in INTERRUPTED_DOWNLOAD_OPERATION_STATES
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

object DownloadExecutionHosts {
    val default: DownloadExecutionHost = DefaultDownloadExecutionHost()

    internal suspend fun pump(context: Context): DownloadExecutionPumpResult {
        return default.pump(context)
    }

    fun cancelAllOwned(context: Context) {
        (default as? DefaultDownloadExecutionHost)?.cancelAllOwned(context)
    }

    internal fun releaseHandoffAdmissionIfIdle(
        context: Context,
        operationId: String
    ) {
        (default as? DefaultDownloadExecutionHost)?.releaseHandoffAdmissionIfIdle(
            context = context,
            operationId = operationId
        )
    }

    internal fun stopForSystemRetry(
        context: Context,
        operationId: String
    ) {
        val host = default
        if (host is DefaultDownloadExecutionHost) {
            host.stopForSystemRetry(context, operationId)
        } else {
            host.stop(
                context = context,
                operationId = operationId,
                preventReschedule = false
            )
        }
    }

    internal fun prepareSchedulerStop(
        operationId: String,
        preventReschedule: Boolean
    ) {
        (default as? DefaultDownloadExecutionHost)?.prepareSchedulerStop(
            operationId = operationId,
            preventReschedule = preventReschedule
        )
    }
}

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

internal fun normalizeDownloadOperationId(value: String?): String? {
    val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (normalized.length > 128) return null
    if (normalized == "." || normalized == "..") return null
    if (normalized.any { character -> character == '/' || character == '\\' }) {
        return null
    }
    if (normalized.any { character ->
            character.isWhitespace() ||
                character.code < 0x21 ||
                character.code > 0x7e
        }
    ) {
        return null
    }
    return normalized
}
