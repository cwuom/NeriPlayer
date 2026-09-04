package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.logging.NPLogger
import java.util.concurrent.atomic.AtomicBoolean

internal data class UidtStopRequest(
    val context: Context,
    val operationId: String,
    val action: UidtStopAction,
    val preventReschedule: Boolean
)

internal class UidtJobCompletionGate {
    private val stoppedByScheduler = AtomicBoolean(false)

    fun markSchedulerStopped() {
        stoppedByScheduler.set(true)
    }

    fun shouldReportCompletion(): Boolean = !stoppedByScheduler.get()
}

internal fun sealUidtCompletionGates(gates: Iterable<UidtJobCompletionGate>) {
    gates.forEach(UidtJobCompletionGate::markSchedulerStopped)
}

internal class UidtStopCoordinator(
    scope: CoroutineScope,
    private val converge: suspend (UidtStopRequest) -> Unit,
    private val onFailure: (UidtStopRequest, Throwable) -> Unit = { _, _ -> }
) {
    private val requests = Channel<UidtStopRequest>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    converge(request)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    onFailure(request, error)
                }
            }
        }
    }

    fun enqueue(request: UidtStopRequest): Boolean {
        return requests.trySend(request).isSuccess
    }
}

/** process ownership keeps accepted stop convergence alive after JobService destruction */
internal object UidtStopCoordinators {
    private const val TAG = "NERI-DownloadUidt"
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val default = UidtStopCoordinator(
        scope = processScope,
        converge = { request ->
            when (request.action) {
                UidtStopAction.RETRY_WITHOUT_CANCELLING_BACKENDS -> {
                    DownloadExecutionHosts.stopForSystemRetry(
                        context = request.context,
                        operationId = request.operationId
                    )
                }

                UidtStopAction.STOP_AND_CANCEL_BACKENDS -> {
                    DownloadExecutionHosts.default.stop(
                        context = request.context,
                        operationId = request.operationId,
                        preventReschedule = request.preventReschedule
                    )
                }
            }
        },
        onFailure = { request, error ->
            NPLogger.e(
                TAG,
                "UIDT 停止收敛失败: operationId=${request.operationId}, " +
                    "error=${error.message}",
                error
            )
        }
    )
}
