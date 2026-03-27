package moe.ouom.neriplayer.ui.screen.tab.settings.about

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.about/SettingsAboutSection
 * Updated: 2026/3/23
 */

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.BuildConfig
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.util.convertTimestampToDate

internal fun LazyListScope.settingsAboutSection(
    devModeEnabled: Boolean,
    onVersionClick: () -> Unit,
    onOpenGitHubRepo: () -> Unit
) {
    item {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.settings_about),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.nav_about),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = { Text(stringResource(R.string.about_app_footer)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    item {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Verified,
                    contentDescription = stringResource(R.string.settings_build_uuid),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.settings_build_uuid),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = { Text(BuildConfig.BUILD_UUID) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    item {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Update,
                    contentDescription = stringResource(R.string.settings_version),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.common_version),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = {
                val suffix = if (devModeEnabled) {
                    " (${stringResource(R.string.settings_version_debug_suffix)})"
                } else {
                    ""
                }
                Text("${BuildConfig.VERSION_NAME}$suffix")
            },
            modifier = androidx.compose.ui.Modifier.settingsItemClickable(onClick = onVersionClick),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    item {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = Icons.Outlined.Timer,
                    contentDescription = stringResource(R.string.settings_build_time),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = {
                Text(
                    text = stringResource(R.string.common_build_time),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            supportingContent = { Text(convertTimestampToDate(BuildConfig.BUILD_TIMESTAMP)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }

    item {
        ListItem(
            leadingContent = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github),
                    contentDescription = stringResource(R.string.common_github),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            headlineContent = { Text(stringResource(R.string.common_github)) },
            supportingContent = { Text(stringResource(R.string.settings_github_repo_url)) },
            modifier = androidx.compose.ui.Modifier.settingsItemClickable(onClick = onOpenGitHubRepo),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
