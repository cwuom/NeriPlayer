package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory

import androidx.compose.runtime.MutableState

internal class DownloadDirectorySwitchOwner(
    private val showWarningState: MutableState<Boolean>,
    private val isBlocked: () -> Boolean,
    private val launchPicker: () -> Unit
) {
    fun requestPick() {
        if (!isBlocked()) showWarningState.value = true
    }

    fun dismissWarning() {
        showWarningState.value = false
    }

    fun confirmWarning() {
        showWarningState.value = false
        if (!isBlocked()) launchPicker()
    }
}
