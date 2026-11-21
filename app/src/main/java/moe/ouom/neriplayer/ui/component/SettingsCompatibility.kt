package moe.ouom.neriplayer.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ThemeColorPicker(
    onColorChanged: (String) -> Unit,
    initialHex: String = "0061A4"
) {
    moe.ouom.neriplayer.ui.component.settings.ThemeColorPicker(
        onColorChanged = onColorChanged,
        initialHex = initialHex
    )
}

@Composable
fun LanguageSettingItem(
    modifier: Modifier = Modifier,
    onLanguageChanged: (moe.ouom.neriplayer.common.locale.LanguageManager.Language) -> Unit = {}
) {
    moe.ouom.neriplayer.ui.component.settings.LanguageSettingItem(
        modifier = modifier,
        onLanguageChanged = onLanguageChanged
    )
}
