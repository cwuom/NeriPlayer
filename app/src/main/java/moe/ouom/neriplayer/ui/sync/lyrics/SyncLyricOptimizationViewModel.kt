package moe.ouom.neriplayer.ui.sync.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal data class SyncLyricOptimizationUiState(
    val enabled: Boolean? = null,
    val selectedEnabled: Boolean = false,
    val dialogRequested: Boolean = false,
    val dialogOwner: Any? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasError: Boolean = false
) {
    val canConfirm: Boolean
        get() = enabled != null && dialogRequested && dialogOwner != null && !isLoading && !isSaving
}

internal class SyncLyricOptimizationViewModel(
    private val loadEnabled: suspend () -> Boolean,
    private val saveEnabled: suspend (Boolean) -> Unit
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SyncLyricOptimizationUiState())
    val uiState = mutableUiState.asStateFlow()
    private var loadJob: Job? = null
    private val defaultOwner = Any()

    init {
        refresh()
    }

    fun refresh() {
        val state = uiState.value
        if (state.isSaving || state.dialogRequested || loadJob?.isActive == true) return
        mutableUiState.update { it.copy(enabled = null, isLoading = true, hasError = false) }
        loadJob = viewModelScope.launch {
            try {
                val enabled = loadEnabled()
                coroutineContext.ensureActive()
                mutableUiState.update { it.copy(enabled = enabled, selectedEnabled = enabled) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update { it.copy(hasError = true) }
            } finally {
                mutableUiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun open(owner: Any = defaultOwner): Boolean {
        val state = uiState.value
        val enabled = state.enabled ?: return false
        if (state.isLoading || state.isSaving || state.dialogOwner != null) return false
        val selected = if (state.dialogRequested) state.selectedEnabled else enabled
        mutableUiState.update {
            it.copy(dialogRequested = true, dialogOwner = owner, selectedEnabled = selected)
        }
        return true
    }

    fun release(owner: Any) {
        val state = uiState.value
        if (state.dialogOwner !== owner) return
        if (state.isSaving) mutableUiState.update { it.copy(dialogOwner = null) } else dismiss()
    }

    fun choose(enabled: Boolean) {
        mutableUiState.update { state ->
            if (state.isSaving || !state.dialogRequested) state else state.copy(selectedEnabled = enabled, hasError = false)
        }
    }

    fun dismiss(): Boolean {
        if (uiState.value.isSaving) return false
        if (!uiState.value.dialogRequested) return true
        mutableUiState.update {
            it.copy(dialogRequested = false, dialogOwner = null, selectedEnabled = it.enabled ?: false, hasError = false)
        }
        return true
    }

    fun confirm() {
        val state = uiState.value
        if (!state.canConfirm) return
        mutableUiState.update { it.copy(isSaving = true, hasError = false) }
        viewModelScope.launch {
            try {
                saveEnabled(state.selectedEnabled)
                coroutineContext.ensureActive()
                mutableUiState.update { it.copy(enabled = state.selectedEnabled, dialogRequested = false, dialogOwner = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update { it.copy(hasError = true) }
            } finally {
                mutableUiState.update { it.copy(isSaving = false) }
            }
        }
    }
}
