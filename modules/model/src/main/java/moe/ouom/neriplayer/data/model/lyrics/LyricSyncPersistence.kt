package moe.ouom.neriplayer.data.model.lyrics

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import com.google.gson.Gson

data class LyricSyncPersistence(
    val revision: Long = 0L,
    val edited: Boolean? = null,
    val romanized: String? = null,
    val originalRomanized: String? = null,
    val source: MusicPlatform? = null,
    val matchedSongId: String? = null,
    val userOffsetMs: Long = 0L
)

private val lyricPersistenceGson = Gson()

fun LyricSyncPersistence.toPersistenceJson(): String = lyricPersistenceGson.toJson(this)

fun readLyricSyncPersistence(json: String?): LyricSyncPersistence =
    if (json == null) LyricSyncPersistence() else requireNotNull(
        lyricPersistenceGson.fromJson(json, LyricSyncPersistence::class.java)
    ) { "Missing lyric persistence payload" }
