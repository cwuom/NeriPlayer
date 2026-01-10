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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
            text = stringResource(R.string.debug_netease_probe),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.debug_netease_desc),
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.callAllAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_call_all_copy)) }

                OutlinedButton(
                    onClick = { vm.callAccountAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_account_only)) }

                OutlinedButton(
                    onClick = { vm.callUserIdAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_userid_only)) }

                OutlinedButton(
                    onClick = { vm.callCreatedPlaylistsAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_created_playlists)) }

                OutlinedButton(
                    onClick = { vm.callStaredAlbums() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_stared_albums)) }

                OutlinedButton(
                    onClick = { vm.callSubscribedPlaylistsAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_subscribed_playlists)) }

                OutlinedButton(
                    onClick = { vm.callLikedPlaylistIdAndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_liked_playlist_id)) }

                OutlinedButton(
                    onClick = { vm.callLyric33894312AndCopy() },
                    enabled = !ui.running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_lyric_sample)) }

                if (ui.running) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.debug_status, ui.lastMessage), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = ui.lastJsonPreview.ifBlank { stringResource(R.string.debug_preview_hint) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
