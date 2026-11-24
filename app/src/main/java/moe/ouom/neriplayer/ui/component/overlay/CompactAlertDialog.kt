package moe.ouom.neriplayer.ui.component.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.DialogProperties

private val CompactWindowHeight = 480.dp

@Composable
internal fun useCompactAlertLayout(): Boolean = LocalWindowInfo.current.containerDpSize.run {
    isSpecified && height > 0.dp && height < CompactWindowHeight && width >= CompactWindowHeight
}

@Composable
internal fun CompactAlertContent(
    icon: @Composable (() -> Unit)?,
    title: @Composable (() -> Unit)?,
    text: @Composable () -> Unit,
    iconColor: Color,
    titleColor: Color,
    textColor: Color,
    actions: @Composable () -> Unit
) {
    // 横屏键盘上方可能只容得下一行输入框，操作与正文分别使用窗口的完整高度
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.weight(0.4f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null || title != null) {
                Row(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (icon != null) CompositionLocalProvider(LocalContentColor provides iconColor, content = icon)
                    if (title != null) CompositionLocalProvider(LocalContentColor provides titleColor) {
                        ProvideTextStyle(MaterialTheme.typography.titleMedium, content = title)
                    }
                }
            }
            Box(Modifier.verticalScroll(rememberScrollState())) { actions() }
        }
        Box(Modifier.weight(0.6f)) {
            CompositionLocalProvider(LocalContentColor provides textColor) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium, content = text)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactMaterialAlertDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    modifier: Modifier,
    shape: Shape,
    containerColor: Color,
    tonalElevation: Dp,
    content: @Composable () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
        content = localizedWindowContent {
            ApplyOverlayWindowNavigationBarPolicy()
            Surface(shape = shape, color = containerColor, tonalElevation = tonalElevation, content = content)
        }
    )
}

@Composable
internal fun CompactMaterialActions(
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)?,
    neutralButton: @Composable (() -> Unit)?
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        dismissButton?.invoke()
        neutralButton?.invoke()
        confirmButton()
    }
}
