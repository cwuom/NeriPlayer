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
 * File: moe.ouom.neriplayer.ui.screen.debug/YouTubeApiProbeScreen
 * Created: 2026/3/21
 */

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.viewmodel.debug.YouTubeApiProbeViewModel
import moe.ouom.neriplayer.R as AppR

@Composable
fun YouTubeApiProbeScreen() {
    val context = LocalContext.current
    val vm: YouTubeApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                YouTubeApiProbeViewModel(app)
            }
        }
    )
    val ui by vm.ui.collectAsState()
    val idle = !ui.running

    DebugProbePage(
        title = stringResource(AppR.string.debug_youtube_probe_title),
        description = ui.authSummary
    ) {
        DebugProbeSection(title = stringResource(AppR.string.debug_youtube_probe_section_inputs)) {
            DebugProbeFieldRow {
                DebugProbeField(
                    ui.hl, vm::onHlChange, stringResource(AppR.string.debug_youtube_probe_input_hl),
                    modifier = Modifier.weight(1f), inRow = true
                )
                DebugProbeField(
                    ui.gl, vm::onGlChange, stringResource(AppR.string.debug_youtube_probe_input_gl),
                    modifier = Modifier.weight(1f), inRow = true
                )
            }
            DebugProbeField(ui.videoId, vm::onVideoIdChange, stringResource(AppR.string.debug_youtube_probe_input_video_id))
            DebugProbeField(ui.browseId, vm::onBrowseIdChange, stringResource(AppR.string.debug_youtube_probe_input_browse_id))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(AppR.string.debug_youtube_probe_force_refresh),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                MiuixSettingsSwitch(
                    checked = ui.forceRefresh,
                    onCheckedChange = vm::onForceRefreshChange,
                    enabled = idle
                )
            }
        }

        DebugProbeSection(
            title = stringResource(AppR.string.debug_youtube_probe_section_actions),
            trailing = { DebugProbeRunningIndicator(ui.running) }
        ) {
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_bootstrap),
                Icons.Rounded.RocketLaunch, vm::probeBootstrap, enabled = idle, copiesResult = false
            )
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_home),
                Icons.Rounded.Home, vm::probeHomeFeed, enabled = idle, copiesResult = false
            )
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_library),
                Icons.AutoMirrored.Rounded.QueueMusic, vm::probeLibraryPlaylists, enabled = idle, copiesResult = false
            )
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_browse),
                Icons.Rounded.Explore, vm::probeBrowse, enabled = idle && ui.browseId.isNotBlank(), copiesResult = false
            )
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_player),
                Icons.Rounded.PlayCircle, vm::probePlayer, enabled = idle && ui.videoId.isNotBlank(), copiesResult = false
            )
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_lyrics),
                Icons.Rounded.Lyrics, vm::probeLyrics, enabled = idle && ui.videoId.isNotBlank(), copiesResult = false
            )
            DebugProbeAction(
                stringResource(AppR.string.debug_youtube_probe_action_clear_auth),
                Icons.Rounded.LockReset, vm::clearAuth, enabled = idle, copiesResult = false
            )
        }

        DebugProbeResult(
            title = stringResource(AppR.string.debug_youtube_probe_section_result),
            status = ui.status,
            running = ui.running,
            output = ui.rawJson,
            emptyOutput = stringResource(AppR.string.debug_youtube_probe_raw_empty),
            summary = ui.summary
        ) {
            MiuixSettingsTextButton(onClick = vm::copyRawJson, enabled = idle && ui.rawJson.isNotBlank()) {
                Text(stringResource(AppR.string.debug_youtube_probe_copy_raw))
            }
            MiuixSettingsTextButton(onClick = vm::clearPreview, enabled = idle) {
                Text(stringResource(AppR.string.debug_clear_preview))
            }
        }
    }
}
