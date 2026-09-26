package moe.ouom.neriplayer.core.download.execution.host

import android.content.Context

/** 统一持有下载宿主实例，避免不同入口各自创建泵 */
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

    internal fun onCoreCommitted(
        context: Context,
        operationId: String,
        attemptId: Long? = null,
        transferOwnerToken: Long? = null
    ): Boolean {
        return default.onCoreCommitted(
            context = context,
            operationId = operationId,
            attemptId = attemptId,
            transferOwnerToken = transferOwnerToken
        )
    }

    internal fun onTransferStarted(
        context: Context,
        operationId: String,
        attemptId: Long? = null,
        transferPermitOwnerKey: String? = null
    ): Long? {
        return default.onTransferStarted(
            context = context,
            operationId = operationId,
            attemptId = attemptId,
            transferPermitOwnerKey = transferPermitOwnerKey
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
