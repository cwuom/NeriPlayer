package moe.ouom.neriplayer.ui.screen.tab.settings.about

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.net.toUri
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.util.platform.tryStartActivity

internal class SettingsAboutVersionTapOwner(
    private val tapCount: MutableIntState,
    private val devModeEnabled: Boolean,
    private val onEnableDevMode: () -> Unit,
    private val onMessage: (Int) -> Unit
) {
    fun onVersionClick() {
        if (devModeEnabled) {
            onMessage(CoreCommonR.string.debug_mode_enabled)
            return
        }
        val nextCount = tapCount.intValue + 1
        if (nextCount < 7) {
            tapCount.intValue = nextCount
            return
        }
        tapCount.intValue = 0
        onEnableDevMode()
        onMessage(CoreCommonR.string.debug_mode_opened)
    }
}

private class SettingsAboutPageActions(
    private val context: Context,
    private val resources: Resources,
    tapCount: MutableIntState,
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    private val onInlineMessageChange: (String) -> Unit,
    private val onShowMessage: (String) -> Unit
) {
    private val versionTap = SettingsAboutVersionTapOwner(
        tapCount = tapCount,
        devModeEnabled = devModeEnabled,
        onEnableDevMode = { onDevModeChange(true) },
        onMessage = { messageRes -> onInlineMessageChange(resources.getString(messageRes)) }
    )

    fun onVersionClick() = versionTap.onVersionClick()

    fun onCopyValue(value: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("settings_build_value", value))
        val copiedMessage = resources.getString(CoreCommonR.string.toast_copied)
        onInlineMessageChange(copiedMessage)
        onShowMessage(copiedMessage)
    }

    fun onOpenGitHubRepo() {
        if (!context.tryStartActivity(Intent(Intent.ACTION_VIEW, "https://github.com/cwuom/NeriPlayer".toUri()))) {
            onShowMessage(resources.getString(CoreCommonR.string.error_no_app_for_action))
        }
    }
}

@Composable
internal fun SettingsAboutPageContent(
    devModeEnabled: Boolean,
    onDevModeChange: (Boolean) -> Unit,
    onInlineMessageChange: (String) -> Unit,
    onShowMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val tapCount = remember { mutableIntStateOf(0) }
    val actions = SettingsAboutPageActions(
        context = context,
        resources = resources,
        tapCount = tapCount,
        devModeEnabled = devModeEnabled,
        onDevModeChange = onDevModeChange,
        onInlineMessageChange = onInlineMessageChange,
        onShowMessage = onShowMessage
    )
    SettingsAboutContent(
        devModeEnabled = devModeEnabled,
        onVersionClick = actions::onVersionClick,
        onCopyValue = actions::onCopyValue,
        onOpenGitHubRepo = actions::onOpenGitHubRepo
    )
}
