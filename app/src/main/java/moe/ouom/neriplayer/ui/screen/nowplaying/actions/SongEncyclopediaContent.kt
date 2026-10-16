package moe.ouom.neriplayer.ui.screen.nowplaying.actions

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.actions/SongEncyclopediaContent
 * Created: 2025/10/16
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCredits
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaMusicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.viewmodel.SongEncyclopediaUiState

internal const val SONG_ENCYCLOPEDIA_TEST_TAG = "song_encyclopedia_content"

/**
 * 歌曲百科二级页。
 *
 * 状态由 [NowPlayingViewModel.encyclopediaState] 提供，且始终按当前歌曲 key 校验，
 * 快速切歌时上一首的结果不会渲染到新歌上。
 */
@Composable
internal fun SongEncyclopediaContent(
    viewModel: NowPlayingViewModel,
    song: SongItem,
    onDismiss: () -> Unit
) {
    val state by viewModel.encyclopediaState.collectAsStateWithLifecycle()
    val songKey = song.encyclopediaSongKey()

    LaunchedEffect(songKey) {
        viewModel.loadEncyclopedia(song)
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.cancelEncyclopediaLoad() }
    }

    SongEncyclopediaStateContent(
        state = state.forSongKey(songKey),
        songKey = songKey,
        onRetry = { viewModel.retryEncyclopediaLoad(song) },
        onDismiss = onDismiss
    )
}

@Composable
private fun SongEncyclopediaStateContent(
    state: SongEncyclopediaUiState,
    songKey: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetScrollGuard()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag(SONG_ENCYCLOPEDIA_TEST_TAG)
    ) {
        SongEncyclopediaHeader(onDismiss)
        when (state) {
            SongEncyclopediaUiState.Idle,
            SongEncyclopediaUiState.Loading -> SongEncyclopediaLoading()
            SongEncyclopediaUiState.NotSupported -> SongEncyclopediaMessage(
                CoreCommonR.string.encyclopedia_not_supported
            )
            SongEncyclopediaUiState.Empty -> SongEncyclopediaMessage(
                CoreCommonR.string.encyclopedia_empty
            )
            is SongEncyclopediaUiState.Failed -> SongEncyclopediaFailure(onRetry)
            is SongEncyclopediaUiState.Success -> {
                // 双保险：即使状态泄漏，也不渲染不属于当前歌曲的数据。
                if (state.data.songKey == songKey) {
                    SongEncyclopediaBody(state.data)
                } else {
                    SongEncyclopediaLoading()
                }
            }
        }
    }
}

@Composable
private fun SongEncyclopediaHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(CoreCommonR.string.action_back)
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(CoreCommonR.string.encyclopedia_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SongEncyclopediaLoading() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(CoreCommonR.string.encyclopedia_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SongEncyclopediaMessage(messageRes: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SongEncyclopediaFailure(onRetry: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(CoreCommonR.string.encyclopedia_failed)) },
        trailingContent = {
            IconButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(CoreCommonR.string.encyclopedia_retry)
                )
            }
        }
    )
}

@Composable
private fun SongEncyclopediaBody(data: SongEncyclopedia) {
    SongEncyclopediaSectionTitle(CoreCommonR.string.encyclopedia_section_basic)
    data.basicInfo.name.takeIf { it.isNotBlank() }?.let { name ->
        SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_song, name)
    }
    SongEncyclopediaRowIfAny(
        labelRes = CoreCommonR.string.encyclopedia_label_artists,
        values = data.basicInfo.artists
    )
    data.basicInfo.album?.takeIf { it.isNotBlank() }?.let { album ->
        SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_album, album)
    }

    data.musicInfo?.let { musicInfo ->
        SongEncyclopediaMusicSection(musicInfo)
    }
    data.credits?.let { credits ->
        SongEncyclopediaCreditsSection(credits)
    }
    if (data.awards.isNotEmpty() || data.appearances.isNotEmpty()) {
        SongEncyclopediaSectionTitle(CoreCommonR.string.encyclopedia_section_awards)
        data.awards.forEach { SongEncyclopediaAwardRow(it) }
        data.appearances.forEach { appearance ->
            SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_appearance, appearance)
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(CoreCommonR.string.encyclopedia_source_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SongEncyclopediaMusicSection(musicInfo: EncyclopediaMusicInfo) {
    val hasContent = musicInfo.genres.isNotEmpty() ||
        musicInfo.tags.isNotEmpty() ||
        musicInfo.language != null ||
        musicInfo.bpm != null
    if (!hasContent) return
    SongEncyclopediaSectionTitle(CoreCommonR.string.encyclopedia_section_music)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_genres, musicInfo.genres)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_tags, musicInfo.tags)
    musicInfo.language?.takeIf { it.isNotBlank() }?.let { language ->
        SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_language, language)
    }
    musicInfo.bpm?.takeIf { it > 0 }?.let { bpm ->
        SongEncyclopediaRow(CoreCommonR.string.encyclopedia_label_bpm, bpm.toString())
    }
}

@Composable
private fun SongEncyclopediaCreditsSection(credits: EncyclopediaCredits) {
    val hasContent = credits.lyricists.isNotEmpty() ||
        credits.composers.isNotEmpty() ||
        credits.arrangers.isNotEmpty() ||
        credits.producers.isNotEmpty() ||
        credits.otherRoles.isNotEmpty()
    if (!hasContent) return
    SongEncyclopediaSectionTitle(CoreCommonR.string.encyclopedia_section_credits)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_lyricist, credits.lyricists)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_composer, credits.composers)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_arranger, credits.arrangers)
    SongEncyclopediaRowIfAny(CoreCommonR.string.encyclopedia_label_producer, credits.producers)
    credits.otherRoles.forEach { role ->
        SongEncyclopediaNamedRowIfAny(role.role, role.names)
    }
}

@Composable
private fun SongEncyclopediaAwardRow(award: EncyclopediaAward) {
    val detail = award.detail?.takeIf { it.isNotBlank() }
    ListItem(
        headlineContent = { Text(award.title) },
        supportingContent = {
            if (detail != null) Text(detail)
        }
    )
}

@Composable
private fun SongEncyclopediaSectionTitle(titleRes: Int) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SongEncyclopediaRow(labelRes: Int, value: String) {
    SongEncyclopediaRow(stringResource(labelRes), value)
}

@Composable
private fun SongEncyclopediaRowIfAny(labelRes: Int, values: List<String>) {
    SongEncyclopediaNamedRowIfAny(stringResource(labelRes), values)
}

@Composable
private fun SongEncyclopediaNamedRowIfAny(label: String, values: List<String>) {
    val text = values.filter { it.isNotBlank() }.joinToString("、")
    if (text.isEmpty()) return
    SongEncyclopediaRow(label, text)
}

@Composable
private fun SongEncyclopediaRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
