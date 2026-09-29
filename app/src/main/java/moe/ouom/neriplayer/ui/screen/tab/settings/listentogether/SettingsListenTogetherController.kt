package moe.ouom.neriplayer.ui.screen.tab.settings.listentogether

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.listentogether.ListenTogetherPreferences
import moe.ouom.neriplayer.listentogether.ListenTogetherSessionManager
import moe.ouom.neriplayer.listentogether.invite.ListenTogetherInvite
import moe.ouom.neriplayer.listentogether.invite.configuredListenTogetherBaseUrlOrNull
import moe.ouom.neriplayer.listentogether.invite.isDefaultListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.invite.parseListenTogetherInvite
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherBaseUrl
import moe.ouom.neriplayer.listentogether.invite.resolveListenTogetherInviteJoinBaseUrl
import moe.ouom.neriplayer.api.ltw.http.ListenTogetherApi
import moe.ouom.neriplayer.api.ltw.model.ListenTogetherServerTestResult
import moe.ouom.neriplayer.listentogether.protocol.model.session.ListenTogetherSessionState
import moe.ouom.neriplayer.listentogether.validation.validateListenTogetherNickname

internal fun isDefaultListenTogetherSettingsServer(input: String): Boolean =
    input.isBlank() || configuredListenTogetherBaseUrlOrNull(input)
        ?.let(::isDefaultListenTogetherBaseUrl) == true

internal fun validListenTogetherClipboardInvite(text: String?): String =
    text?.takeIf { parseListenTogetherInvite(it) != null }.orEmpty()

internal fun readSettingsListenTogetherClipboardText(context: Context): String? = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        ?: return@runCatching null
    if (clip.itemCount <= 0) return@runCatching null
    clip.getItemAt(0).coerceToText(context).toString()
}.getOrNull()

internal fun listenTogetherServerTestMessageId(
    result: ListenTogetherServerTestResult,
    usingDefaultServer: Boolean
): Int = when {
    result.ok && usingDefaultServer -> R.string.settings_listen_together_server_test_success_default
    result.ok -> R.string.settings_listen_together_server_test_success_custom
    result.message == "invalid_response" -> R.string.settings_listen_together_server_test_invalid
    else -> R.string.settings_listen_together_server_test_failed
}

internal fun normalizedSettingsListenTogetherServerInput(input: String): String? {
    val normalized = configuredListenTogetherBaseUrlOrNull(input)
    return if (input.isNotBlank() && normalized == null) null else normalized.orEmpty()
}

internal data class SettingsListenTogetherServerProbeRequest(
    val endpoint: String,
    val usingDefaultServer: Boolean
)

internal fun settingsListenTogetherServerProbeRequest(normalizedInput: String): SettingsListenTogetherServerProbeRequest =
    SettingsListenTogetherServerProbeRequest(
        endpoint = normalizedInput.ifEmpty { resolveListenTogetherBaseUrl(null) },
        usingDefaultServer = normalizedInput.isEmpty()
    )

@Composable
internal fun rememberSettingsListenTogetherController(
    preferences: ListenTogetherPreferences,
    api: ListenTogetherApi,
    sessionManager: ListenTogetherSessionManager,
    onMessage: (String) -> Unit
): SettingsListenTogetherController {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val observed = collectSettingsListenTogetherValues(preferences, sessionManager)
    val drafts = rememberSettingsListenTogetherDrafts()
    val currentOnMessage = rememberUpdatedState(onMessage)
    val dependencies = listOf(context, resources, preferences, api, sessionManager)
    val controller = remember(dependencies) {
        SettingsListenTogetherController(
            context = context,
            resources = resources,
            scope = scope,
            preferences = preferences,
            serverProbe = api::testServerAvailability,
            sessionManager = sessionManager,
            sessionState = observed.session,
            workerBaseUrl = observed.workerBaseUrl,
            workerBaseUrlInput = observed.workerBaseUrlInput,
            nickname = observed.nickname,
            serverInputState = drafts.serverInput,
            nicknameInputState = drafts.nicknameInput,
            onMessage = { currentOnMessage.value(it) }
        )
    }
    SyncSettingsListenTogetherDrafts(controller, observed)
    return controller
}

private class SettingsListenTogetherObservedValues(
    val session: State<ListenTogetherSessionState>,
    val workerBaseUrl: State<String>,
    val workerBaseUrlInput: State<String>,
    val nickname: State<String>
)

@Composable
private fun collectSettingsListenTogetherValues(
    preferences: ListenTogetherPreferences,
    sessionManager: ListenTogetherSessionManager
): SettingsListenTogetherObservedValues = SettingsListenTogetherObservedValues(
    session = sessionManager.sessionState.collectAsState(),
    workerBaseUrl = preferences.workerBaseUrlFlow.collectAsState(initial = ""),
    workerBaseUrlInput = preferences.workerBaseUrlInputFlow.collectAsState(initial = ""),
    nickname = preferences.nicknameFlow.collectAsState(initial = "")
)

private class SettingsListenTogetherDrafts(
    val serverInput: MutableState<String>,
    val nicknameInput: MutableState<String>
)

@Composable
private fun rememberSettingsListenTogetherDrafts(): SettingsListenTogetherDrafts =
    SettingsListenTogetherDrafts(
        serverInput = rememberSaveable { mutableStateOf("") },
        nicknameInput = rememberSaveable { mutableStateOf("") }
    )

@Composable
private fun SyncSettingsListenTogetherDrafts(
    controller: SettingsListenTogetherController,
    observed: SettingsListenTogetherObservedValues
) {
    LaunchedEffect(controller, observed.workerBaseUrlInput.value) {
        controller.syncServerInput()
    }
    LaunchedEffect(controller, observed.nickname.value, controller.showNicknameDialog) {
        controller.syncNicknameInput()
    }
}

internal class SettingsListenTogetherController(
    private val context: Context,
    private val resources: Resources,
    private val scope: CoroutineScope,
    private val preferences: ListenTogetherPreferences,
    private val serverProbe: suspend (String) -> ListenTogetherServerTestResult,
    private val sessionManager: ListenTogetherSessionManager,
    private val sessionState: State<ListenTogetherSessionState>,
    private val workerBaseUrl: State<String>,
    private val workerBaseUrlInput: State<String>,
    private val nickname: State<String>,
    serverInputState: MutableState<String>,
    nicknameInputState: MutableState<String>,
    private val onMessage: (String) -> Unit
) {
    var showResetUuidDialog by mutableStateOf(false)
        private set
    var showServerDialog by mutableStateOf(false)
        private set
    var showNicknameDialog by mutableStateOf(false)
        private set
    var showJoinDialog by mutableStateOf(false)
        private set
    var serverInput by serverInputState
        private set
    var nicknameInput by nicknameInputState
        private set
    var nicknameError by mutableStateOf<String?>(null)
        private set
    var inviteInput by mutableStateOf("")
        private set
    var inviteError by mutableStateOf<String?>(null)
        private set
    var joining by mutableStateOf(false)
        private set
    var serverTesting by mutableStateOf(false)
        private set
    var serverTestMessage by mutableStateOf<String?>(null)
        private set

    val openJoinAction: () -> Unit = ::openJoinDialog
    val openServerAction: () -> Unit = ::openServerDialog
    val openNicknameAction: () -> Unit = ::openNicknameDialog
    val openResetUuidAction: () -> Unit = ::openResetUuidDialog
    val dismissJoinAction: () -> Unit = ::dismissJoinDialog
    val dismissServerAction: () -> Unit = ::dismissServerDialog
    val dismissNicknameAction: () -> Unit = ::dismissNicknameDialog
    val dismissResetUuidAction: () -> Unit = ::dismissResetUuidDialog
    val updateInviteAction: (String) -> Unit = ::updateInviteInput
    val updateServerAction: (String) -> Unit = ::updateServerInput
    val updateNicknameAction: (String) -> Unit = ::updateNicknameInput
    val testServerAction: () -> Unit = ::testServer
    val resetServerInputAction: () -> Unit = ::resetServerInput

    val isInRoom: Boolean get() = !sessionState.value.roomId.isNullOrBlank()
    val isUsingDefaultServer: Boolean get() = isDefaultListenTogetherSettingsServer(serverInput)
    val currentNickname: String get() = nickname.value

    fun syncServerInput() {
        if (serverInput != workerBaseUrlInput.value) serverInput = workerBaseUrlInput.value
    }

    fun syncNicknameInput() {
        if (!showNicknameDialog) {
            nicknameInput = nickname.value
            nicknameError = null
        }
    }

    fun openJoinDialog() {
        if (isInRoom) return
        inviteInput = validListenTogetherClipboardInvite(readSettingsListenTogetherClipboardText(context))
        inviteError = null
        showJoinDialog = true
    }

    fun updateInviteInput(value: String) {
        inviteInput = value
        inviteError = null
    }

    fun dismissJoinDialog() {
        if (joining) return
        showJoinDialog = false
        inviteInput = ""
        inviteError = null
    }

    fun confirmJoin() {
        if (joining) return
        val invite = parseListenTogetherInvite(inviteInput)
        if (invite == null) {
            inviteError = resources.getString(R.string.settings_listen_together_join_invite_invalid)
            return
        }
        if (isInRoom) {
            inviteError = resources.getString(R.string.settings_listen_together_join_room_disabled)
            return
        }
        scope.launch { joinRoom(invite) }
    }

    private suspend fun joinRoom(invite: ListenTogetherInvite) {
        joining = true
        inviteError = null
        try {
            val joinBaseUrl = resolveListenTogetherInviteJoinBaseUrl(
                invite = invite,
                savedBaseUrlInput = workerBaseUrlInput.value,
                savedBaseUrl = workerBaseUrl.value
            )
            sessionManager.joinRoom(
                baseUrl = joinBaseUrl,
                roomId = invite.roomId,
                userUuid = preferences.getOrCreateUserUuid(),
                nickname = preferences.getOrCreateNickname(),
                joinSecret = invite.joinSecret
            )
            sessionManager.connectWebSocket()
            showJoinDialog = false
            inviteInput = ""
        } catch (error: Exception) {
            inviteError = error.message ?: error.javaClass.simpleName
        } finally {
            joining = false
        }
    }

    fun openServerDialog() {
        serverTestMessage = null
        showServerDialog = true
    }

    fun updateServerInput(value: String) {
        serverInput = value
        serverTestMessage = null
    }

    fun resetServerInput() {
        serverInput = ""
        serverTestMessage = resources.getString(R.string.settings_listen_together_server_reset_done)
    }

    fun dismissServerDialog() {
        if (serverTesting) return
        showServerDialog = false
        serverInput = workerBaseUrlInput.value
        serverTestMessage = null
    }

    fun testServer() {
        scope.launch {
            val normalized = validatedServerInput() ?: return@launch
            testValidatedServer(normalized)
        }
    }

    internal suspend fun testValidatedServer(normalized: String) {
        val request = settingsListenTogetherServerProbeRequest(normalized)
        serverTesting = true
        try {
            val result = serverProbe(request.endpoint)
            serverTestMessage = formatServerTestResult(result, request.usingDefaultServer)
        } finally {
            serverTesting = false
        }
    }

    private fun formatServerTestResult(result: ListenTogetherServerTestResult, usingDefault: Boolean): String {
        val messageId = listenTogetherServerTestMessageId(result, usingDefault)
        return if (messageId == R.string.settings_listen_together_server_test_failed) {
            resources.getString(messageId, result.message)
        } else {
            resources.getString(messageId)
        }
    }

    fun applyServer() {
        scope.launch {
            val normalized = validatedServerInput() ?: return@launch
            preferences.setWorkerBaseUrl(normalized)
            preferences.setWorkerBaseUrlInput(normalized)
            serverInput = normalized
            showServerDialog = false
            serverTestMessage = null
            showMessage(R.string.settings_listen_together_server_saved)
        }
    }

    private fun validatedServerInput(): String? {
        val normalized = normalizedSettingsListenTogetherServerInput(serverInput)
        if (normalized == null) {
            serverTestMessage = resources.getString(R.string.settings_listen_together_server_input_invalid)
        }
        return normalized
    }

    fun openNicknameDialog() {
        if (isInRoom) return
        nicknameInput = nickname.value
        nicknameError = null
        showNicknameDialog = true
    }

    fun updateNicknameInput(value: String) {
        nicknameInput = value.take(24)
        nicknameError = validateListenTogetherNickname(nicknameInput)?.format(context)
    }

    fun dismissNicknameDialog() {
        showNicknameDialog = false
        nicknameInput = nickname.value
        nicknameError = null
    }

    fun applyNickname() {
        val value = nicknameInput.trim()
        val validationError = validateListenTogetherNickname(value)
        if (validationError != null) {
            nicknameError = validationError.format(context)
            return
        }
        scope.launch {
            preferences.setNickname(value)
            showNicknameDialog = false
            nicknameError = null
            showMessage(R.string.settings_listen_together_default_nickname_saved)
        }
    }

    fun openResetUuidDialog() {
        if (!isInRoom) showResetUuidDialog = true
    }

    fun dismissResetUuidDialog() {
        showResetUuidDialog = false
    }

    fun resetUuid() {
        scope.launch {
            preferences.resetUserUuid()
            showResetUuidDialog = false
            showMessage(R.string.listen_together_reset_uuid_done)
        }
    }

    private fun showMessage(messageId: Int) {
        onMessage(resources.getString(messageId))
    }
}
