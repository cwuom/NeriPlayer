package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics.coloros

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsAccessMode
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigBackend
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigCoordinator
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigException
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigJournal
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigResult
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsConfigStatus
import moe.ouom.neriplayer.core.player.privileged.coloros.ColorOsPrivilegedBackendFactory
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun ColorOsLockscreenLyricsSetting(
    repository: AutoSettingsRepository,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val enabled by repository.colorOsLockscreenLyricsEnabledFlow.collectAsState(initial = false)
    var showConfiguration by rememberSaveable { mutableStateOf(false) }
    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.lyrics.colorOsLockscreenLyricsEnabled,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiuixSettingsTextButton(onClick = { showConfiguration = true }) {
                    Text(stringResource(CoreCommonR.string.settings_coloros_configure))
                }
                MiuixSettingsSwitch(checked = enabled, onCheckedChange = { next ->
                    scope.launch { repository.setColorOsLockscreenLyricsEnabled(next) }
                })
            }
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = { showConfiguration = true },
    )
    if (showConfiguration) ColorOsConfigurationDialog(onDismiss = { showConfiguration = false })
}

@Composable
private fun ColorOsConfigurationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val journalFile = remember(context) { File(context.noBackupFilesDir, "coloros-lyrics/recovery.json") }
    var modeName by rememberSaveable { mutableStateOf(ColorOsAccessMode.SHIZUKU.name) }
    val mode = ColorOsAccessMode.valueOf(modeName)
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ColorOsConfigResult?>(null) }
    var restoreAvailable by remember { mutableStateOf(journalFile.exists()) }

    fun execute(action: ColorOsConfigurationAction) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) {
                    executeColorOsConfiguration(context, journalFile, mode, action)
                }
            } finally {
                restoreAvailable = journalFile.exists()
                busy = false
            }
        }
    }

    MiuixSettingsDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(CoreCommonR.string.settings_coloros_configuration_title)) },
        text = {
            ColorOsConfigurationContent(
                mode = mode,
                busy = busy,
                result = result,
                restoreAvailable = restoreAvailable,
                onModeChanged = { modeName = it.name },
                onExecute = ::execute,
            )
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(CoreCommonR.string.action_close))
            }
        },
    )
}

internal suspend fun executeColorOsConfiguration(
    context: Context,
    journalFile: File,
    mode: ColorOsAccessMode,
    action: ColorOsConfigurationAction,
): ColorOsConfigResult {
    return try {
        val coordinator = ColorOsConfigCoordinator(
            context.packageName,
            { loadColorOsSystemUiAsset(context) },
            ColorOsConfigJournal(journalFile),
        )
        val operation: suspend (ColorOsConfigBackend) -> ColorOsConfigResult = when (action) {
            ColorOsConfigurationAction.CHECK -> coordinator::inspect
            ColorOsConfigurationAction.APPLY -> coordinator::apply
            ColorOsConfigurationAction.RESTORE -> coordinator::restore
            ColorOsConfigurationAction.DISCARD -> return coordinator.discardRecovery()
        }
        // 先确认本机有目标资源，避免在不受支持的系统上请求提权
        loadColorOsSystemUiAsset(context)
        ColorOsPrivilegedBackendFactory.open(context, mode).use { backend -> operation(backend) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ColorOsConfigException) {
        ColorOsConfigResult(failure.status, canRestore = journalFile.exists(), detailCode = failure.detailCode)
    } catch (_: SecurityException) {
        ColorOsConfigResult(ColorOsConfigStatus.PERMISSION_DENIED, canRestore = journalFile.exists())
    } catch (_: Exception) {
        ColorOsConfigResult(ColorOsConfigStatus.ERROR, canRestore = journalFile.exists())
    }
}
