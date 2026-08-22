package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.model.SongItem

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

    suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult
}

data class DownloadExecutionRequest(
    val operationId: String,
    val song: SongItem
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
        song: SongItem
    )
}

private object ExistingDownloadOperationEntryPoint : DownloadOperationEntryPoint {
    override suspend fun start(
        context: Context,
        operationId: String,
        song: SongItem
    ) {
        // keep the engine behind one entry point so hosts do not duplicate transfer logic
        GlobalDownloadManager.startDownload(context, song)
    }
}

class DefaultDownloadExecutionHost(
    private val operationStore: DownloadExecutionOperationStore =
        DownloadExecutionOperationStore(),
    private val entryPoint: DownloadOperationEntryPoint =
        ExistingDownloadOperationEntryPoint,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : DownloadExecutionHost {
    override fun schedule(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionSchedule {
        val appContext = context.applicationContext
        return runCatching {
            operationStore.save(appContext, request)
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    return@runCatching DownloadExecutionSchedule.Rejected(
                        "UIDT is unavailable on this API level"
                    )
                }
                if (!scheduleUidt(appContext, request.operationId)) {
                    return@runCatching DownloadExecutionSchedule.Rejected(
                        "UIDT JobScheduler rejected operation"
                    )
                }
                DownloadExecutionSchedule.Scheduled(
                    DownloadExecutionSchedule.Backend.UIDT_JOB
                )
            } else {
                if (!ForegroundDownloadWorker.schedule(appContext, request.operationId)) {
                    return@runCatching DownloadExecutionSchedule.Rejected(
                        "WorkManager rejected operation"
                    )
                }
                DownloadExecutionSchedule.Scheduled(
                    DownloadExecutionSchedule.Backend.FOREGROUND_WORK
                )
            }
        }.getOrElse { error ->
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            cancelUidt(appContext, normalizedId)
        }
        ForegroundDownloadWorker.cancel(appContext, normalizedId)
        operationStore.remove(appContext, normalizedId)
    }

    override suspend fun execute(
        context: Context,
        operationId: String
    ): DownloadExecutionResult = withContext(Dispatchers.IO) {
        val normalizedId = normalizeDownloadOperationId(operationId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        val request = operationStore.read(context.applicationContext, normalizedId)
            ?: return@withContext DownloadExecutionResult.MissingOperation
        try {
            entryPoint.start(
                context = context.applicationContext,
                operationId = request.operationId,
                song = request.song
            )
            DownloadExecutionResult.Accepted
        } catch (_: CancellationException) {
            DownloadExecutionResult.Cancelled
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
