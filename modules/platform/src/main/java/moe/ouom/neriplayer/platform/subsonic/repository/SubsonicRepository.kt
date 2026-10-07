package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.lyrics.parser.convertPlainLyricsToEntries
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ServerAlbum(val profileId: String, val id: String, val name: String,
                       val artist: String, val coverUrl: String?, val songCount: Int)

/** Minimal album/search/playback adapter using the application's existing song and lyric models. */
class SubsonicRepository(val accounts: SubsonicAccounts, private val client: SubsonicClient) {
    private val lyricVersions = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val lyricsCache = SubsonicLyricsCache()
    private data class AudioEntry(val revision: Long, val savedAt: Long, val metadata: SubsonicAudioMetadata)
    private val audioMetadata = LinkedHashMap<ServerSongRef, AudioEntry>(256, 0.75f, true)

    suspend fun addAccount(label: String, address: String, username: String, password: String) {
        require(password.isNotEmpty()) { "请填写密码" }
        val profile = SubsonicAccounts.draft(label, address, username)
        withTimeout(15_000L) { client.call(profile, password, "ping") }
        accounts.save(profile, password)
    }

    suspend fun updateAccount(original: SubsonicProfile, label: String, address: String, password: String) =
        withContext(Dispatchers.IO) {
            val current = accounts.credentials(original.id)
            if (current.profile.revision != original.revision) {
                throw SubsonicException(-1, "Configuration changed", SubsonicFailureKind.CONFIG_CHANGED)
            }
            val updated = SubsonicAccounts.draft(label, address, original.username).copy(
                id = original.id, enabled = original.enabled, revision = original.revision + 1L)
            val credential = password.ifEmpty { current.password }
            withTimeout(15_000L) { client.call(updated, credential, "ping") }
            accounts.save(updated, credential, expectedRevision = original.revision)
            lyricVersions.remove(original.id)
            lyricsCache.removeProfile(original.id)
            synchronized(audioMetadata) { audioMetadata.keys.removeAll { it.profileId == original.id } }
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

    suspend fun playback(song: SongItem, forceRefresh: Boolean = false): SongUrlResult {
        accounts.load()
        val ref = ServerSongRef.from(song) ?: return SongUrlResult.Failure
        val profile = accounts.profile(ref.profileId) ?: return SongUrlResult.RequiresLogin
        val cached = synchronized(audioMetadata) { audioMetadata[ref] }
            ?.takeIf { !forceRefresh && it.revision == profile.revision &&
                System.nanoTime() - it.savedAt < 300_000_000_000L }?.metadata
        val metadata = cached ?: subsonicAudioMetadata(
            call(ref.profileId, "getSong", mapOf("id" to ref.songId)).getJSONObject("song")
        ).also { rememberAudio(ref, profile.revision, it) }
        return SongUrlResult.Success(url = ref.resourceUrl(),
            durationMs = song.durationMs.takeIf { it > 0L }, representationIdentity = metadata.representation,
            cacheKeyOverride = ref.cacheKey, audioInfo = metadata.info, mimeType = metadata.info.mimeType,
            expectedContentLength = metadata.size)
    }

    private fun rememberAudio(ref: ServerSongRef, revision: Long, metadata: SubsonicAudioMetadata) {
        synchronized(audioMetadata) {
            audioMetadata[ref] = AudioEntry(revision, System.nanoTime(), metadata)
            while (audioMetadata.size > 256) audioMetadata.remove(audioMetadata.keys.first())
        }
    }

    fun cachedLyrics(song: SongItem): List<LyricEntry>? {
        val ref = ServerSongRef.from(song) ?: return null
        val profile = accounts.profile(ref.profileId) ?: return null
        return lyricsCache.get(ref, profile.revision)
    }

    suspend fun lyrics(song: SongItem): List<LyricEntry> {
        accounts.load()
        cachedLyrics(song)?.let { return it }
        val ref = ServerSongRef.from(song) ?: return emptyList()
        val revision = accounts.profile(ref.profileId)?.revision ?: return emptyList()
        val result = loadLyrics(song)
        if (accounts.profile(ref.profileId)?.revision == revision) lyricsCache.put(ref, result, revision)
        return result
    }

    private suspend fun loadLyrics(song: SongItem): List<LyricEntry> {
        val ref = ServerSongRef.from(song) ?: return emptyList()
        if (lyricsVersion(ref.profileId) < 1) return emptyList()
        val tracks = call(ref.profileId, "getLyricsBySongId", mapOf("id" to ref.songId))
            .optJSONObject("lyricsList")?.optJSONArray("structuredLyrics").objects()
        // Keep server order within each group; do not interpret another language as a translation.
        for (track in tracks.sortedByDescending { it.optBoolean("synced") }) {
            currentCoroutineContext().ensureActive()
            val parsed = parseLyricsTrack(track, song.durationMs)
            if (parsed.any { it.text.isNotBlank() }) return parsed
        }
        return emptyList()
    }

    private fun parseLyricsTrack(selected: JSONObject, durationMs: Long): List<LyricEntry> {
        val lines = selected.optJSONArray("line").objects()
        if (!selected.optBoolean("synced")) {
            val text = lines.joinToString("\n") { it.optString("value") }
            return if (text.isBlank()) emptyList() else convertPlainLyricsToEntries(text, durationMs)
        }
        val offset = selected.optLong("offset", 0).coerceIn(-86_400_000L, 86_400_000L)
        val timed = lines.filter { it.optLong("start", -1) >= 0L && it.optString("value").isNotBlank() }.map { line ->
            (line.optLong("start", 0).coerceIn(0, 604_800_000L) - offset).coerceAtLeast(0) to line.optString("value")
        }.sortedBy { it.first }
        val result = ArrayList<LyricEntry>(timed.size)
        var nextStart: Long? = null
        for (index in timed.indices.reversed()) {
            val (start, text) = timed[index]
            if (index < timed.lastIndex && timed[index + 1].first > start) nextStart = timed[index + 1].first
            result.add(LyricEntry(text, start, nextStart ?: durationMs.takeIf { it > start } ?: (start + 5000)))
        }
        result.reverse()
        return result
    }

    private suspend fun lyricsVersion(profileId: String): Int {
        val revision = accounts.profile(profileId)?.revision ?: throw SubsonicException.accountUnavailable()
        lyricVersions[profileId]?.takeIf { it.first == revision }?.let { return it.second }
        val version = try {
            call(profileId, "getOpenSubsonicExtensions").optJSONArray("openSubsonicExtensions")
                .objects().firstOrNull { it.optString("name") == "songLyrics" }
                ?.optJSONArray("versions")?.let { versions ->
                    (0 until versions.length()).maxOfOrNull { versions.optInt(it, 0) }
                } ?: 0
        } catch (error: SubsonicException) {
            if (error.code == 0 || error.code == 70 || error.code == 404) 0 else throw error
        }
        if (accounts.profile(profileId)?.revision != revision) throw SubsonicException.accountUnavailable()
        lyricVersions[profileId] = revision to version
        return version
    }

    private suspend fun call(profileId: String, method: String,
                             parameters: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val credentials = try { accounts.credentials(profileId) }
        catch (_: IllegalStateException) { throw SubsonicException.accountUnavailable() }
        val result = try {
            withTimeout(15_000L) { client.call(credentials.profile, credentials.password, method, parameters) }
        } catch (timeout: TimeoutCancellationException) {
            // Preserve cancellation of the calling screen/song, but expose our own request timeout.
            currentCoroutineContext().ensureActive()
            throw SubsonicException(-1, "Request timed out", SubsonicFailureKind.TIMEOUT)
        }
        if (accounts.profile(profileId)?.revision != credentials.profile.revision) {
            throw SubsonicException.accountUnavailable()
        }
        result
    }

    private fun mapSong(profileId: String, json: JSONObject): SongItem {
        val id = json.getString("id")
        require(id.isNotEmpty()) { "服务器歌曲 ID 为空" }
        val ref = ServerSongRef(profileId, id)
        accounts.profile(profileId)?.let { rememberAudio(ref, it.revision, subsonicAudioMetadata(json)) }
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
