package moe.ouom.neriplayer.data.local.database

import android.content.ContentValues
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import moe.ouom.neriplayer.data.local.database.entity.LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION

internal object StructuredSongRoomMigration : Migration(14, 15) {
    private val gson = Gson()

    override fun migrate(db: SupportSQLiteDatabase) {
        // copy child rows before rebuilding the parent table because Room enforces
        // the foreign key cascade while the migration transaction is active
        db.execSQL("DROP TABLE IF EXISTS playlist_member_backup")
        db.execSQL("CREATE TEMP TABLE playlist_member_backup AS SELECT * FROM playlist_member")
        db.execSQL("DROP TABLE IF EXISTS favorite_playlist_song_backup")
        db.execSQL(
            "CREATE TEMP TABLE favorite_playlist_song_backup AS " +
                "SELECT * FROM favorite_playlist_song"
        )
        db.execSQL("PRAGMA foreign_keys=OFF")
        migrateTrackTable(db)
        val trackData = loadTrackData(db)
        val memberArtists = migratePlaylistMemberTable(db, trackData)
        createPlaylistMemberArtistTable(db)
        memberArtists.forEach { artist ->
            db.insert(
                "playlist_member_netease_artist",
                0,
                artist.toContentValues()
            )
        }
        val favoriteArtists = migrateFavoritePlaylistSongTable(db)
        createFavoritePlaylistSongArtistTable(db)
        favoriteArtists.forEach { artist ->
            db.insert(
                "favorite_playlist_song_netease_artist",
                0,
                artist.toContentValues()
            )
        }
        db.execSQL("PRAGMA foreign_keys=ON")
    }

    private fun migrateTrackTable(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS track_new")
        db.execSQL(
            """
            CREATE TABLE track_new (
                identity_key TEXT NOT NULL,
                identity_id INTEGER NOT NULL,
                identity_album TEXT NOT NULL,
                identity_media_uri TEXT,
                song_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                album_id INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                cover_url TEXT,
                media_uri TEXT,
                matched_lyric TEXT,
                matched_translated_lyric TEXT,
                matched_lyric_source TEXT,
                matched_song_id TEXT,
                user_lyric_offset_ms INTEGER NOT NULL,
                custom_cover_url TEXT,
                custom_name TEXT,
                custom_artist TEXT,
                original_name TEXT,
                original_artist TEXT,
                original_cover_url TEXT,
                original_lyric TEXT,
                original_translated_lyric TEXT,
                channel_id TEXT,
                audio_id TEXT,
                sub_audio_id TEXT,
                source_stable_key TEXT,
                local_file_name TEXT,
                local_file_path TEXT,
                structured_schema_version INTEGER NOT NULL,
                PRIMARY KEY(identity_key)
            )
            """.trimIndent()
        )
        db.query(
            "SELECT * FROM track"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val fallback = StructuredSongData(
                    id = cursor.long("song_id"),
                    name = cursor.string("name").orEmpty(),
                    artist = cursor.string("artist").orEmpty(),
                    album = cursor.string("album").orEmpty(),
                    albumId = cursor.long("album_id"),
                    durationMs = cursor.long("duration_ms"),
                    coverUrl = cursor.string("cover_url"),
                    mediaUri = cursor.string("media_uri"),
                    channelId = cursor.string("channel_id"),
                    audioId = cursor.string("audio_id"),
                    subAudioId = cursor.string("sub_audio_id"),
                    sourceStableKey = cursor.string("source_stable_key"),
                    localFileName = cursor.string("local_file_name"),
                    localFilePath = cursor.string("local_file_path")
                )
                val song = parseSong(cursor.string("durable_payload_json"), fallback)
                val values = song.toTrackContentValues(
                    identityKey = cursor.string("identity_key").orEmpty(),
                    identityId = cursor.long("identity_id"),
                    identityAlbum = cursor.string("identity_album").orEmpty(),
                    identityMediaUri = cursor.string("identity_media_uri")
                )
                db.insert("track_new", 0, values)
            }
        }
        db.execSQL("DROP TABLE track")
        db.execSQL("ALTER TABLE track_new RENAME TO track")
        db.execSQL(
            "CREATE INDEX index_track_identity_parts ON track " +
                "(identity_id, identity_album, identity_media_uri)"
        )
        db.execSQL("CREATE INDEX index_track_name ON track (name)")
        db.execSQL("CREATE INDEX index_track_artist ON track (artist)")
    }

    private fun loadTrackData(db: SupportSQLiteDatabase): Map<String, StructuredSongData> {
        val result = LinkedHashMap<String, StructuredSongData>()
        db.query("SELECT * FROM track").use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.string("identity_key").orEmpty()] = StructuredSongData(
                    id = cursor.long("song_id"),
                    name = cursor.string("name").orEmpty(),
                    artist = cursor.string("artist").orEmpty(),
                    album = cursor.string("album").orEmpty(),
                    albumId = cursor.long("album_id"),
                    durationMs = cursor.long("duration_ms"),
                    coverUrl = cursor.string("cover_url"),
                    mediaUri = cursor.string("media_uri"),
                    matchedLyric = cursor.string("matched_lyric"),
                    matchedTranslatedLyric = cursor.string("matched_translated_lyric"),
                    matchedLyricSource = cursor.string("matched_lyric_source"),
                    matchedSongId = cursor.string("matched_song_id"),
                    userLyricOffsetMs = cursor.long("user_lyric_offset_ms"),
                    customCoverUrl = cursor.string("custom_cover_url"),
                    customName = cursor.string("custom_name"),
                    customArtist = cursor.string("custom_artist"),
                    originalName = cursor.string("original_name"),
                    originalArtist = cursor.string("original_artist"),
                    originalCoverUrl = cursor.string("original_cover_url"),
                    originalLyric = cursor.string("original_lyric"),
                    originalTranslatedLyric = cursor.string("original_translated_lyric"),
                    localFileName = cursor.string("local_file_name"),
                    localFilePath = cursor.string("local_file_path"),
                    channelId = cursor.string("channel_id"),
                    audioId = cursor.string("audio_id"),
                    subAudioId = cursor.string("sub_audio_id"),
                    sourceStableKey = cursor.string("source_stable_key")
                )
            }
        }
        return result
    }

    private fun migratePlaylistMemberTable(
        db: SupportSQLiteDatabase,
        trackData: Map<String, StructuredSongData>
    ): List<ArtistRow> {
        db.execSQL("DROP TABLE IF EXISTS playlist_member_new")
        db.execSQL(
            """
            CREATE TABLE playlist_member_new (
                playlist_id INTEGER NOT NULL,
                identity_key TEXT NOT NULL,
                display_position INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                order_tie_break INTEGER NOT NULL,
                playlist_context_id TEXT,
                song_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                album_id INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                cover_url TEXT,
                media_uri TEXT,
                matched_lyric TEXT,
                matched_translated_lyric TEXT,
                matched_lyric_source TEXT,
                matched_song_id TEXT,
                user_lyric_offset_ms INTEGER NOT NULL,
                custom_cover_url TEXT,
                custom_name TEXT,
                custom_artist TEXT,
                original_name TEXT,
                original_artist TEXT,
                original_cover_url TEXT,
                original_lyric TEXT,
                original_translated_lyric TEXT,
                local_file_name TEXT,
                local_file_path TEXT,
                channel_id TEXT,
                audio_id TEXT,
                sub_audio_id TEXT,
                source_stable_key TEXT,
                structured_schema_version INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, identity_key),
                FOREIGN KEY(playlist_id) REFERENCES local_playlist(playlist_id)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(identity_key) REFERENCES track(identity_key)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        val artists = ArrayList<ArtistRow>()
        db.query("SELECT * FROM playlist_member_backup").use { cursor ->
            while (cursor.moveToNext()) {
                val identityKey = cursor.string("identity_key").orEmpty()
                val fallback = trackData[identityKey] ?: StructuredSongData()
                val song = parseSong(cursor.string("member_payload_json"), fallback)
                val playlistId = cursor.long("playlist_id")
                val values = song.toMemberContentValues(
                    playlistId = playlistId,
                    identityKey = identityKey,
                    displayPosition = cursor.int("display_position"),
                    addedAt = cursor.long("added_at"),
                    orderTieBreak = cursor.int("order_tie_break"),
                    playlistContextId = cursor.string("playlist_context_id")
                )
                db.insert("playlist_member_new", 0, values)
                song.neteaseArtists.forEachIndexed { position, artist ->
                    artists += ArtistRow(
                        playlistId = playlistId,
                        identityKey = identityKey,
                        artistPosition = position,
                        artistId = artist.id,
                        artistName = artist.name
                    )
                }
            }
        }
        db.execSQL("DROP TABLE playlist_member")
        db.execSQL("ALTER TABLE playlist_member_new RENAME TO playlist_member")
        db.execSQL(
            "CREATE INDEX index_playlist_member_display_order ON playlist_member " +
                "(playlist_id, display_position ASC)"
        )
        db.execSQL(
            "CREATE INDEX index_playlist_member_added_order ON playlist_member " +
                "(playlist_id, added_at DESC, order_tie_break ASC)"
        )
        db.execSQL(
            "CREATE INDEX index_playlist_member_identity_key ON playlist_member " +
                "(identity_key)"
        )
        return artists
    }

    private fun createPlaylistMemberArtistTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE playlist_member_netease_artist (
                playlist_id INTEGER NOT NULL,
                identity_key TEXT NOT NULL,
                artist_position INTEGER NOT NULL,
                artist_id INTEGER NOT NULL,
                artist_name TEXT NOT NULL,
                PRIMARY KEY(playlist_id, identity_key, artist_position),
                FOREIGN KEY(playlist_id, identity_key)
                    REFERENCES playlist_member(playlist_id, identity_key)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX index_playlist_member_artist_member " +
                "ON playlist_member_netease_artist (playlist_id, identity_key)"
        )
    }

    private fun migrateFavoritePlaylistSongTable(db: SupportSQLiteDatabase): List<ArtistRow> {
        db.execSQL("DROP TABLE IF EXISTS favorite_playlist_song_new")
        db.execSQL(
            """
            CREATE TABLE favorite_playlist_song_new (
                playlist_id INTEGER NOT NULL,
                source TEXT NOT NULL,
                display_position INTEGER NOT NULL,
                id INTEGER NOT NULL,
                name TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT NOT NULL,
                album_id INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                cover_url TEXT,
                media_uri TEXT,
                matched_lyric TEXT,
                matched_translated_lyric TEXT,
                matched_lyric_source TEXT,
                matched_song_id TEXT,
                user_lyric_offset_ms INTEGER NOT NULL,
                custom_cover_url TEXT,
                custom_name TEXT,
                custom_artist TEXT,
                original_name TEXT,
                original_artist TEXT,
                original_cover_url TEXT,
                original_lyric TEXT,
                original_translated_lyric TEXT,
                local_file_name TEXT,
                local_file_path TEXT,
                channel_id TEXT,
                audio_id TEXT,
                sub_audio_id TEXT,
                playlist_context_id TEXT,
                source_stable_key TEXT,
                added_at INTEGER NOT NULL,
                structured_schema_version INTEGER NOT NULL,
                PRIMARY KEY(playlist_id, source, display_position),
                FOREIGN KEY(playlist_id, source)
                    REFERENCES favorite_playlist(playlist_id, source)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        val artists = ArrayList<ArtistRow>()
        db.query("SELECT * FROM favorite_playlist_song_backup").use { cursor ->
            while (cursor.moveToNext()) {
                val song = parseSong(cursor.string("song_payload_json"), StructuredSongData())
                val playlistId = cursor.long("playlist_id")
                val source = cursor.string("source").orEmpty()
                val displayPosition = cursor.int("display_position")
                db.insert(
                    "favorite_playlist_song_new",
                    0,
                    song.toFavoriteContentValues(
                        playlistId = playlistId,
                        source = source,
                        displayPosition = displayPosition
                    )
                )
                song.neteaseArtists.forEachIndexed { position, artist ->
                    artists += ArtistRow(
                        playlistId = playlistId,
                        source = source,
                        displayPosition = displayPosition,
                        artistPosition = position,
                        artistId = artist.id,
                        artistName = artist.name
                    )
                }
            }
        }
        db.execSQL("DROP TABLE favorite_playlist_song")
        db.execSQL("ALTER TABLE favorite_playlist_song_new RENAME TO favorite_playlist_song")
        db.execSQL(
            "CREATE INDEX index_favorite_playlist_song_order ON favorite_playlist_song " +
                "(playlist_id ASC, source ASC, display_position ASC)"
        )
        return artists
    }

    private fun createFavoritePlaylistSongArtistTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE favorite_playlist_song_netease_artist (
                playlist_id INTEGER NOT NULL,
                source TEXT NOT NULL,
                display_position INTEGER NOT NULL,
                artist_position INTEGER NOT NULL,
                artist_id INTEGER NOT NULL,
                artist_name TEXT NOT NULL,
                PRIMARY KEY(playlist_id, source, display_position, artist_position),
                FOREIGN KEY(playlist_id, source, display_position)
                    REFERENCES favorite_playlist_song(
                        playlist_id, source, display_position
                    ) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX index_favorite_playlist_song_artist_song " +
                "ON favorite_playlist_song_netease_artist " +
                "(playlist_id, source, display_position)"
        )
    }

    private fun parseSong(raw: String?, fallback: StructuredSongData): StructuredSongData {
        val objectValue = runCatching {
            gson.fromJson(raw.orEmpty(), JsonObject::class.java)
        }.getOrNull() ?: return fallback
        return fallback.copy(
            id = objectValue.long("id", fallback.id),
            name = objectValue.requiredString("name", fallback.name),
            artist = objectValue.requiredString("artist", fallback.artist),
            album = objectValue.requiredString("album", fallback.album),
            albumId = objectValue.long("albumId", fallback.albumId),
            durationMs = objectValue.long("durationMs", fallback.durationMs),
            coverUrl = objectValue.optionalString("coverUrl", fallback.coverUrl),
            mediaUri = objectValue.optionalString("mediaUri", fallback.mediaUri),
            matchedLyric = objectValue.optionalString("matchedLyric", fallback.matchedLyric),
            matchedTranslatedLyric = objectValue.optionalString(
                "matchedTranslatedLyric",
                fallback.matchedTranslatedLyric
            ),
            matchedLyricSource = objectValue.optionalString(
                "matchedLyricSource",
                fallback.matchedLyricSource
            ),
            matchedSongId = objectValue.optionalString("matchedSongId", fallback.matchedSongId),
            userLyricOffsetMs = objectValue.long(
                "userLyricOffsetMs",
                fallback.userLyricOffsetMs
            ),
            customCoverUrl = objectValue.optionalString(
                "customCoverUrl",
                fallback.customCoverUrl
            ),
            customName = objectValue.optionalString("customName", fallback.customName),
            customArtist = objectValue.optionalString("customArtist", fallback.customArtist),
            originalName = objectValue.optionalString("originalName", fallback.originalName),
            originalArtist = objectValue.optionalString("originalArtist", fallback.originalArtist),
            originalCoverUrl = objectValue.optionalString(
                "originalCoverUrl",
                fallback.originalCoverUrl
            ),
            originalLyric = objectValue.optionalString("originalLyric", fallback.originalLyric),
            originalTranslatedLyric = objectValue.optionalString(
                "originalTranslatedLyric",
                fallback.originalTranslatedLyric
            ),
            localFileName = objectValue.optionalString("localFileName", fallback.localFileName),
            localFilePath = objectValue.optionalString("localFilePath", fallback.localFilePath),
            channelId = objectValue.optionalString("channelId", fallback.channelId),
            audioId = objectValue.optionalString("audioId", fallback.audioId),
            subAudioId = objectValue.optionalString("subAudioId", fallback.subAudioId),
            playlistContextId = objectValue.optionalString(
                "playlistContextId",
                fallback.playlistContextId
            ),
            sourceStableKey = objectValue.optionalString(
                "sourceStableKey",
                fallback.sourceStableKey
            ),
            addedAt = objectValue.long("addedAt", fallback.addedAt),
            neteaseArtists = objectValue.artists(fallback.neteaseArtists)
        )
    }

    private fun JsonObject.requiredString(key: String, fallback: String): String {
        return optionalString(key, fallback).orEmpty()
    }

    private fun JsonObject.optionalString(key: String, fallback: String?): String? {
        if (!has(key)) return fallback
        val value = get(key) ?: return fallback
        return runCatching {
            if (value.isJsonNull) null else value.asString
        }.getOrNull()
    }

    private fun JsonObject.long(key: String, fallback: Long): Long {
        if (!has(key)) return fallback
        val value = get(key) ?: return fallback
        return runCatching {
            if (value.isJsonNull) fallback else value.asLong
        }.getOrDefault(fallback)
    }

    private fun JsonObject.artists(fallback: List<ArtistData>): List<ArtistData> {
        if (!has("neteaseArtists")) return fallback
        val array = get("neteaseArtists")
        if (array == null || array.isJsonNull) return emptyList()
        if (!array.isJsonArray) return fallback
        return array.asJsonArray.toArtistData()
    }

    private fun JsonArray.toArtistData(): List<ArtistData> {
        return mapNotNull { value ->
            runCatching {
                val objectValue = value.asJsonObject
                ArtistData(
                    id = objectValue.long("id", 0L),
                    name = objectValue.requiredString("name", "")
                )
            }.getOrNull()
        }
    }

    private fun StructuredSongData.toTrackContentValues(
        identityKey: String,
        identityId: Long,
        identityAlbum: String,
        identityMediaUri: String?
    ): ContentValues {
        return ContentValues().apply {
            put("identity_key", identityKey)
            put("identity_id", identityId)
            put("identity_album", identityAlbum)
            putText("identity_media_uri", identityMediaUri)
            putSongFields(this, includePlaylistContextId = false)
            put("structured_schema_version", LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION)
        }
    }

    private fun StructuredSongData.toMemberContentValues(
        playlistId: Long,
        identityKey: String,
        displayPosition: Int,
        addedAt: Long,
        orderTieBreak: Int,
        playlistContextId: String?
    ): ContentValues {
        return ContentValues().apply {
            put("playlist_id", playlistId)
            put("identity_key", identityKey)
            put("display_position", displayPosition)
            put("added_at", addedAt)
            put("order_tie_break", orderTieBreak)
            putText("playlist_context_id", playlistContextId)
            putSongFields(this, includePlaylistContextId = true)
            put("structured_schema_version", LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION)
        }
    }

    private fun StructuredSongData.toFavoriteContentValues(
        playlistId: Long,
        source: String,
        displayPosition: Int
    ): ContentValues {
        return ContentValues().apply {
            put("playlist_id", playlistId)
            put("source", source)
            put("display_position", displayPosition)
            putSongFields(this, includePlaylistContextId = true, idColumn = "id")
            put("added_at", addedAt)
            put("structured_schema_version", LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION)
        }
    }

    private fun StructuredSongData.putSongFields(
        values: ContentValues,
        includePlaylistContextId: Boolean,
        idColumn: String = "song_id"
    ) {
        values.put(idColumn, id)
        values.put("name", name)
        values.put("artist", artist)
        values.put("album", album)
        values.put("album_id", albumId)
        values.put("duration_ms", durationMs)
        values.putText("cover_url", coverUrl)
        values.putText("media_uri", mediaUri)
        values.putText("matched_lyric", matchedLyric)
        values.putText("matched_translated_lyric", matchedTranslatedLyric)
        values.putText("matched_lyric_source", matchedLyricSource)
        values.putText("matched_song_id", matchedSongId)
        values.put("user_lyric_offset_ms", userLyricOffsetMs)
        values.putText("custom_cover_url", customCoverUrl)
        values.putText("custom_name", customName)
        values.putText("custom_artist", customArtist)
        values.putText("original_name", originalName)
        values.putText("original_artist", originalArtist)
        values.putText("original_cover_url", originalCoverUrl)
        values.putText("original_lyric", originalLyric)
        values.putText("original_translated_lyric", originalTranslatedLyric)
        values.putText("local_file_name", localFileName)
        values.putText("local_file_path", localFilePath)
        values.putText("channel_id", channelId)
        values.putText("audio_id", audioId)
        values.putText("sub_audio_id", subAudioId)
        if (includePlaylistContextId) {
            values.putText("playlist_context_id", playlistContextId)
        }
        values.putText("source_stable_key", sourceStableKey)
    }

    private fun ContentValues.putText(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private data class StructuredSongData(
        val id: Long = 0L,
        val name: String = "",
        val artist: String = "",
        val album: String = "",
        val albumId: Long = 0L,
        val durationMs: Long = 0L,
        val coverUrl: String? = null,
        val mediaUri: String? = null,
        val matchedLyric: String? = null,
        val matchedTranslatedLyric: String? = null,
        val matchedLyricSource: String? = null,
        val matchedSongId: String? = null,
        val userLyricOffsetMs: Long = 0L,
        val customCoverUrl: String? = null,
        val customName: String? = null,
        val customArtist: String? = null,
        val originalName: String? = null,
        val originalArtist: String? = null,
        val originalCoverUrl: String? = null,
        val originalLyric: String? = null,
        val originalTranslatedLyric: String? = null,
        val localFileName: String? = null,
        val localFilePath: String? = null,
        val channelId: String? = null,
        val audioId: String? = null,
        val subAudioId: String? = null,
        val playlistContextId: String? = null,
        val sourceStableKey: String? = null,
        val addedAt: Long = 0L,
        val neteaseArtists: List<ArtistData> = emptyList()
    )

    private data class ArtistData(
        val id: Long,
        val name: String
    )

    private data class ArtistRow(
        val playlistId: Long,
        val identityKey: String? = null,
        val source: String? = null,
        val displayPosition: Int? = null,
        val artistPosition: Int,
        val artistId: Long,
        val artistName: String
    ) {
        fun toContentValues(): ContentValues {
            return ContentValues().apply {
                put("playlist_id", playlistId)
                identityKey?.let { put("identity_key", it) }
                source?.let { put("source", it) }
                displayPosition?.let { put("display_position", it) }
                put("artist_position", artistPosition)
                put("artist_id", artistId)
                put("artist_name", artistName)
            }
        }
    }

    private fun android.database.Cursor.string(name: String): String? =
        getString(getColumnIndexOrThrow(name))

    private fun android.database.Cursor.long(name: String): Long =
        getLong(getColumnIndexOrThrow(name))

    private fun android.database.Cursor.int(name: String): Int =
        getInt(getColumnIndexOrThrow(name))
}
