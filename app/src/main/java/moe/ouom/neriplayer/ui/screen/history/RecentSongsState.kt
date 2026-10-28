package moe.ouom.neriplayer.ui.screen.history

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.search.SearchTextMatcher
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.displayAlbum
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.screen.playlist.FavoriteSongLookup

internal class RecentSongRowItem(
    val song: SongItem,
    val selectionKey: String,
    val itemKey: String
)

internal class RecentSongsSnapshot(
    val rows: List<RecentSongRowItem>,
    val songs: List<SongItem>
) {
    companion object {
        val Empty = RecentSongsSnapshot(emptyList(), emptyList())
    }
}

internal suspend fun prepareRecentSongs(history: List<PlayedEntry>): RecentSongsSnapshot =
    withContext(Dispatchers.Default) {
        val coroutineContext = currentCoroutineContext()
        val rows = history.map { entry ->
            coroutineContext.ensureActive()
            val song = entry.toSongItem()
            RecentSongRowItem(
                song = song,
                selectionKey = song.stableKey(),
                itemKey = SongIdentity(entry.id, entry.album, entry.localFilePath ?: entry.mediaUri).stableKey()
            )
        }
        RecentSongsSnapshot(rows = rows, songs = rows.map { it.song })
    }

@Composable
internal fun rememberRecentSongs(history: List<PlayedEntry>): RecentSongsSnapshot {
    val snapshot by produceState(RecentSongsSnapshot.Empty, history) {
        value = prepareRecentSongs(history)
    }
    return snapshot
}

private class RecentSearchIndex(
    val source: RecentSongsSnapshot,
    val context: Context,
    val index: SearchTextMatcher.Index<RecentSongRowItem>
)

private class RecentSearchResult(
    val index: RecentSearchIndex,
    val query: String,
    val snapshot: RecentSongsSnapshot
)

@Composable
internal fun rememberRecentSearchResults(
    snapshot: RecentSongsSnapshot,
    query: String,
    context: Context,
    searchVisible: Boolean
): RecentSongsSnapshot {
    if (!searchVisible && query.isBlank()) return snapshot

    val resources = LocalResources.current
    val configuration = Configuration(LocalConfiguration.current)
    val resourceConfiguration = Configuration(resources.configuration)
    val searchContext = remember(context, resources, configuration, resourceConfiguration) {
        context.createConfigurationContext(resourceConfiguration)
    }
    val preparedIndex by produceState<RecentSearchIndex?>(null, snapshot, searchContext) {
        value = withContext(Dispatchers.Default) {
            val coroutineContext = currentCoroutineContext()
            RecentSearchIndex(
                source = snapshot,
                context = searchContext,
                index = SearchTextMatcher.index(snapshot.rows) { row ->
                    coroutineContext.ensureActive()
                    recentSongSearchTokens(row.song, searchContext)
                }
            )
        }
    }
    if (query.isBlank()) return snapshot

    val activeIndex = preparedIndex?.takeIf { it.source === snapshot && it.context === searchContext }
    val displayed by produceState<RecentSearchResult?>(null, activeIndex, query) {
        value = if (activeIndex == null) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val rows = activeIndex.index.filterAndRank(query)
                RecentSearchResult(
                    index = activeIndex,
                    query = query,
                    snapshot = RecentSongsSnapshot(rows = rows, songs = rows.map { it.song })
                )
            }
        }
    }
    return displayed?.takeIf { it.index === activeIndex && it.query == query }?.snapshot
        ?: RecentSongsSnapshot.Empty
}

private fun recentSongSearchTokens(song: SongItem, context: Context): List<String> = listOfNotNull(
    song.name,
    song.artist,
    song.customName,
    song.customArtist,
    song.displayAlbum(context),
    song.localFileName,
    song.localFilePath,
    song.originalName,
    song.originalArtist
)

@Composable
internal fun rememberRecentFavoriteLookup(favoriteSongs: List<SongItem>): FavoriteSongLookup? {
    val source = RecentFavoriteSource(favoriteSongs)
    val prepared by produceState<RecentFavoriteLookup?>(null, source) {
        value = withContext(Dispatchers.Default) {
            RecentFavoriteLookup(favoriteSongs, FavoriteSongLookup(favoriteSongs))
        }
    }
    return prepared?.takeIf { it.source === favoriteSongs }?.lookup
}

private class RecentFavoriteLookup(
    val source: List<SongItem>,
    val lookup: FavoriteSongLookup
)

private class RecentFavoriteSource(private val songs: List<SongItem>) {
    override fun equals(other: Any?): Boolean = other is RecentFavoriteSource && songs === other.songs

    override fun hashCode(): Int = System.identityHashCode(songs)
}
