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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.dialog/SettingsCustomizationDialogs
 * Updated: 2026/3/23
 */

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.ThemeDefaults
import moe.ouom.neriplayer.ui.component.HsvPicker
import moe.ouom.neriplayer.util.HapticTextButton
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ColorPickerDialog(
    currentHex: String,
    palette: List<String>,
    onDismiss: () -> Unit,
    onColorSelected: (String) -> Unit,
    onAddColor: (String) -> Unit,
    onRemoveColor: (String) -> Unit
) {
    var pickedHex by remember(currentHex) { mutableStateOf(currentHex.uppercase(Locale.ROOT)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_select_color)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        palette.forEach { hex ->
                            val isPreset = ThemeDefaults.PRESET_SET.contains(hex.uppercase(Locale.ROOT))
                            ColorPickerItem(
                                hex = hex,
                                isSelected = currentHex.equals(hex, ignoreCase = true),
                                onClick = {
                                    pickedHex = hex.uppercase(Locale.ROOT)
                                    onColorSelected(hex)
                                },
                                onRemove = if (!isPreset) ({ onRemoveColor(hex) }) else null
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_custom_color),
                        style = MaterialTheme.typography.titleSmall
                    )
                    HsvPicker(
                        initialHex = currentHex,
                        onColorChanged = { pickedHex = it.uppercase(Locale.ROOT) }
                    )
                }

                val existsInPalette = palette.any { it.equals(pickedHex, ignoreCase = true) }
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val useVerticalButtons = maxWidth < 360.dp
                    if (useVerticalButtons) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onAddColor(pickedHex) },
                                enabled = !existsInPalette && !ThemeDefaults.PRESET_SET.contains(pickedHex),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_add_to_palette),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                            Button(
                                onClick = { onColorSelected(pickedHex) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_apply_color),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { onAddColor(pickedHex) },
                                enabled = !existsInPalette && !ThemeDefaults.PRESET_SET.contains(pickedHex),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_add_to_palette),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                            Button(
                                onClick = { onColorSelected(pickedHex) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_apply_color),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }

                val deletableCount = palette.count {
                    !ThemeDefaults.PRESET_SET.contains(it.uppercase(Locale.ROOT))
                }
                if (deletableCount > 0) {
                    Text(
                        text = stringResource(R.string.settings_color_picker_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorPickerItem(
    hex: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null
) {
    val color = Color(("#$hex").toColorInt())
    val clickableModifier = if (onRemove != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onRemove)
    } else {
        Modifier.clickable(onClick = onClick)
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color)
            .then(clickableModifier),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            val contentColor = if (ColorUtils.calculateLuminance(color.toArgb()) > 0.5) {
                Color.Black
            } else {
                Color.White
            }
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.common_selected),
                tint = contentColor
            )
        }

        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.settings_delete_color),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** DPI 设置对话框 */
@SuppressLint("DefaultLocale")
@Composable
@Suppress("AssignedValueIsNeverRead")
internal fun DpiSettingDialog(
    currentScale: Float,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit
) {
    var sliderValue by remember(currentScale) { mutableFloatStateOf(currentScale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_ui_scale)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = String.format("%.2fx", sliderValue),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0.6f..1.2f,
                    steps = 11,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_restart_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { onApply(sliderValue) }) {
                Text(stringResource(R.string.action_apply))
            }
        },
        dismissButton = {
            Row {
                HapticTextButton(onClick = { sliderValue = 1.0f }) {
                    Text(stringResource(R.string.action_reset))
                }
                HapticTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    )
}
