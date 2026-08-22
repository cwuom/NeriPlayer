package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
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

    fun stop(
        context: Context,
        operationId: String,
        preventReschedule: Boolean = true
    )

    fun externallyStoppedSongKeys(
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
    val preserveStaging: Boolean = false
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
    data object MissingOperation : DownloadExecutionResult
    data object Retry : DownloadExecutionResult
    data object Cancelled : DownloadExecutionResult
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
        // keep the engine behind one entry point so hosts do not duplicate transfer logic
        GlobalDownloadManager.startDownload(
            context = context,
            song = song,
            operationId = operationId,
            preserveStaging = preserveStaging
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

    override fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule {
        val appContext = context.applicationContext
        return runCatching {
            val songKey = request.song.stableKey()
            val existingOperationId = operationIdsBySongKey[songKey]
                ?: operationStore.findOperationIdForSong(appContext, songKey)
            if (existingOperationId != null &&
                existingOperationId != request.operationId &&
                operationStore.read(appContext, existingOperationId) != null
            ) {
                return@runCatching DownloadExecutionSchedule.Rejected(
                    "download operation already scheduled"
                )
            }
            operationStore.save(appContext, request)
            val scheduled = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    false
                } else {
                    scheduleUidt(appContext, request.operationId)
                }
            } else {
                ForegroundDownloadWorker.schedule(appContext, request.operationId)
            }
            if (!scheduled) {
                operationStore.remove(appContext, request.operationId)
                return@runCatching DownloadExecutionSchedule.Rejected(
                    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        "UIDT JobScheduler rejected operation"
                    } else {
                        "WorkManager rejected operation"
                    }
                )
            }
            operationIdsBySongKey[songKey] = request.operationId
            DownloadExecutionSchedule.Scheduled(
                if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    DownloadExecutionSchedule.Backend.UIDT_JOB
                } else {
                    DownloadExecutionSchedule.Backend.FOREGROUND_WORK
                }
            )
        }.getOrElse { error ->
            operationStore.remove(appContext, request.operationId)
            DownloadExecutionSchedule.Rejected(
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    override fun cancel(
        context: Context,
        operationId: String
    ) {
        val normalizedId = normalizeDownloadOperationId(operationId) ?: return
        val appContext = context.applicationContext
        val request = operationStore.read(appContext, normalizedId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(appContext, normalizedId)
        }
        ForegroundDownloadWorker.cancel(appContext, normalizedId)
        operationStore.remove(appContext, normalizedId)
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
        }
        GlobalDownloadManager.stopDownloadOperation(
            context = appContext,
            songKey = request.song.stableKey()
        )
    }

    override fun externallyStoppedSongKeys(context: Context): Set<String> {
        return operationStore.stoppedSongKeys(context.applicationContext)
    }

    override suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult = withContext(Dispatchers.IO) {
        val normalizedId = normalizeDownloadOperationId(operationId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        val request = operationStore.read(context.applicationContext, normalizedId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        if (operationStore.isStopped(context.applicationContext, normalizedId)) {
            return@withContext DownloadExecutionResult.Retry
        }
        operationIdsBySongKey[request.song.stableKey()] = normalizedId
        try {
            entryPoint.start(
                context = context.applicationContext,
                operationId = request.operationId,
                song = request.song,
                preserveStaging = request.preserveStaging
            )
            val result = GlobalDownloadManager.executionResultFor(request.song.stableKey())
            if (result == DownloadExecutionResult.Accepted ||
                result == DownloadExecutionResult.Cancelled
            ) {
                operationStore.remove(context.applicationContext, normalizedId)
                operationIdsBySongKey.remove(request.song.stableKey(), normalizedId)
            }
            result
        } catch (cancellation: CancellationException) {
            GlobalDownloadManager.stopDownloadOperation(
                context = context.applicationContext,
                songKey = request.song.stableKey()
            )
            throw cancellation
        } catch (error: Throwable) {
            DownloadExecutionResult.Failed(error)
        }
    }
}

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
