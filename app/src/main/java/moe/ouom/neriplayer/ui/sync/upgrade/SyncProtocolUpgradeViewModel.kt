package moe.ouom.neriplayer.ui.sync.upgrade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.StringRes
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.api.sync.github.GitHubApiException
import moe.ouom.neriplayer.api.sync.github.GitHubContentConflictException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAccessDeniedException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiException
import moe.ouom.neriplayer.api.sync.webdav.WebDavArchiveLeaseLostException
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.api.sync.webdav.WebDavContentConflictException
import moe.ouom.neriplayer.api.sync.webdav.WebDavDirectoryNotFoundException
import moe.ouom.neriplayer.api.sync.webdav.WebDavMissingConcurrencyTokenException
import moe.ouom.neriplayer.api.sync.webdav.WebDavNotDirectoryException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.common.R as CoreCommonR

internal data class SyncProtocolUpgradeError(
    @StringRes val messageRes: Int,
    val httpStatus: Int? = null
)

internal data class SyncProtocolUpgradeUiState(
    val startupRegistrationComplete: Boolean = false,
    val approved: Boolean? = null,
    val challenge: SyncProtocolUpgradeChallenge? = null,
    val startupTargetId: String? = null,
    val dialogRequested: Boolean = false,
    val allDevicesUpdated: Boolean = false,
    val isSaving: Boolean = false,
    val isSyncing: Boolean = false,
    val syncResult: SyncResult? = null,
    val hasError: Boolean = false,
    val errorDetail: SyncProtocolUpgradeError? = null
) {
    val targetId: String?
        get() = challenge?.targetId ?: startupTargetId

    val canConfirm: Boolean
        get() = targetId != null && approved == false && dialogRequested && allDevicesUpdated && !isSaving

    fun withPendingChallenge(pending: SyncProtocolUpgradeChallenge?, startup: String? = null): SyncProtocolUpgradeUiState {
        val hasPending = pending != null || startup != null
        if (!hasPending || pending != challenge || startup != startupTargetId) {
            return copy(approved = !hasPending, challenge = pending, startupTargetId = startup,
                dialogRequested = false, allDevicesUpdated = false, hasError = false, errorDetail = null)
        }
        return copy(approved = false, hasError = false, errorDetail = null)
    }
}

internal class SyncProtocolUpgradeViewModel(
    pendingFlow: Flow<List<SyncProtocolUpgradeChallenge>>,
    private val saveConfirmation: suspend (Boolean, SyncProtocolUpgradeChallenge) -> Unit,
    private val loadActiveTargets: suspend () -> Set<String>? = { null },
    private val startupTargetsFlow: Flow<Set<String>> = flowOf(emptySet()),
    private val initializeStartupTargets: suspend () -> Unit = {},
    private val performImmediateSync: suspend (String, Boolean) -> Result<SyncResult> = { _, _ ->
        Result.success(SyncResult(true, ""))
    },
    private val logFailure: (String) -> Unit = { detail ->
        NPLogger.w("SyncProtocolUpgrade", detail)
    }
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(SyncProtocolUpgradeUiState())
    private val reloads = MutableStateFlow(0L)
    val uiState = mutableUiState.asStateFlow()
    private var pendingChallenges = emptyList<SyncProtocolUpgradeChallenge>()
    private var startupTargets = emptySet<String>()
    private var activeTargets: Set<String>? = null
    private var retrySync: (() -> Unit)? = null
    private var savedChallenge: SyncProtocolUpgradeChallenge? = null
    private var pendingReadFailed = false

    init {
        viewModelScope.launch {
            reloads.collectLatest {
                try {
                    initializeStartupTargets()
                    pendingFlow.combine(startupTargetsFlow) { pending, startup -> pending to startup }
                        .collect { (pending, startup) ->
                            activeTargets = loadActiveTargets()
                            pendingReadFailed = false
                            pendingChallenges = pending
                            startupTargets = startup
                            updatePendingState()
                            mutableUiState.update { it.copy(startupRegistrationComplete = true) }
                        }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    pendingReadFailed = true
                    val detail = recordFailure(error)
                    mutableUiState.update { state ->
                        if (state.dialogRequested) state.copy(hasError = true, errorDetail = detail)
                        else state.copy(approved = false, challenge = null, startupTargetId = null,
                            hasError = true, errorDetail = detail)
                    }
                }
            }
        }
    }

    fun refreshTargets() {
        mutableUiState.update { state ->
            if (state.startupRegistrationComplete) state else state.copy(hasError = false, errorDetail = null)
        }
        reloads.update { it + 1L }
    }

    private fun availableChallenges(): List<SyncProtocolUpgradeChallenge> =
        pendingChallenges.filter { activeTargets?.contains(it.targetId) != false }

    private fun availableStartupTargets(): Set<String> =
        startupTargets.filterTo(mutableSetOf()) { activeTargets?.contains(it) != false }

    private fun pendingRequest(targetId: String? = null): Pair<SyncProtocolUpgradeChallenge?, String?> {
        val pending = availableChallenges()
        val startup = availableStartupTargets()
        val target = targetId ?: pending.firstOrNull()?.targetId ?: startup.minOrNull()
            ?: return null to null
        val challenge = pending.firstOrNull { it.targetId == target }
        if (challenge != null) return challenge to null
        return null to target.takeIf { it in startup }
    }

    private fun updatePendingState() {
        if (uiState.value.isSaving) return
        val previous = uiState.value.challenge to uiState.value.startupTargetId
        val retained = pendingRequest(uiState.value.targetId)
        val (challenge, startup) = if (retained.first != null || retained.second != null) retained else pendingRequest()
        mutableUiState.update { state ->
            state.withPendingChallenge(challenge, startup)
        }
        if ((challenge to startup) != previous) retrySync = null
    }

    fun openConfirmation(targetId: String? = null) {
        val (challenge, startup) = pendingRequest(targetId)
        if (challenge == null && startup == null) return
        mutableUiState.update { state ->
            if (state.isSaving || state.dialogRequested) state
            else state.copy(challenge = challenge, startupTargetId = startup, approved = false,
                dialogRequested = true, allDevicesUpdated = false, hasError = false, errorDetail = null)
        }
    }

    fun requestSync(onRequested: () -> Unit) {
        if (!uiState.value.isSaving) onRequested()
    }

    fun requestSync(targetId: String?, onRequested: () -> Unit) {
        if (uiState.value.isSaving || uiState.value.dialogRequested) return
        if (targetId == null) {
            onRequested()
            return
        }
        val (challenge, startup) = pendingRequest(targetId)
        if (challenge == null && startup == null) onRequested()
        else {
            openConfirmation(targetId)
            if (challenge != null) retrySync = onRequested
        }
    }

    fun requestUpgrade(challenge: SyncProtocolUpgradeChallenge, onRetry: () -> Unit) {
        if (uiState.value.isSaving) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!isActiveChallenge(challenge) || uiState.value.isSaving) return@launch
                pendingChallenges = pendingChallenges.filterNot { it.targetId == challenge.targetId } + challenge
                pendingReadFailed = false
                savedChallenge = null
                retrySync = onRetry
                mutableUiState.update {
                    it.copy(approved = false, challenge = challenge, startupTargetId = null, dialogRequested = true,
                        allDevicesUpdated = false, hasError = false, errorDetail = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val detail = recordFailure(error)
                mutableUiState.update { it.copy(hasError = true, errorDetail = detail) }
            }
        }
    }

    private suspend fun isActiveChallenge(challenge: SyncProtocolUpgradeChallenge): Boolean {
        return isActiveTarget(challenge.targetId)
    }

    private suspend fun isActiveTarget(targetId: String): Boolean {
        activeTargets = loadActiveTargets()
        coroutineContext.ensureActive()
        return activeTargets?.contains(targetId) != false
    }

    fun setAllDevicesUpdated(updated: Boolean) {
        mutableUiState.update { state ->
            if (state.isSaving) state else state.copy(allDevicesUpdated = updated)
        }
    }

    fun dismissConfirmation(): Boolean {
        if (uiState.value.isSaving) return false
        retrySync = null
        savedChallenge = null
        mutableUiState.update {
            it.copy(dialogRequested = false, allDevicesUpdated = false, hasError = false, errorDetail = null)
        }
        updatePendingState()
        return true
    }

    fun confirm() {
        val state = uiState.value
        if (!state.canConfirm) return
        val targetId = checkNotNull(state.targetId)
        val retry = retrySync
        mutableUiState.update { it.copy(isSaving = true, hasError = false, errorDetail = null) }
        if (pendingReadFailed) refreshTargets()
        viewModelScope.launch {
            var canRetry = false
            try {
                canRetry = applyConfirmation(state, targetId, retry != null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleConfirmationFailure(error, targetId)
            } finally {
                mutableUiState.update { it.copy(isSaving = false, isSyncing = false) }
            }
            if (canRetry) retry?.invoke()
        }
    }

    private suspend fun applyConfirmation(state: SyncProtocolUpgradeUiState, targetId: String, hasManualRetry: Boolean): Boolean {
        persistUpgradeDecision(state, targetId)
        val active = isActiveTarget(targetId)
        if (hasManualRetry) {
            finishConfirmation(state, null)
            return active
        }
        check(active) { "Sync target changed" }
        synchronizeConfirmedTarget(state, targetId)
        return false
    }

    private suspend fun persistUpgradeDecision(state: SyncProtocolUpgradeUiState, targetId: String) {
        check(isActiveTarget(targetId)) { "Sync target changed" }
        state.challenge?.let { persistDetectedConfirmation(it, state.allDevicesUpdated) }
    }

    private suspend fun persistDetectedConfirmation(challenge: SyncProtocolUpgradeChallenge, allDevicesUpdated: Boolean) {
        if (savedChallenge == challenge) return
        saveConfirmation(allDevicesUpdated, challenge)
        coroutineContext.ensureActive()
        savedChallenge = challenge
    }

    private suspend fun synchronizeConfirmedTarget(state: SyncProtocolUpgradeUiState, targetId: String) {
        mutableUiState.update { it.copy(isSyncing = true) }
        val result = performImmediateSync(targetId, state.challenge == null)
        coroutineContext.ensureActive()
        val completed = result.getOrThrow()
        if (!completed.success) throw IOException("Sync did not complete")
        finishConfirmation(state, completed)
    }

    private fun handleConfirmationFailure(error: Exception, targetId: String) {
        if (error is SyncProtocolUpgradeRequiredException && error.challenge?.targetId == targetId) {
            replaceChallenge(checkNotNull(error.challenge))
        } else {
            val detail = recordFailure(error)
            mutableUiState.update { it.copy(hasError = true, errorDetail = detail) }
        }
    }

    private fun recordFailure(error: Exception): SyncProtocolUpgradeError? {
        // 异常正文可能包含服务地址和账号信息，只保留类型和状态码
        val type = error.javaClass.simpleName
        val status = when (error) {
            is WebDavApiException -> error.statusCode
            is GitHubApiException -> error.statusCode
            is WebDavAuthException, is TokenExpiredException -> 401
            is WebDavDirectoryNotFoundException -> 404
            else -> null
        }
        val detail = if (status == null) type else "$type (HTTP $status)"
        logFailure(detail)
        val message = failureMessage(error, status) ?: return null
        return SyncProtocolUpgradeError(message, status)
    }

    @StringRes
    private fun failureMessage(error: Exception, status: Int?): Int? {
        FailureMessages[error.javaClass]?.let { message ->
            return if (error is WebDavContentConflictException && status == 423)
                CoreCommonR.string.webdav_sync_locked else message
        }
        return when {
            error is WebDavApiException || error is GitHubApiException -> CoreCommonR.string.sync_request_failed
            isNetworkFailure(error) -> CoreCommonR.string.comment_error_network
            else -> null
        }
    }

    private fun isNetworkFailure(error: Exception): Boolean = error is UnknownHostException ||
        error is SocketException || error is SocketTimeoutException || error is SSLException

    private fun replaceChallenge(challenge: SyncProtocolUpgradeChallenge) {
        pendingChallenges = pendingChallenges.filterNot { it.targetId == challenge.targetId } + challenge
        retrySync = null
        savedChallenge = null
        mutableUiState.update {
            it.copy(approved = false, challenge = challenge, startupTargetId = null, dialogRequested = true,
                allDevicesUpdated = false, hasError = false, errorDetail = null)
        }
    }

    private fun finishConfirmation(state: SyncProtocolUpgradeUiState, result: SyncResult?) {
        pendingChallenges = pendingChallenges.filterNot { it == state.challenge }
        startupTargets = startupTargets - checkNotNull(state.targetId)
        val (next, startup) = pendingRequest()
        retrySync = null
        mutableUiState.update {
            it.copy(approved = next == null && startup == null, challenge = next, startupTargetId = startup,
                dialogRequested = false, allDevicesUpdated = false, hasError = false, errorDetail = null,
                syncResult = result)
        }
    }

    fun clearSyncResult() {
        mutableUiState.update { it.copy(syncResult = null) }
    }

    private companion object {
        val FailureMessages = mapOf<Class<out Exception>, Int>(
            WebDavAuthException::class.java to CoreCommonR.string.webdav_auth_failed,
            TokenExpiredException::class.java to CoreCommonR.string.github_token_expired_message,
            WebDavDirectoryNotFoundException::class.java to CoreCommonR.string.webdav_directory_missing,
            WebDavNotDirectoryException::class.java to CoreCommonR.string.webdav_sync_not_directory,
            WebDavAccessDeniedException::class.java to CoreCommonR.string.webdav_access_denied,
            WebDavMissingConcurrencyTokenException::class.java to CoreCommonR.string.webdav_sync_missing_condition,
            WebDavArchiveLeaseLostException::class.java to CoreCommonR.string.webdav_sync_lock_lost,
            WebDavContentConflictException::class.java to CoreCommonR.string.sync_remote_changed,
            GitHubContentConflictException::class.java to CoreCommonR.string.sync_remote_changed
        )
    }
}
