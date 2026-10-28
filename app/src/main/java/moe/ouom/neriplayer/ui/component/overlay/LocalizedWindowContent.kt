package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun localizedWindowContent(content: @Composable () -> Unit): @Composable () -> Unit {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return {
        // 新窗口保留调用方语言，避免格式化和操作反馈读取宿主旧文案
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            content = content
        )
    }
}

@Composable
internal fun LocalizedDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = localizedWindowContent(content)
    )
}
