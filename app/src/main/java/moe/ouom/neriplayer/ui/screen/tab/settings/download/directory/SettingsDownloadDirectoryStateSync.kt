package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

internal interface DownloadDirectorySummaryGateway {
    suspend fun describe(uri: String): Result<String>?
}

internal class AndroidDownloadDirectorySummaryGateway(
    private val context: Context
) : DownloadDirectorySummaryGateway {
    override suspend fun describe(uri: String): Result<String>? = runDownloadDirectoryPreflight {
        withContext(Dispatchers.IO) {
            ManagedDownloadStorage.describeConfiguredDirectory(context, uri)
        }
    }
}

internal class DownloadDirectorySummaryOwner(
    private val gateway: DownloadDirectorySummaryGateway,
    private val directoryUri: String?,
    private val defaultSummary: String,
    private val summaryState: MutableState<String>
) {
    suspend fun refresh() {
        if (directoryUri.isNullOrBlank()) {
            summaryState.value = defaultSummary
            return
        }
        summaryState.value = gateway.describe(directoryUri)?.getOrNull() ?: summaryState.value
    }
}

@Composable
internal fun SyncDownloadDirectorySummary(
    context: Context,
    directoryUri: String?,
    defaultSummary: String,
    summaryState: MutableState<String>
) {
    val owner = DownloadDirectorySummaryOwner(
        AndroidDownloadDirectorySummaryGateway(context), directoryUri, defaultSummary, summaryState
    )
    LaunchedEffect(directoryUri, defaultSummary) { owner.refresh() }
}

internal class DownloadDirectoryPermissionOwner(
    private val directoryUri: String?,
    private val permissionLostState: MutableState<Boolean>,
    private val resolvePermissionLost: suspend (String?) -> Boolean
) {
    suspend fun refresh() {
        permissionLostState.value = resolvePermissionLost(directoryUri)
    }
}

@Composable
internal fun SyncDownloadDirectoryPermission(
    context: Context,
    directoryUri: String?,
    permissionLostState: MutableState<Boolean>
) {
    val owner = DownloadDirectoryPermissionOwner(directoryUri, permissionLostState) { uri ->
        resolveDownloadDirectoryPermissionLost(
            directoryUri = uri,
            isRootResolvable = { probeConfiguredDownloadRoot(context) }
        )
    }
    LaunchedEffect(directoryUri) { owner.refresh() }
}
