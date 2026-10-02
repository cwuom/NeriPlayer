package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryRefreshOutcome
import kotlin.time.Duration.Companion.milliseconds

private const val DOWNLOAD_DIRECTORY_REFRESH_TIMEOUT_MS = 30_000L

internal interface DownloadDirectoryApplyGateway {
    suspend fun tryBeginExclusive(): String?
    fun currentBusyReason(): ManagedLibraryProcessingReason?
    fun configure(uri: String?, label: String?)
    fun refresh(
        operationId: String,
        onResult: suspend (ManagedLibraryRefreshOutcome) -> Unit
    ): Deferred<Unit>
    suspend fun complete(operationId: String)
    suspend fun waitingForRetry(operationId: String)
    fun releasePreviousPermission(uri: String?)
}

internal class AndroidDownloadDirectoryApplyGateway(context: Context) : DownloadDirectoryApplyGateway {
    private val context = context.applicationContext

    override suspend fun tryBeginExclusive(): String? =
        ManagedLibraryProcessingCoordinator.tryBeginExclusive(
            context = context,
            reason = ManagedLibraryProcessingReason.DIRECTORY_CHANGE,
            phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
        )

    override fun currentBusyReason(): ManagedLibraryProcessingReason? =
        ManagedLibraryProcessingCoordinator.state.value.reason

    override fun configure(uri: String?, label: String?) {
        ManagedDownloadStorage.updateConfiguredTreeUri(uri)
        ManagedDownloadStorage.updateCustomDirectoryLabel(label)
    }

    override fun refresh(
        operationId: String,
        onResult: suspend (ManagedLibraryRefreshOutcome) -> Unit
    ): Deferred<Unit> =
        GlobalDownloadManager.refreshDownloadDirectory(
            context = context,
            operationId = operationId
        ) { outcome ->
            withContext(Dispatchers.Main.immediate) { onResult(outcome) }
        }

    override suspend fun complete(operationId: String) {
        ManagedLibraryProcessingCoordinator.complete(context, operationId)
    }

    override suspend fun waitingForRetry(operationId: String) {
        ManagedLibraryProcessingCoordinator.waitingForRetry(context, operationId)
    }

    override fun releasePreviousPermission(uri: String?) {
        ManagedDownloadStorage.releasePersistedDirectoryPermission(context, uri)
    }
}

internal fun appliedDownloadDirectoryMessageId(targetUri: String?): Int =
    if (targetUri.isNullOrBlank()) CoreCommonR.string.settings_download_directory_reset_done
    else CoreCommonR.string.settings_download_directory_selected

internal class DownloadDirectoryApplyOwner(
    private val gateway: DownloadDirectoryApplyGateway,
    private val resources: Resources,
    private val onDirectoryUriChange: (String?, String?) -> Unit,
    private val onInlineMessageChange: (String?) -> Unit,
    private val isPreparingState: MutableState<Boolean>,
    private val permissionLostState: MutableState<Boolean>
) {
    suspend fun apply(
        targetUri: String?,
        targetSummary: String,
        previousUri: String?,
        shouldReleasePreviousPermission: Boolean
    ) {
        isPreparingState.value = false
        val operationId = gateway.tryBeginExclusive()
            ?: throw ManagedLibraryProcessingBusyException(gateway.currentBusyReason())
        val session = DownloadDirectoryApplySession(
            gateway, resources, onInlineMessageChange, operationId,
            targetUri, previousUri, shouldReleasePreviousPermission
        )
        try {
            val targetLabel = targetSummary.takeIf { !targetUri.isNullOrBlank() }
            gateway.configure(targetUri, targetLabel)
            onDirectoryUriChange(targetUri, targetLabel)
            permissionLostState.value = false
            session.refresh()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { runCatching { session.markWaiting(showMessage = false) } }
            throw error
        } catch (error: Exception) {
            runCatching { session.markWaiting(showMessage = false) }
            throw error
        }
    }
}

private class DownloadDirectoryApplySession(
    private val gateway: DownloadDirectoryApplyGateway,
    private val resources: Resources,
    private val onInlineMessageChange: (String?) -> Unit,
    private val operationId: String,
    private val targetUri: String?,
    private val previousUri: String?,
    private val shouldReleasePreviousPermission: Boolean
) {
    private val mutex = Mutex()
    private var finished = false

    suspend fun refresh() {
        // 扫描和完整收尾都由同一个后台任务持有，设置页只限制自己的等待时间
        val refresh = gateway.refresh(operationId, ::finish)
        if (withTimeoutOrNull(DOWNLOAD_DIRECTORY_REFRESH_TIMEOUT_MS.milliseconds) { refresh.await() } == null) {
            markWaiting(showMessage = true)
        }
    }

    private suspend fun finish(outcome: ManagedLibraryRefreshOutcome) = mutex.withLock {
        try {
            if (outcome is ManagedLibraryRefreshOutcome.Published) {
                if (shouldReleasePreviousPermission) gateway.releasePreviousPermission(previousUri)
                onInlineMessageChange(resources.getString(appliedDownloadDirectoryMessageId(targetUri)))
                gateway.complete(operationId)
            } else {
                gateway.waitingForRetry(operationId)
                showRetryMessage()
            }
            finished = true
        } catch (error: Exception) {
            showRetryMessage()
            throw error
        }
    }

    suspend fun markWaiting(showMessage: Boolean) {
        // 收尾已经持有锁时由它负责结果，前台不能在超时后继续等待这把锁
        if (!mutex.tryLock()) return
        try {
            if (!finished) {
                gateway.waitingForRetry(operationId)
                if (showMessage) showRetryMessage()
            }
        } finally {
            mutex.unlock()
        }
    }

    private fun showRetryMessage() {
        onInlineMessageChange(resources.getString(CoreCommonR.string.managed_library_processing_retry))
    }
}
