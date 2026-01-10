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
 * File: moe.ouom.neriplayer.ui.screen.debug/SearchApiProbeScreen
 * Created: 2025/8/17
 */

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.debug.SearchApiProbeViewModel

@Composable
fun SearchApiProbeScreen() {
    val context = LocalContext.current
    val vm: SearchApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as Application
                SearchApiProbeViewModel(app)
            }
        }
    )

    val ui by vm.ui.collectAsState()
    val scroll = rememberScrollState()
    val miniH = LocalMiniPlayerHeight.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = miniH),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.debug_search_probe),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = stringResource(R.string.debug_search_desc),
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = ui.keyword,
            onValueChange = vm::onKeywordChange,
            label = { Text(stringResource(R.string.debug_search_keyword_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val buttonEnabled = !ui.running && ui.keyword.isNotBlank()

                Button(
                    onClick = { vm.callSearchAndCopy(MusicPlatform.CLOUD_MUSIC) },
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_search_netease)) }

                Button(
                    onClick = { vm.callSearchAndCopy(MusicPlatform.QQ_MUSIC) },
                    enabled = buttonEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.debug_search_qq)) }


                if (ui.running) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
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
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.debug_status, ui.lastMessage), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = ui.lastJsonPreview.ifBlank { stringResource(R.string.debug_search_preview_hint) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}