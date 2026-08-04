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
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger

private const val TAG = "NERI-YTCreatorItems"

data class YouTubeMusicCreatorItemsUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    val title: String = "",
    val items: List<YouTubeMusicCreatorItem> = emptyList(),
    val continuation: String? = null,
    val playbackQueueLoading: Boolean = false,
    val playbackQueueError: String? = null
)

class YouTubeMusicCreatorItemsViewModel(application: Application) : AndroidViewModel(application) {
    private val client = AppContainer.youtubeMusicClient
    private val _uiState = MutableStateFlow(YouTubeMusicCreatorItemsUiState())
    val uiState: StateFlow<YouTubeMusicCreatorItemsUiState> = _uiState
    private val _playbackRequests = MutableSharedFlow<YouTubeMusicCreatorPlaybackQueue>()
    internal val playbackRequests: SharedFlow<YouTubeMusicCreatorPlaybackQueue> =
        _playbackRequests.asSharedFlow()

    private var currentSection: YouTubeMusicCreatorSection? = null
    private var currentSectionKey: String? = null
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var playbackQueueJob: Job? = null

    fun start(section: YouTubeMusicCreatorSection, forceRefresh: Boolean = false) {
        val endpoint = section.moreEndpoint ?: return
        val sectionKey = creatorSectionKey(section)
        if (!forceRefresh && sectionKey == currentSectionKey && _uiState.value.items.isNotEmpty()) {
            return
        }
        val previous = _uiState.value.takeIf { sectionKey == currentSectionKey }
        currentSection = section
        currentSectionKey = sectionKey
        loadJob?.cancel()
        loadMoreJob?.cancel()
        playbackQueueJob?.cancel()
        _uiState.value = YouTubeMusicCreatorItemsUiState(
            loading = true,
            title = previous?.title?.ifBlank { section.title } ?: section.title,
            items = previous?.items.orEmpty(),
            continuation = previous?.continuation
        )
        loadJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    client.getCreatorItems(endpoint = endpoint, fallbackTitle = section.title)
                }
                if (sectionKey != currentSectionKey) {
                    return@launch
                }
                _uiState.value = YouTubeMusicCreatorItemsUiState(
                    loading = false,
                    title = page.title.ifBlank { section.title },
                    items = page.items.distinctBy(::creatorItemKey),
                    continuation = page.continuation
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sectionKey != currentSectionKey) {
                    return@launch
                }
                NPLogger.e(TAG, "load creator items failed: title=${section.title}", error)
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        error = creatorItemsError(section.title, error)
                    )
                }
            }
        }
    }

    fun retry() {
        currentSection?.let { start(it, forceRefresh = true) }
    }

    fun loadMore() {
        val section = currentSection ?: return
        val continuation = _uiState.value.continuation ?: return
        if (
            _uiState.value.loading ||
            _uiState.value.loadingMore ||
            playbackQueueJob?.isActive == true
        ) {
            return
        }
        val sectionKey = currentSectionKey ?: return
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true, loadMoreError = null) }
            try {
                val page = withContext(Dispatchers.IO) {
                    client.getCreatorItemsContinuation(continuation)
                }
                if (sectionKey != currentSectionKey) {
                    return@launch
                }
                _uiState.update { current ->
                    val merged = (current.items + page.items).distinctBy(::creatorItemKey)
                    current.copy(
                        loadingMore = false,
                        title = current.title.ifBlank { page.title.ifBlank { section.title } },
                        items = merged,
                        continuation = page.continuation.takeUnless {
                            it == continuation && merged.size == current.items.size
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sectionKey != currentSectionKey) {
                    return@launch
                }
                NPLogger.e(TAG, "load more creator items failed: title=${section.title}", error)
                _uiState.update {
                    it.copy(
                        loadingMore = false,
                        loadMoreError = creatorItemsError(section.title, error)
                    )
                }
            }
        }
    }

    fun playSong(item: YouTubeMusicCreatorItem) {
        val section = currentSection ?: return
        val sectionKey = currentSectionKey ?: return
        if (
            item.videoId.isBlank() ||
            _uiState.value.loadingMore ||
            playbackQueueJob?.isActive == true
        ) {
            return
        }
        playbackQueueJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(playbackQueueLoading = true, playbackQueueError = null)
            }
            try {
                val queue = withContext(Dispatchers.IO) {
                    loadYouTubeMusicCreatorPlaybackQueue(
                        section = section,
                        selectedItem = item,
                        fetchFirstPage = client::getCreatorItems,
                        fetchContinuation = client::getCreatorItemsContinuation
                    )
                } ?: throw IllegalStateException("No playable YouTube Music items")
                if (sectionKey != currentSectionKey) {
                    return@launch
                }
                _playbackRequests.emit(queue)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (sectionKey != currentSectionKey) {
                    return@launch
                }
                NPLogger.e(TAG, "load creator playback queue failed: title=${section.title}", error)
                _uiState.update {
                    it.copy(playbackQueueError = creatorItemsError(section.title, error))
                }
            } finally {
                if (sectionKey == currentSectionKey) {
                    _uiState.update { it.copy(playbackQueueLoading = false) }
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

private fun creatorSectionKey(section: YouTubeMusicCreatorSection): String {
    return youtubeMusicCreatorSectionKey(section)
}

private fun creatorItemKey(item: YouTubeMusicCreatorItem): String {
    return youtubeMusicCreatorItemKey(item)
}
