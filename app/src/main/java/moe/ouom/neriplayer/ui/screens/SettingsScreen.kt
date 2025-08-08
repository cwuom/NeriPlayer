package moe.ouom.neriplayer.ui.screens

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
 * File: moe.ouom.neriplayer.ui.screens/SettingsScreen
 * Created: 2025/8/8
 */

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.NightModeHelper
import moe.ouom.neriplayer.util.convertTimestampToDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    followSystemDark: Boolean,
    forceDark: Boolean,
    onForceDarkChange: (Boolean) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            LargeTopAppBar(
                title = { Text("设置") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Brightness4,
                            contentDescription = "动态取色",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("动态取色") },
                    supportingContent = { Text("跟随系统主题色，Android 12+ 可用") },
                    trailingContent = {
                        Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.DarkMode,
                            contentDescription = "强制深色",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("强制深色") },
                    supportingContent = { Text("不跟随系统时可手动指定") },
                    trailingContent = {
                        Switch(
                            checked = forceDark,
                            onCheckedChange = { checked ->
                                onForceDarkChange(checked)
                                NightModeHelper.applyNightMode(
                                    followSystemDark = false,
                                    forceDark = checked
                                )
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "关于",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("关于", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("NeriPlayer • GPLv3 • 由你可爱地驱动") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Verified,
                            contentDescription = "Build UUID",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("Build UUID", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(BuildConfig.BUILD_UUID) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Update,
                            contentDescription = "版本",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("版本", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Timer,
                            contentDescription = "编译时间",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("编译时间", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(convertTimestampToDate(BuildConfig.BUILD_TIMESTAMP)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = "GitHub",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    headlineContent = { Text("GitHub") },
                    supportingContent = { Text("github.com/cwuom/NeriPlayer") },
                    modifier = Modifier.clickable {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            "https://github.com/cwuom/NeriPlayer".toUri()
                        )
                        context.startActivity(intent)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}