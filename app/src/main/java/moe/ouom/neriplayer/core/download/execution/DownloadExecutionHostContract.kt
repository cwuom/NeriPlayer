package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.settings.DownloadAudioQualitySelection
import java.io.IOException
import java.util.UUID

/** 宿主未授予 transfer lane 时，调用方必须在未发起网络 I/O 前退出并交给持久队列重试 */
internal class DownloadTransferAdmissionDeferredException(
    operationId: String,
    attemptId: Long?
) : IOException(
    "download host did not admit transfer: operationId=$operationId, attemptId=$attemptId"
)

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

    /**
     * 网络 permit 真正拿到后才占用 transfer lane，并返回 Host 自己生成的 owner token。
     * Core Commit 必须原样回传该 token；不要传下载 permit 的 generation
     */
    fun onTransferStarted(
        context: Context,
        operationId: String,
        attemptId: Long? = null
    ): Long? = null

    /** 音频 Core Commit 已持久化，必须回传启动时的 owner token 才能释放 transfer lane */
    fun onCoreCommitted(
        context: Context,
        operationId: String,
        attemptId: Long? = null,
        transferOwnerToken: Long? = null
    ): Boolean = false

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
    val downloadAudioQuality: DownloadAudioQualitySelection? = null,
    val batchId: String? = null,
    val batchGeneration: Long? = null
) {
    init {
        require(normalizeDownloadOperationId(operationId) == operationId) {
            "operationId must be a safe, non-empty identifier"
        }
        require(artifactLeaseId.isNotBlank()) {
            "artifactLeaseId must be non-empty"
        }
        require((batchId == null) == (batchGeneration == null)) {
            "batchId and batchGeneration must be provided together"
        }
        if (batchId != null) {
            require(normalizeDownloadOperationId(batchId) == batchId) {
                "batchId must be a safe, non-empty identifier"
            }
            require(batchGeneration!! > 0L) { "batchGeneration must be positive" }
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
    ContinueSoon,
    ContinueAfterRetry,
    Retry
}

fun interface DownloadOperationEntryPoint {
    suspend fun start(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionResult
}

/** 统一校验 operation 标识，避免它被当作路径或跨代次身份使用 */
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
