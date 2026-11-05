package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheTrackRecord
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef

class SubsonicBrowseRoomStore(private val store: PlatformPlaylistCacheRoomStore) : ServerBrowseStore {
    override suspend fun artistHeaders(profileId: String, revision: Long): Map<String, ServerArtist> =
        store.readHeaders(PLATFORM).mapNotNull { artistHeader(it, profileId, revision) }.associateBy { it.id }

    private fun artistHeader(record: PlatformPlaylistCacheRecord, profileId: String, revision: Long): ServerArtist? {
        if (record.kind != "artist" || record.signaturePrimary != profileId ||
            record.signatureSecondary != revision.toString()) return null
        val title = record.title ?: return null
        val id = record.alternateKey ?: record.cacheKey.split(':').takeIf { it.size == 6 }?.get(3)?.let {
            try { ServerSongRef.reference(profileId, it).songId } catch (_: IllegalArgumentException) { null }
        } ?: return null
        if (record.cacheKey != ServerBrowseKey(profileId, revision, "artist", id, size = 500).cacheKey) return null
        return ServerArtist(profileId, id, title, record.coverUrl,
            record.playCount?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: record.trackCount)
    }

    override suspend fun read(key: ServerBrowseKey): ServerBrowsePage? {
        val record = store.read(PLATFORM, key.cacheKey) ?: return null
        if (record.signaturePrimary != key.profileId || record.signatureSecondary != key.revision.toString()) return null
        val artistTitle = record.title
        return ServerBrowsePage(
            albums = if (key.kind in ALBUM_KINDS) record.tracks.map { track ->
                ServerAlbum(key.profileId, requireNotNull(track.itemKey), track.name, track.artist,
                    track.coverUrl, track.addedAt.toInt())
            } else emptyList(),
            songs = if (key.kind in SONG_KINDS) record.tracks.map { track ->
                val ref = ServerSongRef(key.profileId, requireNotNull(track.itemKey))
                SongItem(id = ref.numericId, name = track.name, artist = track.artist, album = track.album,
                    albumId = track.albumId ?: 0L, durationMs = track.durationMs, coverUrl = track.coverUrl,
                    mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
            } else emptyList(), savedAtMs = record.savedAtMs, hasMore = record.hasMore == true,
            artists = if (key.kind in ARTIST_KINDS) record.tracks.map { track ->
                ServerArtist(key.profileId, requireNotNull(track.itemKey), track.name,
                    track.coverUrl, track.addedAt.toInt())
            } else if (key.kind == "artist" && artistTitle != null) {
                listOf(ServerArtist(key.profileId, key.value, artistTitle, record.coverUrl,
                    record.playCount?.toInt() ?: record.tracks.size))
            } else emptyList(), artistArtwork = decodeArtistArtwork(record.subtitle))
    }

    override suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage) {
        val tracks = if (key.kind in ALBUM_KINDS) page.albums.map {
            PlatformPlaylistCacheTrackRecord(itemKey = it.id, name = it.name, artist = it.artist,
                coverUrl = it.coverUrl, addedAt = it.songCount.toLong())
        } else if (key.kind in ARTIST_KINDS) page.artists.map {
            PlatformPlaylistCacheTrackRecord(itemKey = it.id, name = it.name, artist = "",
                coverUrl = it.coverUrl, addedAt = it.albumCount.toLong())
        } else page.songs.map {
            PlatformPlaylistCacheTrackRecord(itemKey = requireNotNull(ServerSongRef.from(it)).songId,
                name = it.name, artist = it.artist, album = it.album, albumId = it.albumId,
                durationMs = it.durationMs, coverUrl = it.coverUrl, audioId = it.audioId)
        }
        val detailArtist = page.artists.singleOrNull()?.takeIf { key.kind == "artist" &&
            it.profileId == key.profileId && it.id == key.value }
        val record = PlatformPlaylistCacheRecord(platform = PLATFORM, cacheKey = key.cacheKey,
            kind = key.kind, signaturePrimary = key.profileId, signatureSecondary = key.revision.toString(),
            alternateKey = detailArtist?.id,
            title = detailArtist?.name, coverUrl = detailArtist?.coverUrl, playCount = detailArtist?.albumCount?.toLong(),
            subtitle = encodeArtistArtwork(page.artistArtwork),
            savedAtMs = page.savedAtMs, hasMore = page.hasMore, trackCount = tracks.size,
            totalCount = tracks.size, tracks = tracks)
        if (key.isCatalog) store.replace(record)
        else store.replaceBounded(record, maxRecords = 128, maxTracks = 20_000,
            protectedKinds = SERVER_CATALOG_KINDS)
    }

    override suspend fun retainProfiles(revisions: Map<String, Long>) {
        store.clearWhere(PLATFORM) { revisions[it.signaturePrimary]?.toString() != it.signatureSecondary }
    }

    override suspend fun clear() = store.clearSelected(listOf(PLATFORM))

    companion object {
        const val PLATFORM = "subsonic"
        private val ALBUM_KINDS = setOf("albums", "catalog-albums", "artist")
        private val SONG_KINDS = setOf("album", "songs", "catalog-songs")
        private val ARTIST_KINDS = setOf("artists", "catalog-artists")
    }
}
