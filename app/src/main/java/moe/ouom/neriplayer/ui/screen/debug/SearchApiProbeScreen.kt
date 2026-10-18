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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.music.MusicPlatform
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
    val enabled = !ui.running && ui.keyword.isNotBlank()

    DebugProbePage(
        title = stringResource(CoreCommonR.string.debug_search_probe),
        description = stringResource(CoreCommonR.string.debug_search_desc)
    ) {
        DebugProbeSection(
            title = stringResource(CoreCommonR.string.debug_search_section_platforms),
            trailing = { DebugProbeRunningIndicator(ui.running) }
        ) {
            DebugProbeField(ui.keyword, vm::onKeywordChange, stringResource(CoreCommonR.string.debug_search_keyword_label))
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_search_netease),
                icon = Icons.Rounded.Cloud,
                onClick = { vm.callSearchAndCopy(MusicPlatform.CLOUD_MUSIC) },
                enabled = enabled
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_search_qq),
                icon = Icons.Rounded.MusicNote,
                onClick = { vm.callSearchAndCopy(MusicPlatform.QQ_MUSIC) },
                enabled = enabled
            )
        }

        DebugProbeResult(
            title = stringResource(CoreCommonR.string.debug_youtube_probe_section_result),
            status = ui.lastMessage,
            running = ui.running,
            output = ui.lastJsonPreview,
            emptyOutput = stringResource(CoreCommonR.string.debug_search_preview_hint)
        )
    }
}
