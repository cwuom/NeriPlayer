@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.ouom.neriplayer.core.player.lifecycle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.url.currentPlaybackCacheKeyForRecovery
import moe.ouom.neriplayer.data.history.toSongItem
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem

internal class StorageCacheRetentionPlan(
    recentSongs: List<SongItem>,
    currentSong: SongItem?,
    val protectedKeys: Set<String>,
    val requestToken: Long,
    val preserveUnmapped: Boolean
) {
    val currentSongKey: String? = currentSong?.stableKey()
    val retainedSongs: List<SongItem> = (recentSongs + listOfNotNull(currentSong)).distinctBy { it.stableKey() }
    val retainedCoverUrls: Set<String> = if (preserveUnmapped) {
        retainedSongs.flatMap { listOfNotNull(it.coverUrl, it.customCoverUrl, it.originalCoverUrl) }
            .filter(String::isNotBlank).toSet()
    } else emptySet()
}

internal suspend fun loadRetainedStorageCacheSongs(count: Int): List<SongItem>? {
    if (count <= 0) return emptyList()
    val history = PlayerDependencies.repositories.playHistoryRepo
    if (!history.awaitInitialized()) return null
    return selectRetainedCacheSongs(history.historyFlow.value.map { it.toSongItem() }, count)
}

internal suspend fun PlayerManager.snapshotStorageCacheRetention(
    recentSongs: List<SongItem>,
    retainRecentSongs: Int
): StorageCacheRetentionPlan = withContext(Dispatchers.Main.immediate) {
    val mediaUri = if (isPlayerInitialized()) player.currentMediaItem?.localConfiguration?.uri?.toString() else null
    StorageCacheRetentionPlan(
        recentSongs = recentSongs,
        currentSong = currentSongFlow.value,
        protectedKeys = setOfNotNull(currentPlaybackCacheKeyForRecovery(), currentMediaUrlFlow.value, mediaUri),
        requestToken = playbackRequestToken,
        preserveUnmapped = retainRecentSongs > 0
    )
}
