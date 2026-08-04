package moe.ouom.neriplayer.ui.viewmodel.artist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorDetail
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.platform.youtube.youtubeMusicThumbnailUrl
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

data class YouTubeMusicCreatorDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val detail: YouTubeMusicCreatorDetail? = null,
    val playbackQueueLoadingSectionKey: String? = null,
    val playbackQueueErrorSectionKey: String? = null,
    val playbackQueueError: String? = null
)

class YouTubeMusicCreatorDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.youtubeMusicClient
    private val _uiState = MutableStateFlow(YouTubeMusicCreatorDetailUiState())
    val uiState: StateFlow<YouTubeMusicCreatorDetailUiState> = _uiState
    private val _playbackRequests = MutableSharedFlow<YouTubeMusicCreatorPlaybackQueue>()
    internal val playbackRequests: SharedFlow<YouTubeMusicCreatorPlaybackQueue> =
        _playbackRequests.asSharedFlow()

    private var currentCreator: YouTubeMusicCreatorSummary? = null
    private var loadJob: Job? = null
    private var playbackQueueJob: Job? = null

    fun start(creator: YouTubeMusicCreatorSummary, forceRefresh: Boolean = false) {
        if (
            !forceRefresh &&
            creator.browseId == currentCreator?.browseId &&
            _uiState.value.detail != null
        ) {
            return
        }
        currentCreator = creator
        loadJob?.cancel()
        playbackQueueJob?.cancel()
        _uiState.value = YouTubeMusicCreatorDetailUiState(
            loading = true,
            detail = _uiState.value.detail?.takeIf {
                it.header.browseId == creator.browseId
            }
        )
        loadJob = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.getCreatorDetail(creator)
                }
                if (currentCreator?.browseId != creator.browseId) {
                    return@launch
                }
                _uiState.value = YouTubeMusicCreatorDetailUiState(detail = detail, loading = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCreator?.browseId != creator.browseId) {
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = getApplication<Application>().getString(
                        R.string.youtube_creator_load_failed,
                        error.message ?: error.javaClass.simpleName
                    )
                )
            }
        }
    }

    fun retry() {
        currentCreator?.let { start(it, forceRefresh = true) }
    }

    fun playSectionSong(
        section: YouTubeMusicCreatorSection,
        selectedItem: YouTubeMusicCreatorItem
    ) {
        if (playbackQueueJob?.isActive == true) {
            return
        }
        val creatorBrowseId = currentCreator?.browseId ?: return
        val sectionKey = youtubeMusicCreatorSectionKey(section)
        playbackQueueJob = viewModelScope.launch {
            _uiState.update { current ->
                if (currentCreator?.browseId != creatorBrowseId) {
                    current
                } else {
                    current.copy(
                        playbackQueueLoadingSectionKey = sectionKey,
                        playbackQueueErrorSectionKey = null,
                        playbackQueueError = null
                    )
                }
            }
            try {
                val queue = withContext(Dispatchers.IO) {
                    loadYouTubeMusicCreatorPlaybackQueue(
                        section = section,
                        selectedItem = selectedItem,
                        fetchFirstPage = client::getCreatorItems,
                        fetchContinuation = client::getCreatorItemsContinuation
                    )
                } ?: throw IllegalStateException("No playable YouTube Music items")
                if (currentCreator?.browseId != creatorBrowseId) {
                    return@launch
                }
                _playbackRequests.emit(queue)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCreator?.browseId != creatorBrowseId) {
                    return@launch
                }
                _uiState.update { current ->
                    current.copy(
                        playbackQueueErrorSectionKey = sectionKey,
                        playbackQueueError = creatorItemsError(section.title, error)
                    )
                }
            } finally {
                if (currentCreator?.browseId == creatorBrowseId) {
                    _uiState.update { current ->
                        current.copy(playbackQueueLoadingSectionKey = null)
                    }
                }
            }
        }
    }

    private fun creatorItemsError(sectionTitle: String, error: Exception): String {
        return getApplication<Application>().getString(
            R.string.youtube_creator_items_load_failed,
            sectionTitle,
            error.message ?: error.javaClass.simpleName
        )
    }
}

internal fun YouTubeMusicCreatorItem.toCreatorSongItem(): SongItem? {
    if (videoId.isBlank()) {
        return null
    }
    val displayArtist = artist.ifBlank { subtitle }
        .ifBlank { "YouTube" }
    val displayAlbum = album.ifBlank { "YouTube Music" }
    val cover = coverUrl.ifBlank { youtubeMusicThumbnailUrl(videoId) }
    return SongItem(
        id = stableYouTubeMusicId(videoId),
        name = title,
        artist = displayArtist,
        album = displayAlbum,
        albumId = stableYouTubeMusicId("$videoId|$displayAlbum"),
        durationMs = durationMs,
        coverUrl = cover,
        mediaUri = buildYouTubeMusicMediaUri(videoId),
        originalName = title,
        originalArtist = displayArtist,
        originalCoverUrl = cover,
        channelId = "youtubeMusic",
        audioId = videoId
    )
}

internal fun YouTubeMusicCreatorItem.toCreatorPlaylist(): YouTubeMusicPlaylist? {
    if (browseId.isBlank()) {
        return null
    }
    return YouTubeMusicPlaylist(
        browseId = browseId,
        playlistId = playlistId.ifBlank {
            browseId.removePrefix("VL")
        },
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl,
        trackCount = 0
    )
}

internal fun YouTubeMusicCreatorItem.toCreatorSummary(): YouTubeMusicCreatorSummary? {
    if (browseId.isBlank()) {
        return null
    }
    return YouTubeMusicCreatorSummary(
        browseId = browseId,
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl,
        channelId = browseId.takeIf { it.startsWith("UC") }.orEmpty()
    )
}
