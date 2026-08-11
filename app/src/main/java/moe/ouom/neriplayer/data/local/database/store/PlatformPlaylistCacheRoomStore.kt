package moe.ouom.neriplayer.data.local.database.store

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
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

internal data class PlatformPlaylistCacheStorageStats(
    val cacheRecordCount: Int,
    val allocatedPageBytes: Long
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

    suspend fun clearSelected(platforms: List<String>) {
        if (platforms.isEmpty()) return
        database.withTransaction {
            val dao = database.platformPlaylistCacheDao()
            dao.deleteArtistsForPlatforms(platforms)
            dao.deleteTracksForPlatforms(platforms)
            dao.deleteCachesForPlatforms(platforms)
        }
    }

    suspend fun countSelected(platforms: List<String>): Int {
        if (platforms.isEmpty()) return 0
        return database.platformPlaylistCacheDao().countCachesForPlatforms(platforms)
    }

    fun storageStats(
        platforms: List<String>
    ): Map<String, PlatformPlaylistCacheStorageStats> {
        val selectedPlatforms = platforms.distinct()
        if (selectedPlatforms.isEmpty()) return emptyMap()

        val sqliteDatabase = database.openHelper.readableDatabase
        val recordCounts = readLongByPlatform(
            sqliteDatabase,
            "SELECT platform, COUNT(*) " +
                "FROM platform_playlist_cache GROUP BY platform"
        )
        val payloadBytesByPlatform = PLATFORM_CACHE_TABLES.fold(
            emptyMap<String, Long>()
        ) { current, table ->
            val tablePayloadBytes = readLongByPlatform(
                sqliteDatabase,
                "SELECT platform, COALESCE(SUM(${table.payloadExpression}), 0) " +
                    "FROM ${table.name} GROUP BY platform"
            )
            buildMap {
                putAll(current)
                tablePayloadBytes.forEach { (platform, bytes) ->
                    put(platform, (get(platform) ?: 0L) + bytes)
                }
            }
        }
        val allocatedPageBytesByPlatform = allocateCachePageBytes(
            totalPageBytes = cacheTablePageBytes(sqliteDatabase)?.takeIf { it > 0L }
                ?: estimatedCacheTablePageBytes(sqliteDatabase, payloadBytesByPlatform),
            payloadBytesByPlatform = payloadBytesByPlatform
        )

        return selectedPlatforms.associateWith { platform ->
            PlatformPlaylistCacheStorageStats(
                cacheRecordCount = recordCounts[platform]?.toInt() ?: 0,
                allocatedPageBytes = allocatedPageBytesByPlatform[platform] ?: 0L
            )
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

private data class PlatformCacheTable(
    val name: String,
    val payloadExpression: String
)

private val PLATFORM_CACHE_TABLES = listOf(
    PlatformCacheTable(
        name = "platform_playlist_cache",
        payloadExpression =
            "32 + length(platform) + length(cache_key) + " +
                "COALESCE(length(CAST(source_id AS TEXT)), 0) + " +
                "COALESCE(length(alternate_key), 0) + COALESCE(length(kind), 0) + " +
                "COALESCE(length(title), 0) + COALESCE(length(subtitle), 0) + " +
                "COALESCE(length(creator_name), 0) + COALESCE(length(cover_url), 0) + " +
                "COALESCE(length(CAST(play_count AS TEXT)), 0) + " +
                "COALESCE(length(CAST(track_count AS TEXT)), 0) + " +
                "COALESCE(length(CAST(total_count AS TEXT)), 0) + " +
                "COALESCE(length(signature_primary), 0) + " +
                "COALESCE(length(signature_secondary), 0) + " +
                "COALESCE(length(CAST(has_more AS TEXT)), 0) + " +
                "length(CAST(saved_at_ms AS TEXT))"
    ),
    PlatformCacheTable(
        name = "platform_playlist_cache_track",
        payloadExpression =
            "32 + length(platform) + length(cache_key) + " +
                "length(CAST(position AS TEXT)) + " +
                "COALESCE(length(CAST(item_id AS TEXT)), 0) + " +
                "COALESCE(length(item_key), 0) + length(name) + length(artist) + " +
                "length(album) + COALESCE(length(CAST(album_id AS TEXT)), 0) + " +
                "length(CAST(duration_ms AS TEXT)) + COALESCE(length(cover_url), 0) + " +
                "COALESCE(length(audio_id), 0) + " +
                "COALESCE(length(CAST(uploader_mid AS TEXT)), 0) + " +
                "length(CAST(added_at AS TEXT))"
    ),
    PlatformCacheTable(
        name = "platform_playlist_cache_track_artist",
        payloadExpression =
            "24 + length(platform) + length(cache_key) + " +
                "length(CAST(track_position AS TEXT)) + " +
                "length(CAST(artist_position AS TEXT)) + " +
                "length(CAST(artist_id AS TEXT)) + length(name)"
    )
)

private fun readLongByPlatform(
    database: SupportSQLiteDatabase,
    query: String
): Map<String, Long> {
    return database.query(query).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getString(0), cursor.getLong(1))
            }
        }
    }
}

private fun cacheTablePageBytes(database: SupportSQLiteDatabase): Long? {
    return runCatching {
        database.query(
            "SELECT COALESCE(SUM(pgsize), 0) FROM dbstat " +
                "WHERE name IN (" +
                "SELECT name FROM sqlite_master " +
                "WHERE type IN ('table', 'index') AND tbl_name IN (" +
                PLATFORM_CACHE_TABLES.joinToString(",") { "'${it.name}'" } +
                ")" +
                ")"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }.getOrNull()
}

private fun estimatedCacheTablePageBytes(
    database: SupportSQLiteDatabase,
    payloadBytesByPlatform: Map<String, Long>
): Long {
    val payloadBytes = payloadBytesByPlatform.values.sum()
    if (payloadBytes <= 0L) return 0L
    val pageSize = database.pragmaLong("page_size").coerceAtLeast(1L)
    val roundedPayloadBytes = ((payloadBytes + pageSize - 1L) / pageSize) * pageSize
    return roundedPayloadBytes.coerceAtLeast(pageSize)
}

private fun allocateCachePageBytes(
    totalPageBytes: Long,
    payloadBytesByPlatform: Map<String, Long>
): Map<String, Long> {
    if (totalPageBytes <= 0L) return emptyMap()
    val weightedPlatforms = payloadBytesByPlatform
        .filterValues { it > 0L }
        .toSortedMap()
    val totalPayloadBytes = weightedPlatforms.values.sum()
    if (totalPayloadBytes <= 0L) return emptyMap()

    var remainingBytes = totalPageBytes
    var remainingPayloadBytes = totalPayloadBytes
    return buildMap {
        weightedPlatforms.entries.forEachIndexed { index, (platform, payloadBytes) ->
            val allocatedBytes = if (index == weightedPlatforms.size - 1) {
                remainingBytes
            } else {
                (remainingBytes.toDouble() * payloadBytes / remainingPayloadBytes)
                    .toLong()
                    .coerceIn(0L, remainingBytes)
            }
            put(platform, allocatedBytes)
            remainingBytes -= allocatedBytes
            remainingPayloadBytes -= payloadBytes
        }
    }
}

private fun SupportSQLiteDatabase.pragmaLong(name: String): Long {
    return query("PRAGMA $name").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
