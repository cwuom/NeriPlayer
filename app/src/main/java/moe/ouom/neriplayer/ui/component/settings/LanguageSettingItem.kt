package moe.ouom.neriplayer.ui.component.settings

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
 * File: moe.ouom.neriplayer.ui.component/LanguageSettingItem
 * Updated: 2026/3/23
 */


import moe.ouom.neriplayer.ui.settings.SettingRowIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.*
import moe.ouom.neriplayer.ui.component.overlay.ScrollableDialogColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.common.locale.getDisplayName
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

private val LanguageSettingItemShape = RoundedCornerShape(18.dp)

/**
 * 语言选择对话框
 * Language selection dialog
 */
@Composable
fun LanguageSettingItem(
    modifier: Modifier = Modifier,
    onLanguageChanged: (LanguageManager.Language) -> Unit = {}
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(LanguageManager.getCurrentLanguage(context)) }

    ListItem(
        modifier = modifier
            .clip(LanguageSettingItemShape)
            .clickable { showDialog = true },
        headlineContent = { Text(stringResource(CoreCommonR.string.language_setting_title)) },
        supportingContent = { Text(currentLanguage.getDisplayName(context)) },
        leadingContent = {
            SettingRowIcon(
                key = "language",
                imageVector = Icons.Outlined.Language,
                contentDescription = null
            )
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )

    if (showDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(CoreCommonR.string.language_select_title)) },
            text = {
                ScrollableDialogColumn {
                    LanguageManager.Language.entries.forEach { language ->
                        MiuixSettingsChoiceRow(
                            title = language.getDisplayName(context),
                            selected = currentLanguage == language,
                            onClick = {
                                showDialog = false
                                if (currentLanguage != language) {
                                    LanguageManager.setLanguage(context, language)
                                    currentLanguage = language
                                    onLanguageChanged(language)
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = { showDialog = false }) {
                    Text(stringResource(CoreCommonR.string.action_close))
                }
            }
        )
    }
}
