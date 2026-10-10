package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseArtistsFromSongJson
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.common.logging.NPLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val TAG = "NERI-ArtistVM"
private const val SONG_PAGE_SIZE = 50
private const val ALBUM_PAGE_SIZE = 30

data class NeteaseArtistHeader(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val avatarUrl: String,
    val alias: String,
    val briefDesc: String,
    val musicSize: Int,
    val albumSize: Int,
    val followed: Boolean
)

data class NeteaseArtistDetailUiState(
    val loading: Boolean = true,
    val followUpdating: Boolean = false,
    val error: String? = null,
    val header: NeteaseArtistHeader? = null,
    val songs: List<SongItem> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val songsHasMore: Boolean = false,
    val albumsHasMore: Boolean = false,
    val songsLoadingMore: Boolean = false,
    val albumsLoadingMore: Boolean = false
)

class NeteaseArtistDetailViewModel internal constructor(
    application: Application,
    private val client: NeteaseClient,
    private val favoriteRepo: FavoritePlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        AppContainer.neteaseClient,
        FavoritePlaylistRepository.getInstance(application)
    )
    private val _uiState = MutableStateFlow(NeteaseArtistDetailUiState())
    val uiState: StateFlow<NeteaseArtistDetailUiState> = _uiState

    private var artistId: Long = 0L
    private var songOffset: Int = 0
    private var albumOffset: Int = 0
    private var loadJob: Job? = null
    @Volatile
    private var artistGeneration = 0L

    init {
        viewModelScope.launch {
            favoriteRepo.favorites.collect { refreshFollowState() }
        }
    }

    fun start(summary: NeteaseArtistSummary, forceRefresh: Boolean = false) {
        if (!forceRefresh && shouldKeepCurrentArtist(summary.id)) {
            refreshFollowState()
            return
        }

        artistGeneration += 1L
        val generation = artistGeneration
        artistId = summary.id
        songOffset = 0
        albumOffset = 0
        loadJob?.cancel()
        _uiState.value = NeteaseArtistDetailUiState(
            loading = true,
            header = NeteaseArtistHeader(
                id = summary.id,
                name = summary.name,
                coverUrl = "",
                avatarUrl = "",
                alias = "",
                briefDesc = "",
                musicSize = 0,
                albumSize = 0,
                followed = favoriteRepo.isFavorite(summary.id, FAVORITE_SOURCE_NETEASE_ARTIST)
            )
        )

        loadJob = viewModelScope.launch {
            try {
                val loaded = loadInitial(summary)
                if (artistGeneration != generation) return@launch
                songOffset = loaded.songs.size
                albumOffset = loaded.albums.size
                publishLoadedArtist(summary.id, loaded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (artistGeneration != generation) return@launch
                NPLogger.e(TAG, "load artist failed", e)
                publishLoadFailure(e)
            }
        }
    }

    private fun publishLoadedArtist(artistId: Long, loaded: NeteaseArtistDetailUiState) {
        _uiState.update { current ->
            loaded.copy(
                loading = false,
                error = null,
                followUpdating = current.followUpdating,
                header = loaded.header?.copy(
                    followed = favoriteRepo.isFavorite(artistId, FAVORITE_SOURCE_NETEASE_ARTIST)
                )
            )
        }
    }

    private fun publishLoadFailure(error: Exception) {
        _uiState.update {
            it.copy(
                loading = false,
                error = getApplication<Application>().getString(
                    CoreCommonR.string.artist_load_failed,
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    fun retry() {
        val header = _uiState.value.header ?: return
        start(NeteaseArtistSummary(header.id, header.name), forceRefresh = true)
    }

    private fun shouldKeepCurrentArtist(summaryId: Long): Boolean {
        val current = _uiState.value
        return artistId == summaryId && current.header?.id == summaryId
    }

    private fun refreshFollowState() {
        _uiState.update { current ->
            val header = current.header ?: return@update current
            current.copy(
                header = header.copy(
                    followed = favoriteRepo.isFavorite(header.id, FAVORITE_SOURCE_NETEASE_ARTIST)
                )
            )
        }
    }

    fun loadMoreSongs() {
        if (artistId <= 0L || _uiState.value.songsLoadingMore || !_uiState.value.songsHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(songsLoadingMore = true) }
            runCatching {
                withContext(ioDispatcher) {
                    parseArtistSongs(client.getArtistSongs(artistId, offset = songOffset, limit = SONG_PAGE_SIZE))
                }
            }.onSuccess { page ->
                songOffset += page.items.size
                _uiState.update {
                    it.copy(
                        songs = it.songs + page.items,
                        songsHasMore = page.hasMore,
                        songsLoadingMore = false
                    )
                }
            }.onFailure { error ->
                NPLogger.e(TAG, "load more songs failed", error)
                _uiState.update { it.copy(songsLoadingMore = false) }
            }
        }
    }

    fun loadMoreAlbums() {
        if (artistId <= 0L || _uiState.value.albumsLoadingMore || !_uiState.value.albumsHasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(albumsLoadingMore = true) }
            runCatching {
                withContext(ioDispatcher) {
                    parseArtistAlbums(client.getArtistAlbums(artistId, offset = albumOffset, limit = ALBUM_PAGE_SIZE))
                }
            }.onSuccess { page ->
                albumOffset += page.items.size
                _uiState.update {
                    it.copy(
                        albums = it.albums + page.items,
                        albumsHasMore = page.hasMore,
                        albumsLoadingMore = false
                    )
                }
            }.onFailure { error ->
                NPLogger.e(TAG, "load more albums failed", error)
                _uiState.update { it.copy(albumsLoadingMore = false) }
            }
        }
    }

    fun toggleFollow() {
        val current = _uiState.value
        val header = current.header ?: return
        if (current.followUpdating) return
        val generation = artistGeneration
        _uiState.update { it.copy(followUpdating = true, error = null) }
        viewModelScope.launch {
            try {
                val followed = withContext(ioDispatcher) {
                    if (!favoriteRepo.awaitInitialized()) {
                        throw IOException("Favorites are not initialized")
                    }
                    if (artistGeneration != generation) return@withContext null
                    val targetFollowed = !favoriteRepo.isFavorite(header.id, FAVORITE_SOURCE_NETEASE_ARTIST)
                    if (targetFollowed) {
                        favoriteRepo.addFavorite(
                            id = header.id,
                            name = header.name,
                            coverUrl = preferredArtistCover(header),
                            trackCount = header.musicSize,
                            source = FAVORITE_SOURCE_NETEASE_ARTIST,
                            subtitle = preferredArtistSubtitle(header),
                            songs = emptyList()
                        )
                    } else {
                        favoriteRepo.removeFavorite(header.id, FAVORITE_SOURCE_NETEASE_ARTIST)
                    }
                    if (!favoriteRepo.awaitInitialized()) {
                        throw IOException("Artist follow change could not be saved")
                    }
                    favoriteRepo.isFavorite(header.id, FAVORITE_SOURCE_NETEASE_ARTIST)
                } ?: return@launch
                publishFollowResult(generation, header.id, followed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e(TAG, "toggle artist follow failed", error)
                publishFollowFailure(generation, header.id, error)
            }
        }
    }

    /** 关注写入期间切换了歌手时, 旧请求的结果不能落到新歌手上 */
    private fun currentFollowHeader(
        state: NeteaseArtistDetailUiState,
        generation: Long,
        headerId: Long
    ): NeteaseArtistHeader? {
        if (artistGeneration != generation) return null
        val header = state.header ?: return null
        return if (header.id == headerId) header else null
    }

    private fun publishFollowResult(generation: Long, headerId: Long, followed: Boolean) {
        _uiState.update { state ->
            val header = currentFollowHeader(state, generation, headerId) ?: return@update state
            state.copy(
                followUpdating = false,
                header = header.copy(followed = followed)
            )
        }
    }

    private fun publishFollowFailure(generation: Long, headerId: Long, error: Exception) {
        _uiState.update { state ->
            if (currentFollowHeader(state, generation, headerId) == null) return@update state
            state.copy(
                followUpdating = false,
                error = getApplication<Application>().getString(
                    CoreCommonR.string.artist_follow_failed,
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private suspend fun loadInitial(summary: NeteaseArtistSummary): NeteaseArtistDetailUiState = coroutineScope {
        val detailDeferred = async(ioDispatcher) { client.getArtistDetail(summary.id) }
        val songsDeferred = async(ioDispatcher) {
            client.getArtistSongs(summary.id, offset = 0, limit = SONG_PAGE_SIZE)
        }
        val albumsDeferred = async(ioDispatcher) {
            client.getArtistAlbums(summary.id, offset = 0, limit = ALBUM_PAGE_SIZE)
        }

        val detailHeader = parseArtistDetail(detailDeferred.await(), summary)
        val songsPage = parseArtistSongs(songsDeferred.await())
        val albumsPage = parseArtistAlbums(albumsDeferred.await())

        NeteaseArtistDetailUiState(
            loading = false,
            header = detailHeader.copy(
                followed = favoriteRepo.isFavorite(detailHeader.id, FAVORITE_SOURCE_NETEASE_ARTIST)
            ),
            songs = songsPage.items,
            albums = albumsPage.items,
            songsHasMore = songsPage.hasMore,
            albumsHasMore = albumsPage.hasMore
        )
    }

    private fun parseArtistDetail(raw: String, fallback: NeteaseArtistSummary): NeteaseArtistHeader {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(CoreCommonR.string.error_api_code, code) }

        val artist = root.optJSONObject("data")?.optJSONObject("artist")
            ?: root.optJSONObject("artist")
            ?: JSONObject()
        return NeteaseArtistHeader(
            id = artist.optLong("id", fallback.id),
            name = artist.optString("name", fallback.name).ifBlank { fallback.name },
            coverUrl = artist.httpsUrl(primary = "cover", fallback = "picUrl"),
            avatarUrl = artist.httpsUrl(primary = "avatar", fallback = "img1v1Url"),
            alias = artist.optJSONArray("alias").joinNames(),
            briefDesc = artist.optString("briefDesc", ""),
            musicSize = artist.optInt("musicSize", 0),
            albumSize = artist.optInt("albumSize", 0),
            followed = artist.optBoolean("followed", false)
        )
    }

    private fun JSONObject.httpsUrl(primary: String, fallback: String): String {
        return toHttps(optNonBlankString(primary) ?: optNonBlankString(fallback))
    }

    private fun parseArtistSongs(raw: String): Page<SongItem> {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(CoreCommonR.string.error_api_code, code) }

        val songs = root.optJSONArray("songs") ?: JSONArray()
        val items = ArrayList<SongItem>(songs.length())
        for (index in 0 until songs.length()) {
            val song = songs.optJSONObject(index) ?: continue
            parseSong(song)?.let(items::add)
        }
        return Page(items = items, hasMore = root.optBoolean("more", false))
    }

    private fun parseArtistAlbums(raw: String): Page<AlbumSummary> {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(CoreCommonR.string.error_api_code, code) }

        val albums = root.optJSONArray("hotAlbums") ?: JSONArray()
        val items = ArrayList<AlbumSummary>(albums.length())
        for (index in 0 until albums.length()) {
            val album = albums.optJSONObject(index) ?: continue
            val id = album.optLong("id", 0L)
            val name = album.optString("name", "")
            if (id <= 0L || name.isBlank()) continue
            items.add(
                AlbumSummary(
                    id = id,
                    name = name,
                    picUrl = toHttps(album.optString("picUrl", "")),
                    size = album.optInt("size", 0)
                )
            )
        }
        return Page(items = items, hasMore = root.optBoolean("more", false))
    }

    private fun parseSong(song: JSONObject): SongItem? {
        val id = song.optLong("id", 0L)
        val name = song.optString("name", "")
        if (id <= 0L || name.isBlank()) return null

        val artists = parseNeteaseArtistsFromSongJson(song)
        val album = song.optJSONObject("al") ?: song.optJSONObject("album") ?: JSONObject()
        val cover = toHttps(album.optString("picUrl", "")).takeIf { it.isNotBlank() }
        return SongItem(
            id = id,
            name = name,
            artist = artists.joinToString(" / ") { it.name },
            album = "${PlayerManager.NETEASE_SOURCE_TAG}${album.optString("name", "")}",
            albumId = album.optLong("id", 0L),
            durationMs = song.optLong("dt", 0L),
            coverUrl = cover,
            originalCoverUrl = cover,
            channelId = "netease",
            audioId = id.toString(),
            neteaseArtists = artists
        )
    }

    private fun JSONArray?.joinNames(): String {
        if (this == null) return ""
        val names = ArrayList<String>(length())
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(names::add)
        }
        return names.joinToString(" / ")
    }

    private fun JSONObject.optNonBlankString(name: String): String? {
        return optString(name, "").trim().takeIf { it.isNotBlank() }
    }

    private fun toHttps(url: String?): String {
        return url?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirst(Regex("^http://"), "https://")
            .orEmpty()
    }

    private fun preferredArtistCover(header: NeteaseArtistHeader): String? {
        return header.avatarUrl
            .takeIf { it.isNotBlank() }
            ?: header.coverUrl.takeIf { it.isNotBlank() }
    }

    private fun preferredArtistSubtitle(header: NeteaseArtistHeader): String? {
        return header.alias
            .takeIf { it.isNotBlank() }
            ?: header.briefDesc.takeIf { it.isNotBlank() }
    }

    private data class Page<T>(
        val items: List<T>,
        val hasMore: Boolean
    )
}
