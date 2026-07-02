package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.util.NPLogger
import org.json.JSONArray
import org.json.JSONObject

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

class NeteaseArtistDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.neteaseClient
    private val favoriteRepo = FavoritePlaylistRepository.getInstance(application)
    private val _uiState = MutableStateFlow(NeteaseArtistDetailUiState())
    val uiState: StateFlow<NeteaseArtistDetailUiState> = _uiState

    private var artistId: Long = 0L
    private var songOffset: Int = 0
    private var albumOffset: Int = 0
    private var loadJob: Job? = null

    fun start(summary: NeteaseArtistSummary, forceRefresh: Boolean = false) {
        if (!forceRefresh && shouldKeepCurrentArtist(summary.id)) {
            refreshFollowState()
            return
        }

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
                if (artistId != summary.id) return@launch
                songOffset = loaded.songs.size
                albumOffset = loaded.albums.size
                _uiState.value = loaded.copy(loading = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NPLogger.e(TAG, "load artist failed", e)
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = getApplication<Application>().getString(
                            R.string.artist_load_failed,
                            e.message ?: e.javaClass.simpleName
                        )
                    )
                }
            }
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
                withContext(Dispatchers.IO) {
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
                withContext(Dispatchers.IO) {
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
        val header = _uiState.value.header ?: return
        val targetFollowed = !header.followed
        viewModelScope.launch {
            _uiState.update { it.copy(followUpdating = true) }
            runCatching {
                withContext(Dispatchers.IO) {
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
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        followUpdating = false,
                        header = it.header?.copy(followed = targetFollowed)
                    )
                }
            }.onFailure { error ->
                NPLogger.e(TAG, "toggle artist follow failed", error)
                _uiState.update {
                    it.copy(
                        followUpdating = false,
                        error = getApplication<Application>().getString(
                            R.string.artist_follow_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
            }
        }
    }

    private suspend fun loadInitial(summary: NeteaseArtistSummary): NeteaseArtistDetailUiState = coroutineScope {
        val detailDeferred = async(Dispatchers.IO) { client.getArtistDetail(summary.id) }
        val songsDeferred = async(Dispatchers.IO) {
            client.getArtistSongs(summary.id, offset = 0, limit = SONG_PAGE_SIZE)
        }
        val albumsDeferred = async(Dispatchers.IO) {
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
        require(code == 200) { getApplication<Application>().getString(R.string.error_api_code, code) }

        val data = root.optJSONObject("data")
        val artist = data?.optJSONObject("artist") ?: root.optJSONObject("artist")
        val alias = artist?.optJSONArray("alias").joinNames()
        return NeteaseArtistHeader(
            id = artist?.optLong("id", fallback.id) ?: fallback.id,
            name = artist?.optString("name", fallback.name).orEmpty().ifBlank { fallback.name },
            coverUrl = toHttps(artist.optNonBlankString("cover") ?: artist.optNonBlankString("picUrl")),
            avatarUrl = toHttps(artist.optNonBlankString("avatar") ?: artist.optNonBlankString("img1v1Url")),
            alias = alias,
            briefDesc = artist?.optString("briefDesc", "").orEmpty(),
            musicSize = artist?.optInt("musicSize", 0) ?: 0,
            albumSize = artist?.optInt("albumSize", 0) ?: 0,
            followed = artist?.optBoolean("followed", false) == true
        )
    }

    private fun parseArtistSongs(raw: String): Page<SongItem> {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        require(code == 200) { getApplication<Application>().getString(R.string.error_api_code, code) }

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
        require(code == 200) { getApplication<Application>().getString(R.string.error_api_code, code) }

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

        val artists = parseNeteaseArtistSummaries(song.optJSONArray("ar"))
        val album = song.optJSONObject("al") ?: song.optJSONObject("album")
        val albumName = album?.optString("name", "").orEmpty()
        val cover = toHttps(album?.optString("picUrl", ""))
        return SongItem(
            id = id,
            name = name,
            artist = artists.joinToString(" / ") { it.name },
            album = "${PlayerManager.NETEASE_SOURCE_TAG}$albumName",
            albumId = album?.optLong("id", 0L) ?: 0L,
            durationMs = song.optLong("dt", 0L),
            coverUrl = cover.takeIf { it.isNotBlank() },
            originalCoverUrl = cover.takeIf { it.isNotBlank() },
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

    private fun JSONObject?.optNonBlankString(name: String): String? {
        return this?.optString(name, "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
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
