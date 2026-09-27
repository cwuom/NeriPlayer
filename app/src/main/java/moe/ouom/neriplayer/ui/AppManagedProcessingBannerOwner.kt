package moe.ouom.neriplayer.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.ManagedLibraryProcessingState

internal data class AppManagedProcessingBannerPresentation(
    val active: Boolean,
    val state: ManagedLibraryProcessingState,
    val progress: ManagedDownloadStorage.MigrationProgress?,
    val collapsed: Boolean
) {
    val visible: Boolean get() = active && !collapsed
    val revealGestureEnabled: Boolean get() = active && collapsed
}

internal class AppManagedProcessingBannerOwner(
    private val collapsedState: MutableState<Boolean>
) {
    private var displayState by mutableStateOf<ManagedLibraryProcessingState>(
        ManagedLibraryProcessingState.Idle
    )
    private var displayProgress by mutableStateOf<ManagedDownloadStorage.MigrationProgress?>(null)

    fun observe(state: ManagedLibraryProcessingState, progress: ManagedDownloadStorage.MigrationProgress?) {
        if (state != ManagedLibraryProcessingState.Idle) {
            displayState = state
            displayProgress = progress
        } else if (progress != null) {
            displayProgress = progress
        }
    }

    fun onOperationChanged(state: ManagedLibraryProcessingState) {
        if (state != ManagedLibraryProcessingState.Idle) {
            collapsedState.value = false
        }
    }

    fun collapse(collapsed: Boolean) {
        collapsedState.value = collapsed
    }

    fun expand() {
        collapsedState.value = false
    }

    fun presentation(
        state: ManagedLibraryProcessingState,
        progress: ManagedDownloadStorage.MigrationProgress?
    ): AppManagedProcessingBannerPresentation {
        val active = state != ManagedLibraryProcessingState.Idle
        return AppManagedProcessingBannerPresentation(
            active = active,
            state = if (active) state else displayState,
            progress = if (active) progress else displayProgress,
            collapsed = collapsedState.value
        )
    }
}
