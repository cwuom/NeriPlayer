package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.lyrics.parser.convertPlainLyricsToEntries
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ServerAlbum(val profileId: String, val id: String, val name: String,
                       val artist: String, val coverUrl: String?, val songCount: Int)

/** Minimal album/search/playback adapter using the application's existing song and lyric models. */
class SubsonicRepository(val accounts: SubsonicAccounts, private val client: SubsonicClient) {
    private val lyricVersions = ConcurrentHashMap<String, Int>()

    suspend fun addAccount(label: String, address: String, username: String, password: String) {
        require(password.isNotEmpty()) { "请填写密码" }
        val profile = SubsonicAccounts.draft(label, address, username)
        withTimeout(15_000L) { client.call(profile, password, "ping") }
        accounts.save(profile, password)
    }

    suspend fun albums(profileId: String, offset: Int, size: Int = 30): List<ServerAlbum> {
        val data = call(profileId, "getAlbumList2", mapOf("type" to "alphabeticalByName",
            "offset" to offset.toString(), "size" to size.toString()))
            .optJSONObject("albumList2")?.optJSONArray("album") ?: JSONArray()
        return data.objects().map { json ->
            val id = json.getString("id")
            ServerAlbum(profileId, id, json.optString("name", id),
                json.optString("artist"), cover(profileId, id, json), json.optInt("songCount"))
        }
    }

    suspend fun albumSongs(profileId: String, albumId: String): List<SongItem> =
        call(profileId, "getAlbum", mapOf("id" to albumId)).getJSONObject("album")
            .optJSONArray("song").objects().map { mapSong(profileId, it) }

    suspend fun search(profileId: String, query: String, offset: Int, size: Int = 30): List<SongItem> =
        call(profileId, "search3", mapOf("query" to query, "songOffset" to offset.toString(),
            "songCount" to size.toString(), "artistCount" to "0", "albumCount" to "0"))
            .optJSONObject("searchResult3")?.optJSONArray("song").objects().map { mapSong(profileId, it) }

    suspend fun song(ref: ServerSongRef): SongItem = mapSong(ref.profileId,
        call(ref.profileId, "getSong", mapOf("id" to ref.songId)).getJSONObject("song"))

    fun playback(song: SongItem): SongUrlResult {
        val ref = ServerSongRef.from(song) ?: return SongUrlResult.Failure
        if (accounts.profile(ref.profileId) == null) return SongUrlResult.RequiresLogin
        return SongUrlResult.Success(url = ref.resourceUrl(),
            durationMs = song.durationMs.takeIf { it > 0L }, representationIdentity = "subsonic:raw:v1",
            cacheKeyOverride = ref.cacheKey)
    }

    suspend fun lyrics(song: SongItem): List<LyricEntry> {
        val ref = ServerSongRef.from(song) ?: return emptyList()
        if (lyricsVersion(ref.profileId) < 1) return emptyList()
        val tracks = call(ref.profileId, "getLyricsBySongId", mapOf("id" to ref.songId))
            .optJSONObject("lyricsList")?.optJSONArray("structuredLyrics").objects()
        val selected = tracks.firstOrNull { it.optBoolean("synced") && (it.optJSONArray("line")?.length() ?: 0) > 0 }
            ?: tracks.firstOrNull() ?: return emptyList()
        val lines = selected.optJSONArray("line").objects()
        if (!selected.optBoolean("synced")) {
            return convertPlainLyricsToEntries(lines.joinToString("\n") { it.optString("value") }, song.durationMs)
        }
        val offset = selected.optLong("offset", 0).coerceIn(-86_400_000L, 86_400_000L)
        val timed = lines.filter { it.has("start") }.map { line ->
            (line.optLong("start", 0).coerceIn(0, 604_800_000L) - offset).coerceAtLeast(0) to line.optString("value")
        }.sortedBy { it.first }
        return timed.mapIndexed { index, (start, text) ->
            val end = timed.drop(index + 1).firstOrNull { it.first > start }?.first
                ?: song.durationMs.takeIf { it > start } ?: (start + 5000)
            LyricEntry(text, start, end)
        }
    }

    private suspend fun lyricsVersion(profileId: String): Int {
        lyricVersions[profileId]?.let { return it }
        val version = try {
            call(profileId, "getOpenSubsonicExtensions").optJSONArray("openSubsonicExtensions")
                .objects().firstOrNull { it.optString("name") == "songLyrics" }
                ?.optJSONArray("versions")?.let { versions ->
                    (0 until versions.length()).maxOfOrNull { versions.optInt(it, 0) }
                } ?: 0
        } catch (error: SubsonicException) {
            if (error.code == 0 || error.code == 70 || error.code == 404) 0 else throw error
        }
        lyricVersions[profileId] = version
        return version
    }

    private suspend fun call(profileId: String, method: String,
                             parameters: Map<String, String> = emptyMap()): JSONObject {
        val profile = accounts.profile(profileId) ?: throw IllegalStateException("音乐服务器已移除或停用")
        return withTimeout(15_000L) { client.call(profile, accounts.password(profileId), method, parameters) }
    }

    private fun mapSong(profileId: String, json: JSONObject): SongItem {
        val id = json.getString("id")
        require(id.isNotEmpty()) { "服务器歌曲 ID 为空" }
        val ref = ServerSongRef(profileId, id)
        return SongItem(id = ref.numericId, name = json.optString("title", id),
            artist = json.optString("artist"), album = json.optString("album"),
            albumId = json.optString("albumId").takeIf { it.isNotBlank() }?.let {
                ServerSongRef.surrogateId("album:$profileId:$it")
            } ?: 0L,
            durationMs = json.optLong("duration", 0).coerceIn(0, 604800) * 1000L,
            coverUrl = cover(profileId, id, json), mediaUri = ref.mediaUri,
            channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
    }

    private fun cover(profileId: String, resourceId: String, json: JSONObject): String? =
        json.optString("coverArt").takeIf { it.isNotBlank() && it != "null" }
            ?.let { ServerSongRef(profileId, resourceId).resourceUrl("cover", it) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
}
