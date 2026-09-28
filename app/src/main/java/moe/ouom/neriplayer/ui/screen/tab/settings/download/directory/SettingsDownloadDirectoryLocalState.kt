package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job

internal class DownloadDirectoryLocalState {
    val showSwitchWarning = mutableStateOf(false)
    val pendingChange = mutableStateOf<PendingDownloadDirectoryChange?>(null)
    val isPreparing = mutableStateOf(false)
    val preparationJob = mutableStateOf<Job?>(null)
    val permissionLost = mutableStateOf(false)
}

@Composable
internal fun rememberDownloadDirectoryLocalState(): DownloadDirectoryLocalState =
    remember { DownloadDirectoryLocalState() }
