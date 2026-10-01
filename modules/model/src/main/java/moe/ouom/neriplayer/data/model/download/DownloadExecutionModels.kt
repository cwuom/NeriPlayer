package moe.ouom.neriplayer.data.model.download

import java.util.UUID
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.download.DownloadAudioQualitySelection

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
    val batchGeneration: Long? = null,
    // 已知无效的成品不能在重试或重启时再次被目录缓存接纳
    val requiresFreshTransfer: Boolean = false
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
    ContinueAfterContention,
    ContinueAfterRetry,
    Retry
}

fun normalizeDownloadOperationId(value: String?): String? {
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
