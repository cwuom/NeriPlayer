package moe.ouom.neriplayer.ui.screen.tab

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.util.time.elapsedMillisSince

internal interface DownloadDirectoryResetGateway {
    suspend fun availability(currentUri: String?): DownloadDirectoryAvailability
}

internal class AndroidDownloadDirectoryResetGateway(
    private val context: Context
) : DownloadDirectoryResetGateway {
    override suspend fun availability(currentUri: String?): DownloadDirectoryAvailability =
        withContext(Dispatchers.IO) {
            resolveDownloadDirectoryAvailability(
                directoryUri = currentUri,
                isRootResolvable = { probeConfiguredDownloadRoot(context) }
            )
        }
}

internal interface DownloadDirectoryResetActionPort {
    fun isBlocked(): Boolean
    suspend fun prepareDefault(targetSummary: String)
    suspend fun applyDefault(targetSummary: String, previousUri: String?)
}

internal fun downloadDirectoryProviderFailureType(error: Throwable?): String =
    error?.javaClass?.simpleName ?: "timeout"

internal class DownloadDirectoryResetOwner(
    private val gateway: DownloadDirectoryResetGateway,
    private val actions: DownloadDirectoryResetActionPort,
    private val scope: CoroutineScope,
    private val resources: Resources,
    private val currentUri: String?,
    private val defaultSummary: String,
    private val isPreparingState: MutableState<Boolean>,
    private val preparationJobState: MutableState<Job?>,
    private val onInlineMessageChange: (String?) -> Unit,
    private val onShowMessage: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    fun onResetRequested() {
        if (actions.isBlocked()) return
        isPreparingState.value = true
        preparationJobState.value = scope.launch { reset() }
    }

    private suspend fun reset() {
        try {
            val startedAtNanos = System.nanoTime()
            val availability = gateway.availability(currentUri)
            NPLogger.d(
                "DownloadDirectoryPreflight",
                "directory_preflight stage=source_availability status=complete " +
                    "availability=${availability::class.java.simpleName} " +
                    "elapsedMs=${elapsedMillisSince(startedAtNanos)}"
            )
            when (availability) {
                DownloadDirectoryAvailability.Available -> actions.prepareDefault(defaultSummary)
                DownloadDirectoryAvailability.Unavailable -> actions.applyDefault(defaultSummary, currentUri)
                is DownloadDirectoryAvailability.ProviderFailure -> showRetryableFailure(availability)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError(error)
        } finally {
            isPreparingState.value = false
            preparationJobState.value = null
        }
    }

    private fun showRetryableFailure(failure: DownloadDirectoryAvailability.ProviderFailure) {
        NPLogger.w(
            "DownloadDirectoryPreflight",
            "directory_preflight stage=source_availability status=retryable " +
                "errorType=${downloadDirectoryProviderFailureType(failure.error.cause)}"
        )
        val message = resources.getString(R.string.managed_library_processing_retry)
        onInlineMessageChange(message)
        onShowMessage(message)
    }
}
