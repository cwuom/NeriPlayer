package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef

class SubsonicBrowseRoomStore(private val store: PlatformPlaylistCacheRoomStore) : ServerBrowseStore {
    override suspend fun read(key: ServerBrowseKey): ServerBrowsePage? {
        val record = store.read(PLATFORM, key.cacheKey) ?: return null
        if (record.signaturePrimary != key.profileId || record.signatureSecondary != key.revision.toString()) return null
        return ServerBrowsePage(
            albums = if (key.kind == "albums") record.tracks.map { track ->
                ServerAlbum(key.profileId, requireNotNull(track.itemKey), track.name, track.artist,
                    track.coverUrl, track.addedAt.toInt())
            } else emptyList(),
            songs = if (key.kind == "album") record.tracks.map { track ->
                val ref = ServerSongRef(key.profileId, requireNotNull(track.itemKey))
                SongItem(id = ref.numericId, name = track.name, artist = track.artist, album = track.album,
                    albumId = track.albumId ?: 0L, durationMs = track.durationMs, coverUrl = track.coverUrl,
                    mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
            } else emptyList(), savedAtMs = record.savedAtMs, hasMore = record.hasMore == true)
    }

    override suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage) {
        val tracks = if (key.kind == "albums") page.albums.map {
            PlatformPlaylistCacheTrackRecord(itemKey = it.id, name = it.name, artist = it.artist,
                coverUrl = it.coverUrl, addedAt = it.songCount.toLong())
        } else page.songs.map {
            PlatformPlaylistCacheTrackRecord(itemKey = requireNotNull(ServerSongRef.from(it)).songId,
                name = it.name, artist = it.artist, album = it.album, albumId = it.albumId,
                durationMs = it.durationMs, coverUrl = it.coverUrl, audioId = it.audioId)
        }
        // One Room transaction stores the snapshot and enforces both record and item budgets.
        store.replaceBounded(PlatformPlaylistCacheRecord(platform = PLATFORM, cacheKey = key.cacheKey,
            kind = key.kind, signaturePrimary = key.profileId, signatureSecondary = key.revision.toString(),
            savedAtMs = page.savedAtMs, hasMore = page.hasMore, trackCount = tracks.size,
            totalCount = tracks.size, tracks = tracks), maxRecords = 128, maxTracks = 20_000)
    }

    override suspend fun retainProfiles(revisions: Map<String, Long>) {
        store.clearWhere(PLATFORM) { revisions[it.signaturePrimary]?.toString() != it.signatureSecondary }
    }

    override suspend fun clear() = store.clearSelected(listOf(PLATFORM))

    companion object { const val PLATFORM = "subsonic" }
}
