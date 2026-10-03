package moe.ouom.neriplayer.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@Composable
internal fun startupDebugBuildWarningPrompt(
    canShowDialog: Boolean,
    repository: DebugBuildWarningRepository? = null,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    isResumed: Boolean = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.RESUMED),
    dialogContent: @Composable (Boolean, Boolean, () -> Unit) -> Unit = { saving, failed, confirm ->
        DebugBuildWarningDialog(saving, failed, confirm)
    }
): Boolean {
    if (!isDebugBuild) return false

    val warningRepository = repository ?: run {
        val context = LocalContext.current.applicationContext
        remember(context) { DebugBuildWarningRepository(context) }
    }
    var acknowledged by remember(warningRepository) { mutableStateOf<Boolean?>(null) }
    var saving by remember(warningRepository) { mutableStateOf(false) }
    var saveFailed by remember(warningRepository) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(warningRepository) {
        warningRepository.acknowledgedFlow.retry(2) { it is IOException }.catch { error ->
            if (error !is IOException) throw error
            NPLogger.e("DebugBuildWarning", "读取 DEBUG 构建提示状态失败", error)
            emit(false)
        }.collect { acknowledged = it }
    }

    if (acknowledged == false && canShowDialog && isResumed) {
        dialogContent(saving, saveFailed) {
            if (!saving) {
                saving = true
                saveFailed = false
                scope.launch {
                    try {
                        warningRepository.acknowledge()
                        acknowledged = true
                    } catch (error: IOException) {
                        NPLogger.e("DebugBuildWarning", "保存 DEBUG 构建提示状态失败", error)
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
    return acknowledged != true
}

@Composable
private fun DebugBuildWarningDialog(
    saving: Boolean,
    saveFailed: Boolean,
    onConfirm: () -> Unit
) {
    DensityScaledAlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.debug_build_warning_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.debug_build_warning_message))
                if (saveFailed) {
                    Text(
                        stringResource(R.string.debug_build_warning_save_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onConfirm, enabled = !saving) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    )
}
