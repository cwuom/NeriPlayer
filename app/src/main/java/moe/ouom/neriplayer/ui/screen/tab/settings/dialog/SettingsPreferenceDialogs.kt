package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.dialog/SettingsPreferenceDialogs
 * Updated: 2026/3/23
 */

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun SettingsPreferenceDialogs(
    showDefaultStartDestinationDialog: Boolean,
    onShowDefaultStartDestinationDialogChange: (Boolean) -> Unit,
    homeStartAvailable: Boolean,
    effectiveDefaultStartDestination: String,
    onDefaultStartDestinationChange: (String) -> Unit,
    showColorPickerDialog: Boolean,
    onShowColorPickerDialogChange: (Boolean) -> Unit,
    seedColorHex: String,
    themeColorPalette: List<String>,
    onSeedColorChange: (String) -> Unit,
    onAddColorToPalette: (String) -> Unit,
    onRemoveColorFromPalette: (String) -> Unit,
    showDpiDialog: Boolean,
    onShowDpiDialogChange: (Boolean) -> Unit,
    uiDensityScale: Float,
    onUiDensityScaleChange: (Float) -> Unit
) {
    if (showDefaultStartDestinationDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { onShowDefaultStartDestinationDialogChange(false) },
            title = { Text(stringResource(R.string.settings_default_start_screen)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    val options = listOfNotNull(
                        ("home" to stringResource(R.string.nav_home)).takeUnless { !homeStartAvailable },
                        "explore" to stringResource(R.string.nav_explore),
                        "library" to stringResource(R.string.nav_library),
                        "settings" to stringResource(R.string.nav_settings)
                    )
                    options.forEach { (route, label) ->
                        MiuixSettingsChoiceRow(
                            title = label,
                            selected = route == effectiveDefaultStartDestination,
                            onClick = {
                                onDefaultStartDestinationChange(route)
                                onShowDefaultStartDestinationDialogChange(false)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(
                    onClick = { onShowDefaultStartDestinationDialogChange(false) },
                    text = { Text(stringResource(R.string.action_close)) }
                )
            }
        )
    }

    if (showColorPickerDialog) {
        ColorPickerDialog(
            currentHex = seedColorHex,
            palette = themeColorPalette,
            onDismiss = { onShowColorPickerDialogChange(false) },
            onColorSelected = { hex ->
                onSeedColorChange(hex)
                onShowColorPickerDialogChange(false)
            },
            onAddColor = onAddColorToPalette,
            onRemoveColor = onRemoveColorFromPalette
        )
    }

    if (showDpiDialog) {
        DpiSettingDialog(
            currentScale = uiDensityScale,
            onDismiss = { onShowDpiDialogChange(false) },
            onApply = { newScale ->
                onUiDensityScaleChange(newScale)
                onShowDpiDialogChange(false)
            }
        )
    }
}
