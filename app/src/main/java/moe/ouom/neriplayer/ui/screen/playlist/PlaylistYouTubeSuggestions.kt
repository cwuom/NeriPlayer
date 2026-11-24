package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.platform.youtube.settings.PlaylistSuggestionSource
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.viewmodel.tab.toSongItem

internal fun playlistSuggestionQueries(title: String, artists: List<String>, source: PlaylistSuggestionSource): List<String> =
    buildList {
        if (source != PlaylistSuggestionSource.Tracks) {
            title.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
        if (source != PlaylistSuggestionSource.Title) {
            artists.map(String::trim).filter(String::isNotEmpty)
                .groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.take(2)
                .forEach { add(it.key) }
        }
    }.distinct()

@Composable
internal fun PlaylistYouTubeSuggestions(
    title: String,
    songs: List<SongItem>,
    onPlay: (List<SongItem>, Int) -> Unit,
    onAdd: (SongItem) -> Unit
) {
    val enabled by AppContainer.settingsRepo.youtubeEnabledFlow.collectAsStateWithLifecycle(YouTubeFeatureGate.isEnabled())
    if (!enabled) return
    var opened by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.youtube_content_suggestions)
    HapticTextButton(onClick = { opened = true }) { Text(label) }
    if (!opened) return

    val context = LocalContext.current
    val prefs by YouTubeContentSettings.preferences.collectAsStateWithLifecycle()
    val catalogKey = prefs.catalogKey(YouTubeContentSettings.systemLocale())
    val queries = remember(title, songs, prefs.playlistSuggestionSource) {
        playlistSuggestionQueries(title, songs.map { it.displayArtist() }, prefs.playlistSuggestionSource)
    }
    var attempt by remember { mutableIntStateOf(0) }
    var results by remember(queries, catalogKey, attempt) { mutableStateOf<List<SongItem>?>(null) }
    var failed by remember(queries, catalogKey, attempt) { mutableStateOf(false) }
    LaunchedEffect(queries, catalogKey, attempt) {
        try {
            results = withContext(Dispatchers.IO) {
                queries.flatMap { query -> AppContainer.youtubeMusicClient.search(query, limit = 6) }
                    .distinctBy { it.videoId }.map { it.toSongItem(context) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val existing = remember(songs) { songs.mapTo(HashSet()) { it.identity() } }
    val available = results.orEmpty().filter { it.identity() !in existing }
    MiuixSettingsDialog(
        title = { Text(label) },
        onDismissRequest = { opened = false },
        text = {
            when {
                failed -> Column {
                    Text(stringResource(R.string.youtube_content_suggestions_error))
                    HapticTextButton(onClick = { attempt++ }) { Text(stringResource(CommonR.string.action_retry)) }
                }
                results == null -> CircularProgressIndicator()
                available.isEmpty() -> Text(stringResource(R.string.youtube_content_suggestions_empty))
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(available, key = { _, song -> song.mediaUri.orEmpty() }) { index, song ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onPlay(available, index)
                                opened = false
                            },
                            headlineContent = { Text(song.name) },
                            supportingContent = { Text(song.artist) },
                            trailingContent = {
                                HapticIconButton(onClick = { onAdd(song) }) {
                                    Icon(Icons.Outlined.Add, stringResource(CommonR.string.playlist_add_to))
                                }
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = { opened = false }) { Text(stringResource(CommonR.string.action_close)) }
        }
    )
}
