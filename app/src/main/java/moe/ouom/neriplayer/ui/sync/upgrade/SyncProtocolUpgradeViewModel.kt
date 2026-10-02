package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge

internal data class SyncProtocolUpgradeUiState(
    val approved: Boolean? = null,
    val challenge: SyncProtocolUpgradeChallenge? = null,
    val dialogRequested: Boolean = false,
    val allDevicesUpdated: Boolean = false,
    val isSaving: Boolean = false,
    val hasError: Boolean = false
) {
    val canConfirm: Boolean
        get() = challenge != null && approved == false && dialogRequested && allDevicesUpdated && !isSaving

    fun withPendingChallenge(pending: SyncProtocolUpgradeChallenge?): SyncProtocolUpgradeUiState {
        val unchanged = pending == challenge
        return copy(
            approved = pending == null,
            challenge = pending,
            dialogRequested = dialogRequested && pending != null && unchanged,
            allDevicesUpdated = allDevicesUpdated && unchanged,
            hasError = false
        )
    }
}

internal class SyncProtocolUpgradeViewModel(
    pendingFlow: Flow<List<SyncProtocolUpgradeChallenge>>,
    private val saveConfirmation: suspend (Boolean, SyncProtocolUpgradeChallenge) -> Unit,
    private val loadActiveTargets: suspend () -> Set<String>? = { null }
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SyncProtocolUpgradeUiState())
    private val reloads = MutableStateFlow(0L)
    val uiState = mutableUiState.asStateFlow()
    private var pendingChallenges = emptyList<SyncProtocolUpgradeChallenge>()
    private var activeTargets: Set<String>? = null
    private var retrySync: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            reloads.collectLatest {
                try {
                    pendingFlow.collect { pending ->
                        activeTargets = loadActiveTargets()
                        pendingChallenges = pending
                        updatePendingState()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    mutableUiState.update { it.copy(approved = false, challenge = null, hasError = true) }
                }
            }
        }
    }

    fun refreshTargets() {
        reloads.update { it + 1L }
    }

    private fun availableChallenges(): List<SyncProtocolUpgradeChallenge> =
        pendingChallenges.filter { activeTargets?.contains(it.targetId) != false }

    private fun updatePendingState() {
        val pending = availableChallenges()
        val previous = uiState.value.challenge
        mutableUiState.update { state ->
            val challenge = if (state.isSaving) state.challenge else
                state.challenge?.takeIf { it in pending } ?: pending.firstOrNull()
            state.withPendingChallenge(challenge)
        }
        if (uiState.value.challenge != previous) retrySync = null
    }

    fun openConfirmation(targetId: String? = null) {
        val challenge = availableChallenges().firstOrNull { targetId == null || it.targetId == targetId }
            ?: return
        mutableUiState.update { state ->
            if (state.isSaving || state.dialogRequested) state
            else state.copy(challenge = challenge, approved = false, dialogRequested = true, allDevicesUpdated = false)
        }
    }

    fun requestSync(onRequested: () -> Unit) {
        if (!uiState.value.isSaving) onRequested()
    }

    fun requestUpgrade(challenge: SyncProtocolUpgradeChallenge, onRetry: () -> Unit) {
        if (uiState.value.isSaving) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!isActiveChallenge(challenge) || uiState.value.isSaving) return@launch
                pendingChallenges = pendingChallenges.filterNot { it.targetId == challenge.targetId } + challenge
                retrySync = onRetry
                mutableUiState.update {
                    it.copy(approved = false, challenge = challenge, dialogRequested = true,
                        allDevicesUpdated = false, hasError = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update { it.copy(hasError = true) }
            }
        }
    }

    private suspend fun isActiveChallenge(challenge: SyncProtocolUpgradeChallenge): Boolean {
        activeTargets = loadActiveTargets()
        coroutineContext.ensureActive()
        return activeTargets?.contains(challenge.targetId) != false
    }

    fun setAllDevicesUpdated(updated: Boolean) {
        mutableUiState.update { state ->
            if (state.isSaving) state else state.copy(allDevicesUpdated = updated)
        }
    }

    fun dismissConfirmation(): Boolean {
        if (uiState.value.isSaving) return false
        retrySync = null
        mutableUiState.update {
            it.copy(dialogRequested = false, allDevicesUpdated = false, hasError = false)
        }
        return true
    }

    fun confirm() {
        val state = uiState.value
        if (!state.canConfirm) return
        val challenge = checkNotNull(state.challenge)
        val retry = retrySync
        mutableUiState.update { it.copy(isSaving = true, hasError = false) }
        viewModelScope.launch {
            var canRetry = false
            try {
                saveConfirmation(state.allDevicesUpdated, challenge)
                coroutineContext.ensureActive()
                canRetry = isActiveChallenge(challenge)
                pendingChallenges = pendingChallenges.filterNot { it == challenge }
                val next = availableChallenges().firstOrNull()
                mutableUiState.update {
                    it.copy(approved = next == null, challenge = next,
                        dialogRequested = false, allDevicesUpdated = false)
                }
                retrySync = null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update { it.copy(hasError = true) }
            } finally {
                mutableUiState.update { it.copy(isSaving = false) }
            }
            if (canRetry && !uiState.value.hasError) retry?.invoke()
        }
    }
}
