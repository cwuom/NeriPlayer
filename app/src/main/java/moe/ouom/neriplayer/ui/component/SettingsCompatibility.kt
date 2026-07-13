package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun HsvPicker(
    onColorChanged: (String) -> Unit,
    initialHex: String = "0061A4"
) {
    moe.ouom.neriplayer.ui.component.settings.HsvPicker(
        onColorChanged = onColorChanged,
        initialHex = initialHex
    )
}

@Composable
fun LanguageSettingItem(
    modifier: Modifier = Modifier,
    onBeforeRestart: () -> Unit = {}
) {
    moe.ouom.neriplayer.ui.component.settings.LanguageSettingItem(
        modifier = modifier,
        onBeforeRestart = onBeforeRestart
    )
}

