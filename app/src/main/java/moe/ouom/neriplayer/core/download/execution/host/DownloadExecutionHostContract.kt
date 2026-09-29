package moe.ouom.neriplayer.core.download.execution.host

import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import moe.ouom.neriplayer.data.model.download.DownloadExecutionSchedule
import moe.ouom.neriplayer.data.model.download.DownloadExecutionResult
import moe.ouom.neriplayer.data.model.download.DownloadExecutionPumpResult

import android.content.Context
import java.io.IOException

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
        attemptId: Long? = null,
        /** 来自真实网络 permit 的不透明 owner key，用于回收失联的宿主镜像 */
        transferPermitOwnerKey: String? = null
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

fun interface DownloadOperationEntryPoint {
    suspend fun start(
        context: Context,
        request: DownloadExecutionRequest
    ): DownloadExecutionResult
}

/** 统一校验 operation 标识，避免它被当作路径或跨代次身份使用 */
