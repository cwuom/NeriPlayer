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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsBackupRestore
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
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R

@Composable
fun DebugHomeScreen(
    onOpenBiliDebug: () -> Unit,
    onOpenNeteaseDebug: () -> Unit,
    onOpenSearchDebug: () -> Unit,
    onHideDebugMode: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.BugReport,
                    contentDescription = "调试",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text("调试工具") },
            supportingContent = { Text("选择要调试的平台或隐藏调试模式") },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
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
                            contentDescription = "B 站",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text("B 站接口调试") },
                    supportingContent = { Text("搜索 / 取封面简介 / 统计 / 取流 / 收藏夹") },
                    modifier = Modifier.clickable(onClick = onOpenBiliDebug),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_netease_cloud_music),
                            contentDescription = "网易云",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text("网易云接口调试") },
                    supportingContent = { Text("账户 / 歌单 / 歌曲 URL / 歌词") },
                    modifier = Modifier.clickable(onClick = onOpenNeteaseDebug),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )

                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    headlineContent = { Text("多平台搜索接口调试") },
                    supportingContent = { Text("QQ / 网易云") },
                    modifier = Modifier.clickable(onClick = onOpenSearchDebug),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onHideDebugMode) {
            Icon(
                imageVector = Icons.Outlined.SettingsBackupRestore,
                contentDescription = "隐藏"
            )
            Spacer(Modifier.height(0.dp)) // 占位，避免排版抖动
            Text("隐藏调试模式")
        }

        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Build,
                    contentDescription = "提示",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text("提示") },
            supportingContent = {
                Text("隐藏后底部栏将移除“调试”，可在设置页点击版本号 7 次再次开启")
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        )
    }
}