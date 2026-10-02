package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.directoryProbeTimeoutFailure
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.runDownloadDirectoryPreflight

internal interface DownloadDirectorySelectionGateway {
    suspend fun persistGrant(targetUri: String, onPersisted: () -> Unit)
    suspend fun describe(targetUri: String): String
    fun isConfiguredDirectory(targetUri: String): Boolean
    suspend fun releaseGrant(targetUri: String)
}

internal fun selectedDownloadDirectorySummary(result: Result<String>?): String =
    (result ?: throw directoryProbeTimeoutFailure(DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS))
        .getOrThrow()

internal class AndroidDownloadDirectorySelectionGateway(
    private val context: Context
) : DownloadDirectorySelectionGateway {
    override suspend fun persistGrant(targetUri: String, onPersisted: () -> Unit) {
        withContext(Dispatchers.IO) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(targetUri.toUri(), flags)
            onPersisted()
        }
    }

    override suspend fun describe(targetUri: String): String = selectedDownloadDirectorySummary(
        runDownloadDirectoryPreflight {
            withContext(Dispatchers.IO) {
                ManagedDownloadStorage.describeConfiguredDirectory(context, targetUri)
            }
        }
    )

    override fun isConfiguredDirectory(targetUri: String): Boolean =
        ManagedDownloadStorage.isConfiguredDirectoryUri(targetUri)

    override suspend fun releaseGrant(targetUri: String) {
        withContext(NonCancellable + Dispatchers.IO) {
            ManagedDownloadStorage.releasePersistedDirectoryPermission(context, targetUri)
        }
    }
}

private class SelectedDownloadDirectoryGrant(
    private val gateway: DownloadDirectorySelectionGateway,
    private val targetUri: String
) {
    private var grantPersisted = false
    private var keepGrant = false

    suspend fun persist() {
        gateway.persistGrant(targetUri) { grantPersisted = true }
    }

    fun keepIfPrepared(result: DownloadDirectoryPreparationResult) {
        keepGrant = result == DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
    }

    suspend fun releaseIfNeeded() {
        // 目录生效后权限已交给存储层，离开设置页不能撤销它
        if (grantPersisted && !keepGrant && !gateway.isConfiguredDirectory(targetUri)) {
            gateway.releaseGrant(targetUri)
        }
    }
}

internal class DownloadDirectorySelectionOwner(
    private val gateway: DownloadDirectorySelectionGateway,
    private val scope: CoroutineScope,
    private val isPreparingState: MutableState<Boolean>,
    private val preparationJobState: MutableState<Job?>,
    private val permissionLostState: MutableState<Boolean>,
    private val isBlocked: () -> Boolean,
    private val prepare: suspend (targetUri: String, targetSummary: String) -> DownloadDirectoryPreparationResult,
    private val onError: (Exception) -> Unit
) {
    fun onPicked(targetUri: String?) {
        if (targetUri == null || isBlocked()) return
        isPreparingState.value = true
        preparationJobState.value = scope.launch { processPicked(targetUri) }
    }

    private suspend fun processPicked(targetUri: String) {
        val grant = SelectedDownloadDirectoryGrant(gateway, targetUri)
        try {
            grant.persist()
            permissionLostState.value = false
            val summary = gateway.describe(targetUri)
            grant.keepIfPrepared(prepare(targetUri, summary))
        } catch (error: Exception) {
            reportFailure(error)
        } finally {
            try {
                grant.releaseIfNeeded()
            } finally {
                isPreparingState.value = false
                preparationJobState.value = null
            }
        }
    }

    private fun reportFailure(error: Exception) {
        if (error is CancellationException) throw error
        onError(error)
    }
}
