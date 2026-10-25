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
 * File: moe.ouom.neriplayer.ui.screen.debug/BiliApiProbeScreen
 * Created: 2025/8/14
 */
import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.viewmodel.debug.BiliApiProbeViewModel

@Composable
fun BiliApiProbeScreen() {
    val ctx = LocalContext.current
    val vm: BiliApiProbeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                BiliApiProbeViewModel(ctx.applicationContext as Application)
            }
        }
    )

    val ui by vm.ui.collectAsState()
    val idle = !ui.running
    val hasBvid = ui.bvid.isNotBlank()
    val hasCid = ui.cid.isNotBlank()
    val page = ui.page.ifBlank { "1" }

    DebugProbePage(
        title = stringResource(CoreCommonR.string.debug_bili_probe_title),
        description = stringResource(CoreCommonR.string.debug_probe_autocopy)
    ) {
        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_bili_section_search)) {
            DebugProbeField(ui.keyword, vm::onKeywordChange, stringResource(CoreCommonR.string.debug_search_keyword))
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_search_copy_json),
                icon = Icons.Rounded.Search,
                onClick = vm::searchAndCopy,
                enabled = idle
            )
        }

        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_bili_section_video)) {
            DebugProbeField(ui.bvid, vm::onBvidChange, stringResource(CoreCommonR.string.debug_bvid_hint))
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_get_info_copy),
                icon = Icons.Rounded.Info,
                onClick = vm::viewByBvidAndCopy,
                enabled = idle && hasBvid
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_has_liked),
                icon = Icons.Rounded.ThumbUp,
                onClick = vm::hasLikeByBvidAndCopy,
                enabled = idle && hasBvid
            )
        }

        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_bili_section_stream)) {
            DebugProbeFieldRow {
                DebugProbeField(
                    ui.page, vm::onPageChange, stringResource(CoreCommonR.string.debug_page_hint),
                    modifier = Modifier.weight(1f), numeric = true, inRow = true
                )
                DebugProbeField(
                    ui.cid, vm::onCidChange, stringResource(CoreCommonR.string.debug_cid_hint),
                    modifier = Modifier.weight(1f), numeric = true, inRow = true
                )
            }
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_get_stream_by_page, page),
                icon = Icons.Rounded.Stream,
                onClick = vm::playInfoByBvidPageAndCopy,
                enabled = idle && hasBvid
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_get_audio_by_page, page),
                icon = Icons.Rounded.AudioFile,
                onClick = vm::allAudioStreamsByBvidPageAndCopy,
                enabled = idle && hasBvid
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_get_mp4_by_page, page),
                icon = Icons.Rounded.Movie,
                onClick = vm::mp4DurlByBvidPageAndCopy,
                enabled = idle && hasBvid
            )
            // with a CID the part number is skipped; without one these calls equal the rows above
            if (hasCid) {
                DebugProbeAction(
                    title = stringResource(CoreCommonR.string.debug_get_stream_by, "CID"),
                    icon = Icons.Rounded.Stream,
                    onClick = vm::playInfoByBvidCidAndCopy,
                    enabled = idle && hasBvid
                )
                DebugProbeAction(
                    title = stringResource(CoreCommonR.string.debug_get_audio_by, "CID"),
                    icon = Icons.Rounded.AudioFile,
                    onClick = vm::allAudioStreamsByBvidCidAndCopy,
                    enabled = idle && hasBvid
                )
                DebugProbeAction(
                    title = stringResource(CoreCommonR.string.debug_get_mp4_by, "CID"),
                    icon = Icons.Rounded.Movie,
                    onClick = vm::mp4DurlByBvidCidAndCopy,
                    enabled = idle && hasBvid
                )
            }
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_get_by_avid),
                icon = Icons.Rounded.Tag,
                onClick = vm::playInfoByAvidCidAndCopy,
                enabled = idle && hasBvid
            )
        }

        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_bili_section_subtitles)) {
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_bili_subtitle_tracks, page),
                icon = Icons.Rounded.ClosedCaption,
                onClick = vm::subtitleTracksByBvidCidAndCopy,
                enabled = idle && hasBvid
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_bili_subtitle_cues, page),
                icon = Icons.Rounded.Subtitles,
                onClick = vm::subtitleCueProbeByBvidCidAndCopy,
                enabled = idle && hasBvid
            )
        }

        DebugProbeSection(title = stringResource(CoreCommonR.string.debug_bili_section_favorites)) {
            DebugProbeField(ui.upMid, vm::onUpMidChange, stringResource(CoreCommonR.string.debug_up_mid_hint), numeric = true)
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_get_fav_list),
                icon = Icons.Rounded.Bookmarks,
                onClick = vm::createdFavsAndCopy,
                enabled = idle && ui.upMid.isNotBlank()
            )
            DebugProbeField(ui.mediaId, vm::onMediaIdChange, stringResource(CoreCommonR.string.debug_media_id_hint), numeric = true)
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_fav_info),
                icon = Icons.Rounded.Info,
                onClick = vm::favInfoAndCopy,
                enabled = idle && ui.mediaId.isNotBlank()
            )
            DebugProbeAction(
                title = stringResource(CoreCommonR.string.debug_fav_contents),
                icon = Icons.AutoMirrored.Rounded.ListAlt,
                onClick = vm::favContentsAndCopy,
                enabled = idle && ui.mediaId.isNotBlank()
            )
        }

        DebugProbeResult(
            title = stringResource(CoreCommonR.string.debug_youtube_probe_section_result),
            status = ui.lastMessage,
            running = ui.running,
            output = ui.lastJsonPreview,
            emptyOutput = stringResource(CoreCommonR.string.debug_preview_empty)
        ) {
            MiuixSettingsTextButton(onClick = vm::clearPreview, enabled = idle) {
                Text(stringResource(CoreCommonR.string.debug_clear_preview))
            }
        }
    }
}
