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
 * File: moe.ouom.neriplayer.ui.screen.debug/NeteaseApiProbeScreen
 * Created: 2025/8/14
 */

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.viewmodel.debug.NeteaseApiProbeViewModel

@Composable
fun NeteaseApiProbeScreen() {
    val context = LocalContext.current

    val vm: NeteaseApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                NeteaseApiProbeViewModel(app)
            }
        }
    )

    val ui by vm.ui.collectAsState()
    val idle = !ui.running

    DebugProbePage(
        title = stringResource(CoreCommonR.string.debug_netease_probe),
        description = stringResource(CoreCommonR.string.debug_netease_desc)
    ) {
        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_netease_probe_section_auth)) {
            Text(
                ui.authSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_netease_probe_section_inputs)) {
            DebugProbeField(ui.keyword, vm::onKeywordChange, stringResource(CoreCommonR.string.debug_netease_probe_input_keyword))
            DebugProbeField(
                ui.songId, vm::onSongIdChange,
                stringResource(CoreCommonR.string.debug_netease_probe_input_song_id),
                numeric = true
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_netease_probe_action_search),
                icon = Icons.Rounded.Search,
                onClick = vm::callSearchAndCopy,
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_netease_probe_action_song_detail),
                icon = Icons.Rounded.Info,
                onClick = vm::callSongDetailAndCopy,
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_netease_probe_action_song_lyric),
                icon = Icons.Rounded.Subtitles,
                onClick = vm::callSongLyricAndCopy,
                enabled = idle
            )
        }

        DebugProbeSection(
            title = stringResource(CoreCommonR.string.debug_netease_probe_section_actions),
            trailing = { DebugProbeRunningIndicator(ui.running) }
        ) {
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_call_all_copy),
                icon = Icons.Rounded.Bolt,
                onClick = { vm.callAllAndCopy() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_account_only),
                icon = Icons.Rounded.Person,
                onClick = { vm.callAccountAndCopy() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_userid_only),
                icon = Icons.Rounded.Badge,
                onClick = { vm.callUserIdAndCopy() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_created_playlists),
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                onClick = { vm.callCreatedPlaylistsAndCopy() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_subscribed_playlists),
                icon = Icons.Rounded.LibraryMusic,
                onClick = { vm.callSubscribedPlaylistsAndCopy() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_stared_albums),
                icon = Icons.Rounded.Album,
                onClick = { vm.callStaredAlbums() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_liked_playlist_id),
                icon = Icons.Rounded.Favorite,
                onClick = { vm.callLikedPlaylistIdAndCopy() },
                enabled = idle
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_lyric_sample),
                icon = Icons.Rounded.Lyrics,
                onClick = { vm.callLyric33894312AndCopy() },
                enabled = idle
            )
        }

        DebugProbeResult(
            title = stringResource(CoreCommonR.string.debug_netease_probe_section_result),
            status = ui.lastMessage,
            running = ui.running,
            output = ui.lastJsonPreview,
            emptyOutput = stringResource(CoreCommonR.string.debug_netease_probe_raw_empty),
            summary = ui.resultSummary
        )
    }
}
