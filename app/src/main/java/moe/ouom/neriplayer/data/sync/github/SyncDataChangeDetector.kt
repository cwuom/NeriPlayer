package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.identity

internal object SyncDataChangeDetector {
    fun hasDataChanged(remote: SyncData, merged: SyncData): Boolean {
        if (remote.playlists.size != merged.playlists.size) return true
        if (remote.playlists.map(SyncPlaylist::id) != merged.playlists.map(SyncPlaylist::id)) return true

        val remotePlaylistMap = remote.playlists.associateBy { it.id }
        for (mergedPlaylist in merged.playlists) {
            val remotePlaylist = remotePlaylistMap[mergedPlaylist.id] ?: return true
            if (remotePlaylist.isDeleted != mergedPlaylist.isDeleted) return true
            if (remotePlaylist.name != mergedPlaylist.name) return true
            if (remotePlaylist.songOrderVersion != mergedPlaylist.songOrderVersion) return true
            if (remotePlaylist.songs.size != mergedPlaylist.songs.size) return true
            if (remotePlaylist.songs.map { it.identity() } != mergedPlaylist.songs.map { it.identity() }) return true
            for (i in remotePlaylist.songs.indices) {
                val remoteSong = remotePlaylist.songs[i]
                val mergedSong = mergedPlaylist.songs[i]
                if (!sameSongMetadata(remoteSong, mergedSong)) return true
            }
        }

        if (remote.favoritePlaylists.size != merged.favoritePlaylists.size) return true
        val remoteFavoriteMap = remote.favoritePlaylists.associateBy { "${it.id}_${it.source}" }
        val mergedFavoriteMap = merged.favoritePlaylists.associateBy { "${it.id}_${it.source}" }
        if (remoteFavoriteMap.keys != mergedFavoriteMap.keys) return true
        remoteFavoriteMap.forEach { (key, remoteFavorite) ->
            val mergedFavorite = mergedFavoriteMap[key] ?: return true
            if (remoteFavorite.isDeleted != mergedFavorite.isDeleted) return true
            if (remoteFavorite.modifiedAt != mergedFavorite.modifiedAt) return true
            if (remoteFavorite.sortOrder != mergedFavorite.sortOrder) return true
            if (remoteFavorite.trackCount != mergedFavorite.trackCount) return true
            if (remoteFavorite.songs.map { it.identity() } != mergedFavorite.songs.map { it.identity() }) return true
            for (i in remoteFavorite.songs.indices) {
                val remoteSong = remoteFavorite.songs[i]
                val mergedSong = mergedFavorite.songs[i]
                if (!sameSongMetadata(remoteSong, mergedSong)) return true
            }
        }

        if (recentPlaysChanged(remote.recentPlays, merged.recentPlays)) return true
        if (recentPlayDeletionsChanged(remote.recentPlayDeletions, merged.recentPlayDeletions)) return true
        if (playlistSongDeletionsChanged(remote.playlistSongDeletions, merged.playlistSongDeletions)) return true

        val remoteStats = remote.playbackStats.associateBy { it.identityKey }
        val mergedStats = merged.playbackStats.associateBy { it.identityKey }
        if (remote.playbackStatsClearedAt != merged.playbackStatsClearedAt) return true
        if (remoteStats.keys != mergedStats.keys) return true
        remoteStats.forEach { (key, remoteStat) ->
            val mergedStat = mergedStats[key] ?: return true
            if (!SyncPlaybackStatMapper.sameMetadata(remoteStat, mergedStat)) return true
        }

        val remoteBuckets = remote.playbackStatBuckets.associateBy { it.dayStartAt to it.identityKey }
        val mergedBuckets = merged.playbackStatBuckets.associateBy { it.dayStartAt to it.identityKey }
        if (remoteBuckets.keys != mergedBuckets.keys) return true
        remoteBuckets.forEach { (key, remoteBucket) ->
            val mergedBucket = mergedBuckets[key] ?: return true
            if (!SyncPlaybackStatMapper.sameMetadata(remoteBucket, mergedBucket)) return true
        }
        return false
    }

    private fun recentPlaysChanged(
        remoteRecent: List<SyncRecentPlay>,
        mergedRecent: List<SyncRecentPlay>
    ): Boolean {
        if (remoteRecent.size != mergedRecent.size) return true
        for (i in remoteRecent.indices) {
            if (remoteRecent[i].song.identity() != mergedRecent[i].song.identity()) return true
            if (!sameSongMetadata(remoteRecent[i].song, mergedRecent[i].song)) return true
            if (remoteRecent[i].playedAt != mergedRecent[i].playedAt) return true
        }
        return false
    }

    private fun recentPlayDeletionsChanged(
        remoteRecentDeletions: List<SyncRecentPlayDeletion>,
        mergedRecentDeletions: List<SyncRecentPlayDeletion>
    ): Boolean {
        if (remoteRecentDeletions.size != mergedRecentDeletions.size) return true
        for (i in remoteRecentDeletions.indices) {
            if (remoteRecentDeletions[i].identity() != mergedRecentDeletions[i].identity()) return true
            if (remoteRecentDeletions[i].deletedAt != mergedRecentDeletions[i].deletedAt) return true
            if (remoteRecentDeletions[i].deviceId != mergedRecentDeletions[i].deviceId) return true
        }
        return false
    }

    private fun playlistSongDeletionsChanged(
        remotePlaylistDeletions: List<SyncPlaylistSongDeletion>,
        mergedPlaylistDeletions: List<SyncPlaylistSongDeletion>
    ): Boolean {
        if (remotePlaylistDeletions.size != mergedPlaylistDeletions.size) return true
        for (i in remotePlaylistDeletions.indices) {
            if (remotePlaylistDeletions[i].playlistId != mergedPlaylistDeletions[i].playlistId) return true
            if (remotePlaylistDeletions[i].identity() != mergedPlaylistDeletions[i].identity()) return true
            if (remotePlaylistDeletions[i].deletedAt != mergedPlaylistDeletions[i].deletedAt) return true
            if (remotePlaylistDeletions[i].deviceId != mergedPlaylistDeletions[i].deviceId) return true
            if (
                remotePlaylistDeletions[i].removedMembershipTokens.orEmpty().toSet() !=
                mergedPlaylistDeletions[i].removedMembershipTokens.orEmpty().toSet()
            ) return true
        }
        return false
    }

    private fun sameSongMetadata(a: SyncSong, b: SyncSong): Boolean {
        return a.name == b.name &&
            a.artist == b.artist &&
            a.album == b.album &&
            a.albumId == b.albumId &&
            a.durationMs == b.durationMs &&
            a.coverUrl == b.coverUrl &&
            a.mediaUri == b.mediaUri &&
            a.addedAt == b.addedAt &&
            a.matchedLyric == b.matchedLyric &&
            a.matchedTranslatedLyric == b.matchedTranslatedLyric &&
            a.matchedLyricSource == b.matchedLyricSource &&
            a.matchedSongId == b.matchedSongId &&
            a.userLyricOffsetMs == b.userLyricOffsetMs &&
            a.customCoverUrl == b.customCoverUrl &&
            a.customName == b.customName &&
            a.customArtist == b.customArtist &&
            a.originalName == b.originalName &&
            a.originalArtist == b.originalArtist &&
            a.originalCoverUrl == b.originalCoverUrl &&
            a.originalLyric == b.originalLyric &&
            a.originalTranslatedLyric == b.originalTranslatedLyric &&
            a.channelId == b.channelId &&
            a.audioId == b.audioId &&
            a.subAudioId == b.subAudioId &&
            a.playlistContextId == b.playlistContextId &&
            a.syncMembershipTokens.orEmpty().toSet() == b.syncMembershipTokens.orEmpty().toSet()
    }
}
