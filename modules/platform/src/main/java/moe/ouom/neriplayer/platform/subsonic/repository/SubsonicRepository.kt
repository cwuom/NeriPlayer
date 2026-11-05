package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.lyrics.parser.convertPlainLyricsToEntries
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicCoverReference
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ServerAlbum(val profileId: String, val id: String, val name: String,
                       val artist: String, val coverUrl: String?, val songCount: Int)

data class ServerArtist(val profileId: String, val id: String, val name: String,
                        val coverUrl: String?, val albumCount: Int, val fallbackCoverUrl: String? = null) {
    val imageUrl: String? get() = coverUrl ?: fallbackCoverUrl
}

/** Minimal album/search/playback adapter using the application's existing song and lyric models. */
class SubsonicRepository(val accounts: SubsonicAccounts, private val client: SubsonicClient,
                        val browseCache: SubsonicBrowseCache = SubsonicBrowseCache()) {
    private val covers = SubsonicCoverProjection(accounts::portableCoverUrl)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            accounts.profiles.collect { profiles ->
                if (accounts.isLoaded) {
                    try { browseCache.retainProfiles(profiles.filter { it.enabled }.associate { it.id to it.revision }) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Storage failure must not disable server access. */ }
                }
            }
        }
    }

    fun browseKey(profileId: String, albumId: String? = null, query: String = "",
                  offset: Int = 0, size: Int = 30,
                  category: ServerLibraryCategory = ServerLibraryCategory.ALBUMS): ServerBrowseKey {
        val profile = accounts.profile(profileId) ?: throw SubsonicException.accountUnavailable()
        return ServerBrowseKey(profileId, profile.revision,
            if (albumId != null) "album" else category.browseKind(query),
            albumId ?: query.trim(), offset, size)
    }

    fun catalogKey(profileId: String, category: ServerLibraryCategory): ServerBrowseKey =
        browseKey(profileId, size = CATALOG_PAGE_SIZE, category = category).copy(kind = when (category) {
            ServerLibraryCategory.ALBUMS -> "catalog-albums"
            ServerLibraryCategory.SONGS -> "catalog-songs"
            ServerLibraryCategory.ARTISTS -> "catalog-artists"
        })

    fun artistAlbumsKey(profileId: String, artistId: String): ServerBrowseKey =
        browseKey(profileId, size = CATALOG_PAGE_SIZE).copy(kind = "artist", value = artistId)

    suspend fun cached(key: ServerBrowseKey): ServerBrowsePage? = withContext(Dispatchers.IO) {
        requireRevision(key)
        val result = browseCache.snapshot(key)?.let(covers::page)
        requireRevision(key)
        result
    }

    suspend fun catalog(profileId: String, category: ServerLibraryCategory,
                        force: Boolean = false): ServerBrowsePage = withContext(Dispatchers.IO) {
        val key = catalogKey(profileId, category)
        val legacyArtwork = category == ServerLibraryCategory.SONGS &&
            browseCache.snapshot(key)?.let { it.artistArtwork == null } == true
        val refresh = force || legacyArtwork
        val result = browseCache.fetch(key, refresh) {
            val page = when (category) {
                ServerLibraryCategory.ALBUMS -> ServerBrowsePage(albums = completeServerCatalog<ServerAlbum>({ it.id }) { offset ->
                    browse(key.copy(kind = "albums", offset = offset), refresh).albums
                })
                ServerLibraryCategory.SONGS -> {
                    val artwork = linkedMapOf<String, String>()
                    val songs = completeServerCatalog<SongItem>({ requireNotNull(ServerSongRef.from(it)).songId }) { offset ->
                        val page = browse(key.copy(kind = "songs", offset = offset), refresh)
                        artwork.putAll(page.artistArtwork.orEmpty())
                        page.songs
                    }
                    ServerBrowsePage(songs = songs, artistArtwork = artwork)
                }
                ServerLibraryCategory.ARTISTS -> ServerBrowsePage(artists = fetchArtists(profileId))
            }
            requireRevision(key)
            page
        }
        requireRevision(key)
        covers.page(result)
    }

    suspend fun artistAlbums(profileId: String, artistId: String,
                             force: Boolean = false): ServerBrowsePage = withContext(Dispatchers.IO) {
        val key = artistAlbumsKey(profileId, artistId)
        val result = browseCache.fetch(key, force) {
            val artist = call(profileId, "getArtist", mapOf("id" to artistId)).requiredEntity("artist", artistId)
            val page = ServerBrowsePage(albums = artist.collectionRows("album").map { mapAlbum(profileId, it) },
                artists = listOf(mapArtist(profileId, artist)))
            requireRevision(key)
            page
        }
        requireRevision(key)
        covers.page(result)
    }

    suspend fun cachedArtist(artist: ServerArtist): ServerArtist = withContext(Dispatchers.IO) {
        val rootKey = catalogKey(artist.profileId, ServerLibraryCategory.ARTISTS)
        val detailKey = rootKey.copy(kind = "artist", value = artist.id)
        val detail = browseCache.snapshot(detailKey)?.artists?.singleOrNull { it.id == artist.id }
        val root = browseCache.snapshot(rootKey)?.artists?.firstOrNull { it.id == artist.id }
        val artwork = browseCache.snapshot(rootKey.copy(kind = "catalog-songs"))?.artistArtwork
        requireRevision(rootKey)
        covers.artist(projectArtistArtwork(artist, root, detail, artwork))
    }

    suspend fun cachedArtists(profileId: String, artists: List<ServerArtist>): List<ServerArtist> = withContext(Dispatchers.IO) {
        val rootKey = catalogKey(profileId, ServerLibraryCategory.ARTISTS)
        if (artists.any { it.profileId != profileId }) throw SubsonicException.accountUnavailable()
        val roots = browseCache.snapshot(rootKey)?.artists.orEmpty().associateBy { it.id }
        val artwork = browseCache.snapshot(rootKey.copy(kind = "catalog-songs"))?.artistArtwork
        val details = browseCache.artistHeaders(profileId, rootKey.revision)
        val result = artists.map { covers.artist(projectArtistArtwork(it, roots[it.id], details[it.id], artwork)) }
        requireRevision(rootKey)
        result
    }

    suspend fun browse(key: ServerBrowseKey, force: Boolean = false): ServerBrowsePage = withContext(Dispatchers.IO) {
        requireRevision(key)
        val legacyArtwork = key.kind in setOf("songs", "search") &&
            browseCache.snapshot(key)?.let { it.artistArtwork == null } == true
        val result = browseCache.fetch(key, force || legacyArtwork) {
            val page = when (key.kind) {
                "album" -> ServerBrowsePage(songs = fetchAlbumSongs(key.profileId, key.value))
                "search", "songs" -> fetchSearch(key.profileId, key.value, key.offset, key.size)
                "search-albums" -> fetchAlbumSearch(key.profileId, key.value, key.offset, key.size).let {
                    ServerBrowsePage(albums = it, hasMore = it.size == key.size)
                }
                "artists" -> ServerBrowsePage(artists = fetchArtists(key.profileId))
                "search-artists" -> fetchArtistSearch(key.profileId, key.value, key.offset, key.size).let {
                    ServerBrowsePage(artists = it, hasMore = it.size == key.size)
                }
                else -> fetchAlbums(key.profileId, key.offset, key.size).let {
                    ServerBrowsePage(albums = it, hasMore = it.size == key.size)
                }
            }
            requireRevision(key)
            page
        }
        requireRevision(key)
        covers.page(result)
    }

    private fun requireRevision(key: ServerBrowseKey) {
        if (accounts.profile(key.profileId)?.revision != key.revision) throw SubsonicException.accountUnavailable()
    }
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
            require(updated.baseUrl == current.profile.baseUrl) { "更换服务器地址请新增配置" }
            val credential = password.ifEmpty { current.password }
            withTimeout(15_000L) { client.call(updated, credential, "ping") }
            accounts.save(updated, credential, expectedRevision = original.revision)
            lyricVersions.remove(original.id)
            lyricsCache.removeProfile(original.id)
            synchronized(audioMetadata) { audioMetadata.keys.removeAll { it.profileId == original.id } }
        }

    suspend fun albums(profileId: String, offset: Int, size: Int = 30): List<ServerAlbum> =
        browse(browseKey(profileId, offset = offset, size = size)).albums

    private suspend fun fetchAlbums(profileId: String, offset: Int, size: Int): List<ServerAlbum> {
        val data = call(profileId, "getAlbumList2", mapOf("type" to "alphabeticalByName",
            "offset" to offset.toString(), "size" to size.toString()))
            .requiredCollection("albumList2").collectionRows("album")
        return data.map { mapAlbum(profileId, it) }
    }

    suspend fun albumSongs(profileId: String, albumId: String): List<SongItem> =
        browse(browseKey(profileId, albumId = albumId)).songs

    private suspend fun fetchAlbumSongs(profileId: String, albumId: String): List<SongItem> =
        call(profileId, "getAlbum", mapOf("id" to albumId)).requiredEntity("album", albumId)
            .collectionRows("song").map { mapSong(profileId, it) }

    suspend fun search(profileId: String, query: String, offset: Int, size: Int = 30): List<SongItem> =
        browse(browseKey(profileId, query = query, offset = offset, size = size,
            category = ServerLibraryCategory.SONGS)).songs

    private suspend fun fetchAlbumSearch(profileId: String, query: String, offset: Int, size: Int): List<ServerAlbum> =
        call(profileId, "search3", mapOf("query" to query, "albumOffset" to offset.toString(),
            "albumCount" to size.toString(), "artistCount" to "0", "songCount" to "0"))
            .requiredCollection("searchResult3").collectionRows("album").map { mapAlbum(profileId, it) }

    private suspend fun fetchSearch(profileId: String, query: String, offset: Int, size: Int): ServerBrowsePage {
        val rows = call(profileId, "search3", mapOf("query" to query, "songOffset" to offset.toString(),
            "songCount" to size.toString(), "artistCount" to "0", "albumCount" to "0"))
            .requiredCollection("searchResult3").collectionRows("song")
        return ServerBrowsePage(songs = rows.map { mapSong(profileId, it) }, hasMore = rows.size == size,
            artistArtwork = songArtistArtwork(accounts.profile(profileId) ?: throw SubsonicException.accountUnavailable(), rows))
    }

    private suspend fun fetchArtists(profileId: String): List<ServerArtist> =
        call(profileId, "getArtists").requiredCollection("artists").collectionRows("index", requireId = false)
            .flatMap { it.collectionRows("artist") }.map { mapArtist(profileId, it) }.distinctBy { it.id }

    private suspend fun fetchArtistSearch(profileId: String, query: String, offset: Int, size: Int): List<ServerArtist> =
        call(profileId, "search3", mapOf("query" to query, "artistOffset" to offset.toString(),
            "artistCount" to size.toString(), "songCount" to "0", "albumCount" to "0"))
            .requiredCollection("searchResult3").collectionRows("artist").map { mapArtist(profileId, it) }

    private suspend fun fetchSong(ref: ServerSongRef): JSONObject =
        call(ref.profileId, "getSong", mapOf("id" to ref.songId)).requiredEntity("song", ref.songId)

    suspend fun song(ref: ServerSongRef): SongItem = mapSong(ref.profileId, fetchSong(ref))

    suspend fun download(song: SongItem): SubsonicDownloadSource {
        accounts.load()
        val ref = ServerSongRef.from(song) ?: throw SubsonicException(-1, "无效的服务器歌曲")
        if (accounts.profile(ref.profileId) == null) throw SubsonicException.accountUnavailable()
        // 下载重新读取原文件信息，避免沿用播放期间缓存的格式或长度
        val json = fetchSong(ref)
        val metadata = subsonicAudioMetadata(json)
        val suffix = json.optString("suffix").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
        return SubsonicDownloadSource(
            url = ref.resourceUrl("download"),
            mimeType = metadata.info.mimeType,
            fileExtensionHint = suffix,
            contentLength = metadata.size,
            durationMs = json.optLong("duration").takeIf { it in 1L..604_800L }?.times(1000L)
        )
    }

    /** Return the existing playback result with an opaque URL; authentication occurs on HTTP open. */
    suspend fun playback(song: SongItem, forceRefresh: Boolean = false): SongUrlResult {
        accounts.load()
        val ref = ServerSongRef.from(song) ?: return SongUrlResult.Failure
        val profile = accounts.profile(ref.profileId) ?: return SongUrlResult.RequiresLogin
        val cached = synchronized(audioMetadata) { audioMetadata[ref] }
            ?.takeIf { !forceRefresh && it.revision == profile.revision &&
                System.nanoTime() - it.savedAt < 300_000_000_000L }?.metadata
        val metadata = cached ?: subsonicAudioMetadata(fetchSong(ref))
            .also { rememberAudio(ref, profile.revision, it) }
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

    suspend fun lyrics(song: SongItem, forceRefresh: Boolean = false): List<LyricEntry> {
        accounts.load()
        val ref = ServerSongRef.from(song) ?: return emptyList()
        if (forceRefresh) lyricsCache.remove(ref) else cachedLyrics(song)?.let { return it }
        val revision = accounts.profile(ref.profileId)?.revision ?: return emptyList()
        val result = loadLyrics(song)
        if (accounts.profile(ref.profileId)?.revision == revision) lyricsCache.put(ref, result, revision)
        return result
    }

    private suspend fun loadLyrics(song: SongItem): List<LyricEntry> {
        val ref = ServerSongRef.from(song) ?: return emptyList()
        if (lyricsVersion(ref.profileId) < 1) return legacyLyrics(song, ref)
        val response = try {
            call(ref.profileId, "getLyricsBySongId", mapOf("id" to ref.songId))
        } catch (error: SubsonicException) {
            if (optionalLyricsUnavailable(error)) return legacyLyrics(song, ref)
            throw error
        }
        val tracks = response.requiredCollection("lyricsList").collectionRows("structuredLyrics", requireId = false)
        // Keep server order within each group; do not interpret another language as a translation.
        for (track in tracks.sortedByDescending { it.optBoolean("synced") }) {
            currentCoroutineContext().ensureActive()
            val parsed = parseLyricsTrack(track, song.durationMs)
            if (parsed.any { it.text.isNotBlank() }) return parsed
        }
        return emptyList()
    }

    private suspend fun legacyLyrics(song: SongItem, ref: ServerSongRef): List<LyricEntry> {
        if (song.name.isBlank() && song.artist.isBlank()) return emptyList()
        val response = try {
            call(ref.profileId, "getLyrics", buildMap {
                if (song.artist.isNotBlank()) put("artist", song.artist)
                if (song.name.isNotBlank()) put("title", song.name)
            })
        } catch (error: SubsonicException) {
            if (optionalLyricsUnavailable(error)) return emptyList()
            throw error
        }
        return parseLegacyServerLyrics(response, song.durationMs)
    }

    private fun optionalLyricsUnavailable(error: SubsonicException): Boolean =
        error.httpStatus in setOf(404, 501) || (error.httpStatus == null && error.code in setOf(0, 70))

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
                    (0 until versions.length()).map { versions.optInt(it, 0) }
                        .filter { it in 1..2 }.maxOrNull()
                } ?: 0
        } catch (error: SubsonicException) {
            if (optionalLyricsUnavailable(error)) 0 else throw error
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
            coverUrl = cover(profileId, json), mediaUri = ref.mediaUri,
            channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
    }

    private fun mapAlbum(profileId: String, json: JSONObject): ServerAlbum {
        val id = json.getString("id")
        return ServerAlbum(profileId, id, json.optString("name", id),
            json.optString("artist"), cover(profileId, json), json.optInt("songCount"))
    }

    private fun mapArtist(profileId: String, json: JSONObject): ServerArtist {
        val id = json.getString("id")
        return ServerArtist(profileId, id, json.optString("name", id),
            cover(profileId, json) ?: publicArtistImageUrl(json.opt("artistImageUrl") as? String),
            json.optInt("albumCount").coerceAtLeast(0))
    }

    private fun cover(profileId: String, json: JSONObject): String? =
        json.optString("coverArt").takeIf { it.isNotBlank() && it != "null" }
            ?.let { SubsonicCoverReference.create(accounts.profile(profileId) ?: throw SubsonicException.accountUnavailable(), it) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull { optJSONObject(it) }
}
