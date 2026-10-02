package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SyncProtocolUpgradeUiState(
    val approved: Boolean? = null,
    val dialogRequested: Boolean = false,
    val allDevicesUpdated: Boolean = false,
    val isSaving: Boolean = false,
    val hasError: Boolean = false
) {
    val canConfirm: Boolean
        get() = approved == false && dialogRequested && allDevicesUpdated && !isSaving
}

internal class SyncProtocolUpgradeViewModel(
    approvedFlow: Flow<Boolean>,
    private val saveConfirmation: suspend (Boolean) -> Unit
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SyncProtocolUpgradeUiState())
    val uiState = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                approvedFlow.collect { approved ->
                    mutableUiState.update { state ->
                        state.copy(
                            approved = approved,
                            dialogRequested = state.dialogRequested && !approved,
                            hasError = false
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update { it.copy(approved = false, hasError = true) }
            }
        }
    }

    fun openConfirmation() {
        mutableUiState.update { state ->
            if (state.approved != false || state.isSaving || state.dialogRequested) state
            else state.copy(dialogRequested = true, allDevicesUpdated = false)
        }
    }

    fun requestSync(onApproved: () -> Unit) {
        val state = uiState.value
        if (state.isSaving) return
        if (state.approved == true) {
            onApproved()
        } else {
            mutableUiState.update { it.copy(dialogRequested = true, allDevicesUpdated = false) }
        }
    }

    fun setAllDevicesUpdated(updated: Boolean) {
        mutableUiState.update { state ->
            if (state.isSaving) state else state.copy(allDevicesUpdated = updated)
        }
    }

    fun dismissConfirmation(): Boolean {
        if (uiState.value.isSaving) return false
        mutableUiState.update {
            it.copy(dialogRequested = false, allDevicesUpdated = false, hasError = false)
        }
        return true
    }

    fun confirm() {
        val state = uiState.value
        if (!state.canConfirm) return
        mutableUiState.update { it.copy(isSaving = true, hasError = false) }
        viewModelScope.launch {
            try {
                saveConfirmation(state.allDevicesUpdated)
                coroutineContext.ensureActive()
                mutableUiState.update {
                    it.copy(approved = true, dialogRequested = false, allDevicesUpdated = false)
                }
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
