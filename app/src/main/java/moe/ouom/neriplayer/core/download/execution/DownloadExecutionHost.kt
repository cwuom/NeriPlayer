package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.policy.shouldRequireExplicitResume
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import java.util.concurrent.ConcurrentHashMap

/** owns durable scheduling and operation identity for user initiated downloads */
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

    suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult
}

data class DownloadExecutionRequest(
    val operationId: String,
    val song: SongItem,
    val preserveStaging: Boolean = false,
    val attemptId: Long? = null,
    val userInitiated: Boolean = true
) {
    init {
        require(normalizeDownloadOperationId(operationId) == operationId) {
            "operationId must be a safe, non-empty identifier"
        }
    }
}

sealed interface DownloadExecutionSchedule {
    data class Scheduled(val backend: Backend) : DownloadExecutionSchedule

    data class Rejected(val reason: String) : DownloadExecutionSchedule

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
    data object Cancelled : DownloadExecutionResult
    data object UserStopped : DownloadExecutionResult
    data class Failed(val error: Throwable) : DownloadExecutionResult
}

fun interface DownloadOperationEntryPoint {
    suspend fun start(
        context: Context,
        operationId: String,
        song: SongItem,
        preserveStaging: Boolean
    )
}

private object ExistingDownloadOperationEntryPoint : DownloadOperationEntryPoint {
    override suspend fun start(
        context: Context,
        operationId: String,
        song: SongItem,
        preserveStaging: Boolean
    ) {
        val preparedAttemptId = DownloadExecutionOperationStore()
            .read(context.applicationContext, operationId)
            ?.attemptId
        // keep the engine behind one entry point so hosts do not duplicate transfer logic
        GlobalDownloadManager.startDownload(
            context = context,
            song = song,
            operationId = operationId,
            preserveStaging = preserveStaging,
            preparedAttemptId = preparedAttemptId
        )
    }
}

class DefaultDownloadExecutionHost(
    private val operationStore: DownloadExecutionOperationStore =
        DownloadExecutionOperationStore(),
    private val entryPoint: DownloadOperationEntryPoint =
        ExistingDownloadOperationEntryPoint,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : DownloadExecutionHost {
    private val operationIdsBySongKey = ConcurrentHashMap<String, String>()
    private val executingOperationIds = ConcurrentHashMap.newKeySet<String>()

    override fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule {
        val appContext = context.applicationContext
        return runCatching {
            val songKey = request.song.stableKey()
            val existingOperationId = operationIdsBySongKey[songKey]
                ?: operationStore.findOperationIdForSong(appContext, songKey)
            val existingState = existingOperationId?.let { id ->
                operationStore.currentState(appContext, id)
            }
            val existingReadable = existingOperationId?.let { id ->
                operationStore.read(appContext, id) != null
            } == true
            if (existingOperationId != null &&
                existingOperationId != request.operationId &&
                existingState in ACTIVE_SCHEDULING_STATES &&
                existingReadable
            ) {
                return@runCatching DownloadExecutionSchedule.Rejected(
                    "download operation already scheduled"
                )
            }
            val currentState = operationStore.currentState(appContext, request.operationId)
            if (currentState != null && currentState !in SCHEDULABLE_OPERATION_STATES) {
                return@runCatching DownloadExecutionSchedule.Rejected(
                    "operation is no longer schedulable: $currentState"
                )
            }
            if (currentState == null) {
                operationStore.save(appContext, request)
            }
            val selectedBackend = selectDownloadExecutionBackend(
                sdkInt = sdkInt,
                userInitiated = request.userInitiated
            )
            val scheduledBackend = when (selectedBackend) {
                DownloadExecutionSchedule.Backend.UIDT_JOB -> {
                    if (scheduleUidtIfSupported(appContext, request.operationId, sdkInt)) {
                        DownloadExecutionSchedule.Backend.UIDT_JOB
                    } else if (ForegroundDownloadWorker.schedule(appContext, request.operationId)) {
                        DownloadExecutionSchedule.Backend.FOREGROUND_WORK
                    } else {
                        null
                    }
                }

                DownloadExecutionSchedule.Backend.FOREGROUND_WORK -> {
                    ForegroundDownloadWorker.schedule(appContext, request.operationId)
                        .takeIf { it }
                        ?.let { DownloadExecutionSchedule.Backend.FOREGROUND_WORK }
                }
            }
            if (scheduledBackend == null) {
                return@runCatching DownloadExecutionSchedule.Rejected(
                    "${selectedBackend.name} host rejected operation"
                )
            }
            operationIdsBySongKey[songKey] = request.operationId
            DownloadExecutionSchedule.Scheduled(
                scheduledBackend
            )
        }.getOrElse { error ->
            DownloadExecutionSchedule.Rejected(
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private companion object {
        private val SCHEDULABLE_OPERATION_STATES = setOf(
            "PENDING_QUEUE",
            "QUEUED",
            "RETRYABLE"
        )
        private val ACTIVE_SCHEDULING_STATES = SCHEDULABLE_OPERATION_STATES +
            setOf("RUNNING", "COMMITTING", "CORE_COMMITTED", "ASSETS_ENRICHING")
    }

    override fun cancel(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
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
    }

    override fun cancelForSong(
        context: Context,
        songKey: String
    ) {
        val appContext = context.applicationContext
        val operationId = operationIdsBySongKey[songKey]
            ?: operationStore.findOperationIdForSong(appContext, songKey)
            ?: return
        cancel(appContext, operationId)
    }

    override fun stopForSong(
        context: Context,
        songKey: String,
        preventReschedule: Boolean
    ) {
        val appContext = context.applicationContext
        val operationId = operationIdsBySongKey[songKey]
            ?: operationStore.findOperationIdForSong(appContext, songKey)
            ?: return
        stop(appContext, operationId, preventReschedule)
    }

    override fun stop(
        context: Context,
        operationId: String,
        preventReschedule: Boolean
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        val request = operationStore.read(appContext, normalizedId) ?: return
        operationIdsBySongKey[request.song.stableKey()] = normalizedId
        if (preventReschedule) {
            operationStore.markStopped(appContext, normalizedId)
        } else {
            // make the paused operation the one queue refresh can resume
            operationStore.updateState(
                context = appContext,
                operationId = normalizedId,
                state = "RETRYABLE",
                errorCode = "HOST_STOPPED"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(appContext, normalizedId)
        }
        ForegroundDownloadWorker.cancel(appContext, normalizedId)
        GlobalDownloadManager.stopDownloadOperation(
            context = appContext,
            songKey = request.song.stableKey()
        )
        if (preventReschedule) {
            operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
        }
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
            stopRequestedByUser = operationStore.isStopped(appContext, normalizedId)
        )
    }

    override fun operationIdForSong(context: Context, songKey: String): String? {
        return operationStore.findOperationIdForSong(
            context.applicationContext,
            songKey
        )
    }

    override fun markUserRequestedProcessExitOperations(context: Context): Set<String> {
        if (sdkInt < Build.VERSION_CODES.R) return emptySet()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return emptySet()
        val latestExit = latestProcessExit(activityManager, context.packageName) ?: return emptySet()
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
        preferences.edit {
            putLong(PROCESS_EXIT_TIMESTAMP_KEY, latestExit.timestamp)
        }
        if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return emptySet()
        }
        val activeStates = listOf("PENDING_QUEUE", "QUEUED", "RUNNING", "RETRYABLE")
        val entries = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            DownloadExecutionRoomStore.listByStates(context, activeStates)
        }
        return entries
            .filter { it.request.userInitiated }
            .mapTo(linkedSetOf()) { entry ->
                operationStore.markStopped(context.applicationContext, entry.request.operationId)
                entry.request.song.stableKey()
            }
    }

    override suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult = withContext(Dispatchers.IO) {
        val normalizedId = normalizeDownloadOperationId(operationId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        val request = operationStore.read(context.applicationContext, normalizedId)
            ?: run {
                moe.ouom.neriplayer.core.logging.NPLogger.w(
                    "NERI-DownloadHost",
                    "下载 operation 读取失败: operationId=$normalizedId, reason=missing_or_unreadable"
                )
                return@withContext DownloadExecutionResult.MissingOperation
            }
        if (operationStore.isStopped(context.applicationContext, normalizedId)) {
            return@withContext DownloadExecutionResult.UserStopped
        }
        if (operationStore.currentState(context.applicationContext, normalizedId) == "CANCEL_REQUESTED") {
            return@withContext DownloadExecutionResult.Cancelled
        }
        if (!executingOperationIds.add(normalizedId)) {
            return@withContext DownloadExecutionResult.AlreadyHandled
        }
        try {
            if (!operationStore.tryStart(
                    context = context.applicationContext,
                    operationId = normalizedId,
                    allowExistingRunning = true
                )
            ) {
                return@withContext DownloadExecutionResult.AlreadyHandled
            }
            operationIdsBySongKey[request.song.stableKey()] = normalizedId
            entryPoint.start(
                context = context.applicationContext,
                operationId = request.operationId,
                song = request.song,
                preserveStaging = request.preserveStaging
            )
            val result = GlobalDownloadManager.executionResultFor(request.song.stableKey())
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
                DownloadExecutionResult.MissingOperation -> Unit
            }
            result
        } catch (cancellation: CancellationException) {
            GlobalDownloadManager.stopDownloadOperation(
                context = context.applicationContext,
                songKey = request.song.stableKey()
            )
            throw cancellation
        } catch (error: Throwable) {
            operationStore.updateState(
                context = context.applicationContext,
                operationId = normalizedId,
                state = "RETRYABLE",
                errorCode = error.javaClass.simpleName
            )
            DownloadExecutionResult.Failed(error)
        } finally {
            executingOperationIds.remove(normalizedId)
        }
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
    return reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
        reason == ApplicationExitInfo.REASON_USER_STOPPED
}

private const val PROCESS_EXIT_PREFERENCES = "download_execution_host"
private const val PROCESS_EXIT_TIMESTAMP_KEY = "last_user_requested_exit_timestamp"

object DownloadExecutionHosts {
    val default: DownloadExecutionHost = DefaultDownloadExecutionHost()
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun scheduleUidt(
    context: Context,
    operationId: String
): Boolean {
    return UidtDownloadJobService.schedule(context, operationId)
}

internal fun selectDownloadExecutionBackend(
    sdkInt: Int,
    userInitiated: Boolean
): DownloadExecutionSchedule.Backend {
    return if (
        userInitiated &&
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            sdkInt < Build.VERSION_CODES.BAKLAVA
    ) {
        DownloadExecutionSchedule.Backend.UIDT_JOB
    } else {
        DownloadExecutionSchedule.Backend.FOREGROUND_WORK
    }
}

private fun scheduleUidtIfSupported(
    context: Context,
    operationId: String,
    sdkInt: Int
): Boolean {
    if (
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    ) {
        return false
    }
    return scheduleUidt(context, operationId)
}

private const val TERMINAL_OPERATION_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L
private const val TERMINAL_OPERATION_PRUNE_LIMIT = 64

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
