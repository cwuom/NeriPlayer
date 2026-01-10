package moe.ouom.neriplayer.ui.screen.debug

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
 * File: moe.ouom.neriplayer.ui.screen.debug/DebugHomeScreen
 * Created: 2025/8/14
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R

@Composable
fun DebugHomeScreen(
    onOpenBiliDebug: () -> Unit,
    onOpenNeteaseDebug: () -> Unit,
    onOpenSearchDebug: () -> Unit,
    onOpenLogs: () -> Unit,
    onHideDebugMode: () -> Unit,
    onTestExceptionHandler: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = stringResource(R.string.debug_title),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.debug_tools)) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            ),
            supportingContent = { Text(stringResource(R.string.debug_select_platform)) },
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(Modifier.fillMaxWidth()) {
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_bilibili),
                            contentDescription = stringResource(R.string.platform_bilibili),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.debug_bili_api)) },
                    supportingContent = { Text(stringResource(R.string.debug_bili_api_desc)) },
                    modifier = Modifier.clickable(onClick = onOpenBiliDebug),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_netease_cloud_music),
                            contentDescription = stringResource(R.string.platform_netease_short),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.debug_netease_api)) },
                    supportingContent = { Text(stringResource(R.string.debug_netease_api_desc)) },
                    modifier = Modifier.clickable(onClick = onOpenNeteaseDebug),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.action_search),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.debug_search_api)) },
                    supportingContent = { Text(stringResource(R.string.debug_search_api_desc)) },
                    modifier = Modifier.clickable(onClick = onOpenSearchDebug),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = stringResource(R.string.log_title),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.debug_view_logs)) },
                    supportingContent = { Text(stringResource(R.string.debug_view_logs_desc)) },
                    modifier = Modifier.clickable(onClick = onOpenLogs),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = stringResource(R.string.error_test),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text(stringResource(R.string.debug_test_exception)) },
                    supportingContent = { Text(stringResource(R.string.debug_test_exception_desc)) },
                    modifier = Modifier.clickable(onClick = onTestExceptionHandler),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onHideDebugMode) {
            Icon(
                imageVector = Icons.Outlined.SettingsBackupRestore,
                contentDescription = stringResource(R.string.action_hide)
            )
            Spacer(Modifier.height(0.dp))
            Text(stringResource(R.string.debug_hide))
        }

        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = stringResource(R.string.dialog_hint),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.dialog_hint)) },
            supportingContent = {
                Text(stringResource(R.string.debug_hide_hint))
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}