package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle
import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentPreferences
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger

internal data class LibraryYouTubeCatalog(val enabled: Boolean, val account: String?, val catalogKey: String?)

internal fun libraryYouTubeCatalog(
    auth: Flow<YouTubeAuthBundle>,
    enabled: Flow<Boolean>,
    preferences: Flow<YouTubeContentPreferences>,
    systemLocale: () -> Locale = YouTubeContentSettings::systemLocale
): Flow<LibraryYouTubeCatalog> = combine(auth, enabled, preferences) { bundle, active, prefs ->
    if (active && bundle.hasSavedAuthMaterial()) {
        LibraryYouTubeCatalog(true, bundle.buildRefreshObserverFingerprint(), prefs.catalogKey(systemLocale()))
    } else {
        LibraryYouTubeCatalog(active, null, null)
    }
}.distinctUntilChanged()

internal class LibraryYouTubeRefreshOwner(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<LibraryUiState>,
    private val isEnabled: () -> Boolean,
    private val load: suspend () -> List<YouTubeMusicPlaylist>
) {
    private var refreshJob: Job? = null
    private var pending = false

    fun onSessionChanged() {
        clear()
        refresh()
    }

    fun refresh() {
        if (!isEnabled() || !scope.isActive) return
        val runningJob = refreshJob
        if (runningJob?.isActive == true) {
            pending = true
            NPLogger.d("LibraryViewModel", "refreshYouTubeMusicPlaylists coalesced while loading")
            return
        }
        pending = false
        val nextJob = scope.launch(start = CoroutineStart.LAZY) { loadAndPublish() }
        refreshJob = nextJob
        state.update { it.copy(youtubeMusicRefreshing = true) }
        nextJob.start()
    }

    fun clear() {
        val previousJob = refreshJob
        // 先解除所有权, 避免取消时旧任务的 finally 改写当前状态
        refreshJob = null
        pending = false
        previousJob?.cancel()
        state.update {
            it.copy(youtubeMusicPlaylists = emptyList(), youtubeMusicError = null, youtubeMusicRefreshing = false)
        }
    }

    private suspend fun loadAndPublish() {
        try {
            val playlists = load()
            currentCoroutineContext().ensureActive()
            state.update {
                it.copy(youtubeMusicPlaylists = playlists, youtubeMusicError = null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            state.update {
                it.copy(youtubeMusicPlaylists = emptyList(), youtubeMusicError = error.message)
            }
        } finally {
            finishRefresh(currentCoroutineContext()[Job])
        }
    }

    private fun finishRefresh(completedJob: Job?) {
        if (refreshJob !== completedJob) return
        refreshJob = null
        state.update { it.copy(youtubeMusicRefreshing = false) }
        if (pending && isEnabled()) {
            pending = false
            refresh()
        }
    }
}
