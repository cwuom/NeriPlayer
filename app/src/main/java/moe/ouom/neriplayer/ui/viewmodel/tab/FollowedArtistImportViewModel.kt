package moe.ouom.neriplayer.ui.viewmodel.tab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FavoriteArtist
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.netease.mapping.NeteaseFollowedArtistPage
import moe.ouom.neriplayer.platform.netease.mapping.parseNeteaseFollowedArtists
import moe.ouom.neriplayer.platform.youtube.api.auth.hasEffectiveAuth
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import java.io.IOException

enum class FollowedArtistImportError { LOGIN_REQUIRED, ACCOUNT_CHANGED, FAILED }

data class FollowedArtistImportUiState(
    val source: String? = null,
    val loading: Boolean = false,
    val importedCount: Int? = null,
    val error: FollowedArtistImportError? = null
)

class FollowedArtistImportViewModel internal constructor(
    application: Application,
    private val loadArtists: suspend (String) -> List<FavoriteArtist>,
    private val mergeArtists: suspend (String, List<FavoriteArtist>, Long) -> Int,
    private val currentAccount: (String) -> Any?,
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        ::loadRemoteFollowedArtists,
        { source, artists, startedAt ->
            FavoritePlaylistRepository.getInstance(application)
                .mergeFollowedArtists(source, artists, startedAt)
        },
        ::followedArtistAccount
    )

    private val _uiState = MutableStateFlow(FollowedArtistImportUiState())
    val uiState: StateFlow<FollowedArtistImportUiState> = _uiState

    fun consumeFeedback(feedback: FollowedArtistImportUiState) {
        if (_uiState.value == feedback) {
            _uiState.value = feedback.copy(importedCount = null, error = null)
        }
    }

    fun importArtists(source: String) {
        if (_uiState.value.loading) return
        if (source != FAVORITE_SOURCE_NETEASE_ARTIST && source != FAVORITE_SOURCE_YOUTUBE_ARTIST) return
        val account = currentAccount(source)
        if (account == null) {
            _uiState.value = FollowedArtistImportUiState(
                source = source, error = FollowedArtistImportError.LOGIN_REQUIRED
            )
            return
        }
        val startedAt = now()
        _uiState.value = FollowedArtistImportUiState(source = source, loading = true)
        viewModelScope.launch {
            try {
                val imported = withContext(ioDispatcher) {
                    val artists = loadArtists(source)
                    if (currentAccount(source) != account) return@withContext null
                    mergeArtists(source, artists, startedAt)
                }
                _uiState.value = if (imported == null) {
                    FollowedArtistImportUiState(
                        source = source, error = FollowedArtistImportError.ACCOUNT_CHANGED
                    )
                } else {
                    FollowedArtistImportUiState(source = source, importedCount = imported)
                }
            } catch (error: CancellationException) {
                _uiState.value = FollowedArtistImportUiState(source = source)
                throw error
            } catch (_: Exception) {
                _uiState.value = FollowedArtistImportUiState(
                    source = source, error = FollowedArtistImportError.FAILED
                )
            }
        }
    }
}

private fun followedArtistAccount(source: String): Any? = when (source) {
    FAVORITE_SOURCE_NETEASE_ARTIST -> AppContainer.neteaseCookieRepo.getCookiesOnce()
        .get("MUSIC_U")?.takeIf { it.isNotBlank() }
    FAVORITE_SOURCE_YOUTUBE_ARTIST -> youtubeFollowedArtistAccount(AppContainer.youtubeAuthRepo.getAuthOnce())
    else -> null
}

internal fun youtubeFollowedArtistAccount(bundle: YouTubeAuthBundle): String? =
    bundle.takeIf { it.hasEffectiveAuth() }?.buildRefreshObserverFingerprint()

private suspend fun loadRemoteFollowedArtists(source: String): List<FavoriteArtist> = when (source) {
    FAVORITE_SOURCE_NETEASE_ARTIST -> loadAllNeteaseFollowedArtists { offset ->
        parseNeteaseFollowedArtists(AppContainer.neteaseClient.getFollowedArtists(offset = offset))
    }
    FAVORITE_SOURCE_YOUTUBE_ARTIST -> AppContainer.youtubeMusicClient.getFollowedArtists().map {
        FavoriteArtist(
            id = stableYouTubeMusicId(it.browseId),
            name = it.title,
            coverUrl = it.coverUrl,
            browseId = it.browseId,
            subtitle = it.subtitle
        )
    }
    else -> throw IllegalArgumentException("Unsupported artist source")
}

internal suspend fun loadAllNeteaseFollowedArtists(
    fetchPage: suspend (Int) -> NeteaseFollowedArtistPage
): List<FavoriteArtist> {
    val artists = linkedMapOf<Long, FavoriteArtist>()
    val visitedPages = mutableSetOf<List<Long>>()
    var offset = 0
    do {
        val page = fetchPage(offset)
        val pageIds = page.artists.map { it.id }
        if (page.rawCount > 0 && !visitedPages.add(pageIds)) {
            throw IOException("Repeated artist page")
        }
        page.artists.forEach {
            artists.putIfAbsent(
                it.id, FavoriteArtist(it.id, it.name, it.coverUrl, it.musicSize, subtitle = it.alias)
            )
        }
        if (!page.hasMore) break
        if (page.rawCount <= 0) throw IOException("Empty artist continuation")
        offset = Math.addExact(offset, page.rawCount)
    } while (true)
    return artists.values.toList()
}
