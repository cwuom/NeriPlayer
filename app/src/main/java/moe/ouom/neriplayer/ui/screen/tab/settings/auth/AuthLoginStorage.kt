package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

internal data class AuthLoginStorageStatus(
    val requiresCompatibilityWarning: Boolean,
    val isPersistent: Boolean
)

@Composable
internal fun rememberAuthLoginStorageStatus(platform: SettingsAccountPlatform): AuthLoginStorageStatus? {
    val status by produceState<AuthLoginStorageStatus?>(initialValue = null, platform) {
        value = withContext(Dispatchers.IO) {
            try {
                when (platform) {
                    SettingsAccountPlatform.Bilibili -> AppContainer.biliCookieRepo.let {
                        AuthLoginStorageStatus(it.requiresCompatibilityStorageWarning, it.isAuthStoragePersistent)
                    }
                    SettingsAccountPlatform.Netease -> AppContainer.neteaseCookieRepo.let {
                        AuthLoginStorageStatus(it.requiresCompatibilityStorageWarning, it.isAuthStoragePersistent)
                    }
                    SettingsAccountPlatform.YouTube -> AppContainer.youtubeAuthRepo.let {
                        AuthLoginStorageStatus(it.requiresCompatibilityStorageWarning, it.isAuthStoragePersistent)
                    }
                    SettingsAccountPlatform.QqMusic -> AuthLoginStorageStatus(false, false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AuthLoginStorageStatus(false, false)
            }
        }
    }
    return status
}

internal class AuthLoginStorageGate {
    var pendingAction by mutableStateOf<(() -> Unit)?>(null)
        private set
    var storageUnavailable by mutableStateOf(false)
        private set
    var submitted by mutableStateOf(false)
        private set

    fun request(requiresWarning: Boolean, isPersistent: Boolean, action: () -> Unit) {
        if (submitted || pendingAction != null || storageUnavailable) return
        when {
            !isPersistent -> storageUnavailable = true
            requiresWarning -> pendingAction = action
            else -> submit(action)
        }
    }

    fun confirm(isPersistent: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (isPersistent) submit(action) else storageUnavailable = true
    }

    fun dismiss() {
        pendingAction = null
        storageUnavailable = false
    }

    fun allowRetry() {
        submitted = false
    }

    private fun submit(action: () -> Unit) {
        if (submitted) return
        submitted = true
        action()
    }
}

@Composable
internal fun AuthLoginStorageDialogs(gate: AuthLoginStorageGate, isPersistent: Boolean) {
    if (gate.pendingAction != null) {
        MiuixSettingsDialog(
            onDismissRequest = gate::dismiss,
            title = { Text(stringResource(CoreCommonR.string.auth_storage_compatibility_title)) },
            text = { Text(stringResource(CoreCommonR.string.auth_storage_compatibility_message)) },
            confirmButton = {
                MiuixSettingsTextButton(onClick = { gate.confirm(isPersistent) }) {
                    Text(stringResource(CoreCommonR.string.auth_storage_compatibility_continue))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = gate::dismiss) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
    if (gate.storageUnavailable) {
        MiuixSettingsDialog(
            onDismissRequest = gate::dismiss,
            title = { Text(stringResource(CoreCommonR.string.auth_storage_unavailable_title)) },
            text = { Text(stringResource(CoreCommonR.string.auth_storage_unavailable_message)) },
            confirmButton = {
                MiuixSettingsTextButton(onClick = gate::dismiss) {
                    Text(stringResource(CoreCommonR.string.action_ok))
                }
            }
        )
    }
}
