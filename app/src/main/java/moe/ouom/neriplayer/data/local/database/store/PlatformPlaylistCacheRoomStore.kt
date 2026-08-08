package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackArtistEntity
import moe.ouom.neriplayer.data.local.database.entity.PlatformPlaylistCacheTrackEntity

internal data class PlatformPlaylistCacheRecord(
    val platform: String,
    val cacheKey: String,
    val sourceId: Long? = null,
    val alternateKey: String? = null,
    val kind: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val creatorName: String? = null,
    val coverUrl: String? = null,
    val playCount: Long? = null,
    val trackCount: Int = 0,
    val totalCount: Int = 0,
    val signaturePrimary: String? = null,
    val signatureSecondary: String? = null,
    val hasMore: Boolean? = null,
    val savedAtMs: Long,
    val tracks: List<PlatformPlaylistCacheTrackRecord>
)

internal data class PlatformPlaylistCacheTrackRecord(
    val itemId: Long? = null,
    val itemKey: String? = null,
    val name: String,
    val artist: String,
    val album: String = "",
    val albumId: Long? = null,
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val audioId: String? = null,
    val uploaderMid: Long? = null,
    val addedAt: Long = 0L,
    val artists: List<PlatformPlaylistCacheArtistRecord> = emptyList()
)

internal data class PlatformPlaylistCacheArtistRecord(
    val id: Long,
    val name: String
)

internal class PlatformPlaylistCacheRoomStore(
    private val database: NeriUserDataDatabase
) {
    suspend fun read(
        platform: String,
        cacheKey: String
    ): PlatformPlaylistCacheRecord? {
        return database.withTransaction {
            val dao = database.platformPlaylistCacheDao()
            val cache = dao.getCache(platform, cacheKey) ?: return@withTransaction null
            val tracks = dao.getTracks(platform, cacheKey)
            val artistsByTrack = dao.getArtists(platform, cacheKey)
                .groupBy(PlatformPlaylistCacheTrackArtistEntity::trackPosition)
            cache.toRecord(
                tracks = tracks.map { track ->
                    track.toRecord(
                        artists = artistsByTrack[track.position]
                            .orEmpty()
                            .map { artist -> artist.toRecord() }
                    )
                }
            )
        }
    }

    suspend fun replace(record: PlatformPlaylistCacheRecord) {
        database.withTransaction {
            val dao = database.platformPlaylistCacheDao()
            dao.deleteArtists(record.platform, record.cacheKey)
            dao.deleteTracks(record.platform, record.cacheKey)
            dao.upsertCache(record.toEntity())
            dao.insertTracks(record.toTrackEntities())
            dao.insertArtists(record.toArtistEntities())
        }
    }

    suspend fun replaceIfNewer(record: PlatformPlaylistCacheRecord) {
        database.withTransaction {
            val dao = database.platformPlaylistCacheDao()
            val existing = dao.getCache(record.platform, record.cacheKey)
            if (existing != null && existing.savedAtMs > record.savedAtMs) {
                return@withTransaction
            }
            dao.deleteArtists(record.platform, record.cacheKey)
            dao.deleteTracks(record.platform, record.cacheKey)
            dao.upsertCache(record.toEntity())
            dao.insertTracks(record.toTrackEntities())
            dao.insertArtists(record.toArtistEntities())
        }
    }

    suspend fun clear(
        platform: String,
        cacheKey: String
    ) {
        database.withTransaction {
            val dao = database.platformPlaylistCacheDao()
            dao.deleteArtists(platform, cacheKey)
            dao.deleteTracks(platform, cacheKey)
            dao.deleteCache(platform, cacheKey)
        }
    }

    private fun PlatformPlaylistCacheRecord.toEntity(): PlatformPlaylistCacheEntity {
        return PlatformPlaylistCacheEntity(
            platform = platform,
            cacheKey = cacheKey,
            sourceId = sourceId,
            alternateKey = alternateKey,
            kind = kind,
            title = title,
            subtitle = subtitle,
            creatorName = creatorName,
            coverUrl = coverUrl,
            playCount = playCount,
            trackCount = trackCount,
            totalCount = totalCount,
            signaturePrimary = signaturePrimary,
            signatureSecondary = signatureSecondary,
            hasMore = hasMore,
            savedAtMs = savedAtMs
        )
    }

    private fun PlatformPlaylistCacheRecord.toTrackEntities(): List<PlatformPlaylistCacheTrackEntity> {
        return tracks.mapIndexed { index, track ->
            PlatformPlaylistCacheTrackEntity(
                platform = platform,
                cacheKey = cacheKey,
                position = index,
                itemId = track.itemId,
                itemKey = track.itemKey,
                name = track.name,
                artist = track.artist,
                album = track.album,
                albumId = track.albumId,
                durationMs = track.durationMs,
                coverUrl = track.coverUrl,
                audioId = track.audioId,
                uploaderMid = track.uploaderMid,
                addedAt = track.addedAt
            )
        }
    }

    private fun PlatformPlaylistCacheRecord.toArtistEntities(): List<PlatformPlaylistCacheTrackArtistEntity> {
        return tracks.flatMapIndexed { trackIndex, track ->
            track.artists.mapIndexed { artistIndex, artist ->
                PlatformPlaylistCacheTrackArtistEntity(
                    platform = platform,
                    cacheKey = cacheKey,
                    trackPosition = trackIndex,
                    artistPosition = artistIndex,
                    artistId = artist.id,
                    name = artist.name
                )
            }
        }
    }

    private fun PlatformPlaylistCacheEntity.toRecord(
        tracks: List<PlatformPlaylistCacheTrackRecord>
    ): PlatformPlaylistCacheRecord {
        return PlatformPlaylistCacheRecord(
            platform = platform,
            cacheKey = cacheKey,
            sourceId = sourceId,
            alternateKey = alternateKey,
            kind = kind,
            title = title,
            subtitle = subtitle,
            creatorName = creatorName,
            coverUrl = coverUrl,
            playCount = playCount,
            trackCount = trackCount,
            totalCount = totalCount,
            signaturePrimary = signaturePrimary,
            signatureSecondary = signatureSecondary,
            hasMore = hasMore,
            savedAtMs = savedAtMs,
            tracks = tracks
        )
    }

    private fun PlatformPlaylistCacheTrackEntity.toRecord(
        artists: List<PlatformPlaylistCacheArtistRecord>
    ): PlatformPlaylistCacheTrackRecord {
        return PlatformPlaylistCacheTrackRecord(
            itemId = itemId,
            itemKey = itemKey,
            name = name,
            artist = artist,
            album = album,
            albumId = albumId,
            durationMs = durationMs,
            coverUrl = coverUrl,
            audioId = audioId,
            uploaderMid = uploaderMid,
            addedAt = addedAt,
            artists = artists
        )
    }

    private fun PlatformPlaylistCacheTrackArtistEntity.toRecord(): PlatformPlaylistCacheArtistRecord {
        return PlatformPlaylistCacheArtistRecord(
            id = artistId,
            name = name
        )
    }
}
