package moe.ouom.neriplayer.data.sync.host

import android.content.Context
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.identity.identity
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.platform.bili.skip.BiliVideoSkipRepository
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.mapping.toBiliVideoSkipRuleOrNull
import moe.ouom.neriplayer.data.sync.mapping.toFavoritePlaylist
import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalApplyHost
import moe.ouom.neriplayer.util.platform.LanguageManager

internal class AndroidSyncLocalApplyHost(
    private val appContext: Context,
    private val storage: SecureTokenStorage,
    private val playlistRepo: LocalPlaylistRepository,
    private val favoriteRepo: FavoritePlaylistRepository,
    private val playHistoryRepo: PlayHistoryRepository,
    private val playbackStatsRepo: PlaybackStatsRepository,
    private val playlistUsageRepo: PlaylistUsageRepository,
    private val localPlaylistPlaybackStatsRepo: LocalPlaylistPlaybackStatsRepository,
    private val biliVideoSkipRepo: BiliVideoSkipRepository
) : SyncLocalApplyHost {
    override suspend fun applyPlaylists(data: SyncData, expectedMutationVersion: Long): Boolean {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val currentPlaylists = playlistRepo.playlists.value.associateBy { playlist ->
            SystemLocalPlaylists.resolve(playlist.id, playlist.name, localizedContext)?.id ?: playlist.id
        }
        val mergedLocalPlaylists = data.playlists
            .filterNot(SyncPlaylist::isDeleted)
            .map { syncPlaylist ->
                val systemDescriptor = SystemLocalPlaylists.resolve(
                    syncPlaylist.id,
                    syncPlaylist.name,
                    localizedContext
                )
                val normalizedId = systemDescriptor?.id ?: syncPlaylist.id
                val syncedSongs = syncPlaylist.songs
                    .map { it.toSongItem() }
                    .distinctBy { it.identity() }
                val preservedLocalSongs = currentPlaylists[normalizedId]
                    ?.songs
                    .orEmpty()
                    .filter { LocalSongSupport.isLocalSong(it, localizedContext) }

                LocalPlaylist(
                    id = normalizedId,
                    name = systemDescriptor?.currentName ?: syncPlaylist.name,
                    songs = mergeLocalOnlySongs(syncedSongs, preservedLocalSongs),
                    modifiedAt = syncPlaylist.modifiedAt,
                    customCoverUrl = currentPlaylists[normalizedId]?.customCoverUrl,
                    songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                )
            }
        val playlistsApplied = playlistRepo.applySyncedPlaylistsIfUnchanged(
            playlists = mergedLocalPlaylists,
            expectedMutationVersion = expectedMutationVersion
        )
        if (!playlistsApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local playlist epoch changed")
            return false
        }
        return true
    }

    override fun applyDeletions(data: SyncData, expectedMutationVersion: Long): Boolean {
        val deletionStateApplied = storage.setDeletionStateIfMutationVersion(
            expectedMutationVersion = expectedMutationVersion,
            recentPlayDeletions = data.recentPlayDeletions,
            playlistSongDeletions = data.playlistSongDeletions
        )
        if (!deletionStateApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local deletion epoch changed")
            return false
        }
        return true
    }

    override suspend fun applyFavorites(data: SyncData, expectedMutationVersion: Long): Boolean {
        val favoritesApplied = favoriteRepo.replaceFavoritesFromSyncIfUnchanged(
            favorites = data.favoritePlaylists.map { it.toFavoritePlaylist() },
            expectedMutationVersion = expectedMutationVersion
        )
        if (!favoritesApplied) {
            NPLogger.w(TAG, "Skip applying merged sync data because local favorites epoch changed")
            return false
        }
        return true
    }

    override suspend fun applyHistory(data: SyncData, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
        val localizedContext = LanguageManager.applyLanguage(appContext)
        val localPlayHistoryEmpty = playHistoryRepo.historyFlow.value.isEmpty()
        val shouldApplyRemoteHistory = remoteChanged ||
            (localPlayHistoryEmpty && data.recentPlays.isNotEmpty())

        if (shouldApplyRemoteHistory) {
            val syncedHistory = data.recentPlays.mapNotNull { syncPlay ->
                if (LocalSongSupport.isLocalSong(syncPlay.song.album, syncPlay.song.mediaUri, syncPlay.song.albumId, localizedContext)) {
                    return@mapNotNull null
                }

                PlayedEntry(
                    id = syncPlay.song.id,
                    name = syncPlay.song.name,
                    artist = syncPlay.song.artist,
                    album = syncPlay.song.album,
                    albumId = syncPlay.song.albumId,
                    durationMs = syncPlay.song.durationMs,
                    coverUrl = syncPlay.song.coverUrl,
                    mediaUri = LocalSongSupport.sanitizeMediaUriForSync(syncPlay.song.mediaUri),
                    matchedLyric = syncPlay.song.matchedLyric,
                    matchedTranslatedLyric = syncPlay.song.matchedTranslatedLyric,
                    customCoverUrl = syncPlay.song.customCoverUrl,
                    customName = syncPlay.song.customName,
                    customArtist = syncPlay.song.customArtist,
                    originalName = syncPlay.song.originalName,
                    originalArtist = syncPlay.song.originalArtist,
                    originalCoverUrl = syncPlay.song.originalCoverUrl,
                    originalLyric = syncPlay.song.originalLyric,
                    originalTranslatedLyric = syncPlay.song.originalTranslatedLyric,
                    resumePositionMs = syncPlay.resumePositionMs,
                    playedAt = syncPlay.playedAt
                )
            }
            val localOnlyHistory = playHistoryRepo.historyFlow.value.filter {
                LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, localizedContext)
            }
            val playHistory = mergeLocalOnlyHistory(syncedHistory, localOnlyHistory)
            val historyApplied = playHistoryRepo.updateHistoryIfUnchanged(
                entries = playHistory,
                expectedMutationVersion = expectedMutationVersion
            )
            if (!historyApplied) {
                NPLogger.w(TAG, "Skip applying merged sync data because local history epoch changed")
                return false
            }
        }
        return true
    }

    override suspend fun applyStatistics(data: SyncData) {
        playbackStatsRepo.applyMergedStats(
            syncStats = data.playbackStats,
            playbackStatsClearedAt = data.playbackStatsClearedAt,
            syncDailyStats = data.playbackStatBuckets
        )
        playlistUsageRepo.applyMergedStats(data.playlistUsageStats)
        localPlaylistPlaybackStatsRepo.applyMergedStats(
            stats = data.localPlaylistPlaybackStats,
            buckets = data.localPlaylistPlaybackBuckets
        )
    }

    override suspend fun applyVideoSkipRules(data: SyncData, expectedMutationVersion: Long): Boolean {
        val videoSkipRulesApplied = biliVideoSkipRepo.replaceFromSyncIfUnchanged(
            rules = data.biliVideoSkipRules.mapNotNull { rule ->
                rule.toBiliVideoSkipRuleOrNull()
            },
            expectedMutationVersion = expectedMutationVersion
        )
        if (!videoSkipRulesApplied) {
            NPLogger.w(TAG, "Skip applying Bili video skip rules because local state changed")
            return false
        }
        return true
    }

    private fun mergeLocalOnlySongs(
        syncedSongs: List<moe.ouom.neriplayer.data.model.SongItem>,
        localOnlySongs: List<moe.ouom.neriplayer.data.model.SongItem>
    ): MutableList<moe.ouom.neriplayer.data.model.SongItem> {
        val merged = syncedSongs.toMutableList()
        val knownIdentities = merged.map { it.identity() }.toMutableSet()
        localOnlySongs.forEach { song ->
            if (knownIdentities.add(song.identity())) {
                merged += song
            }
        }
        return merged
    }

    private fun mergeLocalOnlyHistory(
        syncedHistory: List<PlayedEntry>,
        localOnlyHistory: List<PlayedEntry>
    ): List<PlayedEntry> {
        return (syncedHistory + localOnlyHistory)
            .distinctBy { "${it.id}|${it.album}|${it.mediaUri.orEmpty()}|${it.playedAt}" }
            .sortedByDescending { it.playedAt }
            .take(500)
    }

    private companion object {
        const val TAG = "SyncLocalDataStore"
    }
}
