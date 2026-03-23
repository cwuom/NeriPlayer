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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.debug.YouTubeApiProbeViewModel

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
    val scroll = rememberScrollState()
    val miniH = LocalMiniPlayerHeight.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = miniH),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.debug_youtube_probe_title),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.debug_youtube_probe_desc),
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(ui.authSummary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.debug_youtube_probe_section_inputs),
                    style = MaterialTheme.typography.labelLarge
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ui.hl,
                        onValueChange = vm::onHlChange,
                        label = { Text(stringResource(R.string.debug_youtube_probe_input_hl)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ui.gl,
                        onValueChange = vm::onGlChange,
                        label = { Text(stringResource(R.string.debug_youtube_probe_input_gl)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = ui.videoId,
                    onValueChange = vm::onVideoIdChange,
                    label = { Text(stringResource(R.string.debug_youtube_probe_input_video_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = ui.browseId,
                    onValueChange = vm::onBrowseIdChange,
                    label = { Text(stringResource(R.string.debug_youtube_probe_input_browse_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.debug_youtube_probe_force_refresh),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = ui.forceRefresh,
                        onCheckedChange = vm::onForceRefreshChange,
                        enabled = !ui.running
                    )
                }

                Text(
                    text = stringResource(R.string.debug_youtube_probe_section_actions),
                    style = MaterialTheme.typography.labelLarge
                )

                Button(
                    onClick = vm::probeBootstrap,
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_bootstrap))
                }

                OutlinedButton(
                    onClick = vm::probeHomeFeed,
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_home))
                }

                OutlinedButton(
                    onClick = vm::probeLibraryPlaylists,
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_library))
                }

                OutlinedButton(
                    onClick = vm::probeBrowse,
                    enabled = !ui.running && ui.browseId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_browse))
                }

                OutlinedButton(
                    onClick = vm::probePlayer,
                    enabled = !ui.running && ui.videoId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_player))
                }

                OutlinedButton(
                    onClick = vm::probeLyrics,
                    enabled = !ui.running && ui.videoId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_lyrics))
                }

                OutlinedButton(
                    onClick = vm::clearAuth,
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_action_clear_auth))
                }

                if (ui.running) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.debug_status, ui.status),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.debug_youtube_probe_section_result),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = ui.summary.ifBlank {
                        stringResource(R.string.debug_youtube_probe_summary_empty)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = ui.rawJson.ifBlank {
                        stringResource(R.string.debug_youtube_probe_raw_empty)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(
                    onClick = vm::copyRawJson,
                    enabled = !ui.running && ui.rawJson.isNotBlank()
                ) {
                    Text(stringResource(R.string.debug_youtube_probe_copy_raw))
                }
                TextButton(
                    onClick = vm::clearPreview,
                    enabled = !ui.running
                ) {
                    Text(stringResource(R.string.debug_clear_preview))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
