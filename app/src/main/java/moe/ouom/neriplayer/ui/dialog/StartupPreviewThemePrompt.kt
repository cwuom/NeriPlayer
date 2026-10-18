package moe.ouom.neriplayer.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

@Composable
internal fun startupPreviewThemePrompt(
    canShowDialog: Boolean,
    repository: PreviewThemePromptRepository? = null,
    isResumed: Boolean = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
        .isAtLeast(Lifecycle.State.RESUMED)
): Boolean {
    val context = LocalContext.current.applicationContext
    val promptRepository = repository ?: remember(context) { PreviewThemePromptRepository(context) }
    var shouldPrompt by remember(promptRepository) { mutableStateOf<Boolean?>(null) }
    var saving by remember(promptRepository) { mutableStateOf(false) }
    var saveFailed by remember(promptRepository) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(promptRepository) {
        promptRepository.shouldPromptFlow.retry(2) { it is IOException }.catch { error ->
            if (error !is IOException) throw error
            NPLogger.e("PreviewThemePrompt", "读取预览版主题询问状态失败", error)
            emit(false)
        }.collect { shouldPrompt = it }
    }

    if (canShowDialog && isResumed && (shouldPrompt == true || saving)) {
        PreviewThemePromptDialog(saving, saveFailed) { enable ->
            if (!saving) {
                saving = true
                saveFailed = false
                scope.launch {
                    try {
                        promptRepository.answer(enable)
                        shouldPrompt = false
                    } catch (error: IOException) {
                        NPLogger.e("PreviewThemePrompt", "保存预览版主题选择失败", error)
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
    return shouldPrompt != false || saving
}

@Composable
private fun PreviewThemePromptDialog(
    saving: Boolean,
    saveFailed: Boolean,
    onAnswer: (Boolean) -> Unit
) {
    DensityScaledAlertDialog(
        onDismissRequest = { if (!saving) onAnswer(false) },
        title = { Text(stringResource(R.string.preview_theme_prompt_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.preview_theme_prompt_message))
                if (saveFailed) {
                    Text(
                        stringResource(R.string.preview_theme_prompt_save_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { onAnswer(true) }, enabled = !saving) {
                Text(stringResource(R.string.preview_theme_prompt_enable))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = { onAnswer(false) }, enabled = !saving) {
                Text(stringResource(R.string.preview_theme_prompt_decline))
            }
        }
    )
}
