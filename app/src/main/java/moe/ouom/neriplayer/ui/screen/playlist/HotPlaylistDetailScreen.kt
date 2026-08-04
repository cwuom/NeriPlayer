package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.stats.PlaybackStatsHotPlaylist
import moe.ouom.neriplayer.data.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.stats.buildPlaybackStatsHotPlaylist
import moe.ouom.neriplayer.data.stats.toPlaybackStatsSongItem
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.screen.StatTrackRow
import moe.ouom.neriplayer.util.format.formatPlayCount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotPlaylistDetailScreen(
    period: PlaybackStatsPeriod,
    onBack: () -> Unit,
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val hotPlaylist by produceState<PlaybackStatsHotPlaylist?>(
        initialValue = null,
        key1 = period
    ) {
        val statsRepository = withContext(Dispatchers.IO) {
            AppContainer.playbackStatsRepo
        }
        combine(
            statsRepository.statsFlow,
            statsRepository.dailyStatsFlow
        ) { stats, dailyStats ->
            stats to dailyStats
        }.collect { (stats, dailyStats) ->
            value = withContext(Dispatchers.Default) {
                buildPlaybackStatsHotPlaylist(
                    stats = stats,
                    dailyStats = dailyStats,
                    period = period
                )
            }
        }
    }
    val playlist = hotPlaylist
    val tracks = playlist?.tracks.orEmpty()
    val songs = remember(tracks) {
        tracks.map { stat -> stat.toPlaybackStatsSongItem() }
    }
    val context = LocalContext.current
    val title = stringResource(period.hotPlaylistTitleResId())

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(
                        enabled = songs.isNotEmpty(),
                        onClick = { onSongClick(songs, 0) }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = stringResource(R.string.cd_play_all)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (playlist == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 8.dp + LocalMiniPlayerHeight.current
                )
            ) {
                item(key = "hot_playlist_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.library_hot_playlist_summary,
                                tracks.size,
                                formatPlayCount(context, playlist.totalPlayCount)
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (tracks.isEmpty()) {
                    item(key = "hot_playlist_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.library_hot_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(tracks, key = { _, stat -> stat.identityKey }) { index, stat ->
                        StatTrackRow(
                            rank = index + 1,
                            stat = stat,
                            offlineMode = offlineMode,
                            onClick = { onSongClick(songs, index) }
                        )
                    }
                }
            }
        }
    }
}

private fun PlaybackStatsPeriod.hotPlaylistTitleResId(): Int = when (this) {
    PlaybackStatsPeriod.MONTH -> R.string.library_hot_playlist_month
    else -> R.string.library_hot_playlist_week
}
