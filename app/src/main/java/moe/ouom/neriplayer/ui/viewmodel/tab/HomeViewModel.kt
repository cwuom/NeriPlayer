package moe.ouom.neriplayer.ui.viewmodel.tab

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/HomeViewModel
 * Created: 2025/8/10
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.util.platform.LanguageManager
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.IOException

private const val TAG = "NERI-HomeVM"
private const val HOME_NETEASE_SONG_LIMIT = 30
private const val HOME_NETEASE_PLAYLIST_LIMIT = 30
private const val HOME_PRIVATE_FM_MAX_BATCHES = 10
private const val HOME_MAX_FAILURE_BEFORE_WARNING = 3
private const val HOME_YT_MUSIC_PLAYLIST_LIMIT = 24
private const val HOME_INITIAL_LOAD_DEFER_MS = 250L

private fun shouldFallbackRecommend(code: Int): Boolean = code == 301 || code == 50000005

data class HomeSectionState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

data class HomeNeteaseSongSectionState(
    val source: NeteaseHomeSongSource,
    val section: HomeSectionState<SongItem> = HomeSectionState()
)

data class HomeNeteasePlaylistSectionState(
    val source: NeteaseHomePlaylistSource,
    val section: HomeSectionState<PlaylistSummary> = HomeSectionState()
)

data class HomeUiState(
    val playlistSections: List<HomeNeteasePlaylistSectionState> = emptyList(),
    val trendingSongSections: List<HomeNeteaseSongSectionState> = emptyList(),
    val radarSongSections: List<HomeNeteaseSongSectionState> = emptyList(),
    val radarPlaylists: HomeSectionState<PlaylistSummary> = HomeSectionState(),
    val ytMusicPlaylists: HomeSectionState<YouTubeMusicPlaylist> = HomeSectionState(),
    val ytMusicHomeShelves: HomeSectionState<YouTubeMusicHomeShelf> = HomeSectionState(),
    val hasLogin: Boolean = false,
    val internationalizationEnabled: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AppContainer.neteaseCookieRepo
    private val client = AppContainer.neteaseClient
    private val youtubeAuthRepo = AppContainer.youtubeAuthRepo

    private val initialRecommendCookies = repo.getCookiesOnce().toMutableMap().apply {
        putIfAbsent("os", "pc")
    }
    private var hasRecommendLogin = !initialRecommendCookies["MUSIC_U"].isNullOrBlank()
    private val _uiState = MutableStateFlow(createHomeUiState(hasRecommendLogin, loading = true))
    val uiState: StateFlow<HomeUiState> = _uiState

    private var playlistJob: Job? = null
    private var hotSongsJob: Job? = null
    private var radarSongsJob: Job? = null
    private var radarPlaylistsJob: Job? = null
    private var ytMusicPlaylistJob: Job? = null
    private var ytMusicHomeFeedJob: Job? = null
    private var ytMusicPlaylistRefreshPending = false
    private var ytMusicHomeFeedRefreshPending = false
    private var homeRecommendationsBootstrapped = false
    private var lastYouTubeAuthFingerprint: String? = null
    private var offlineMode = false

    private fun localizedAppContext() = LanguageManager.applyLanguage(getApplication())

    private fun createHomeUiState(hasLogin: Boolean, loading: Boolean): HomeUiState {
        return HomeUiState(
            playlistSections = createPlaylistSections(hasLogin, loading),
            trendingSongSections = createSongSections(
                sources = NeteaseHomeTrendingSongSources,
                hasLogin = hasLogin,
                loading = loading
            ),
            radarSongSections = createSongSections(
                sources = NeteaseHomeRadarSongSources,
                hasLogin = hasLogin,
                loading = loading
            ),
            radarPlaylists = HomeSectionState(loading = loading),
            hasLogin = hasLogin
        )
    }

    private fun createSongSections(
        sources: List<NeteaseHomeSongSource>,
        hasLogin: Boolean,
        loading: Boolean
    ): List<HomeNeteaseSongSectionState> {
        return availableNeteaseHomeSongSources(sources, hasLogin).map { source ->
            HomeNeteaseSongSectionState(source = source, section = HomeSectionState(loading = loading))
        }
    }

    private fun createPlaylistSections(
        hasLogin: Boolean,
        loading: Boolean
    ): List<HomeNeteasePlaylistSectionState> {
        return availableNeteaseHomePlaylistSources(NeteaseHomePlaylistSources, hasLogin).map { source ->
            HomeNeteasePlaylistSectionState(
                source = source,
                section = HomeSectionState(loading = loading)
            )
        }
    }

    private fun buildSongSectionsForRefresh(
        current: List<HomeNeteaseSongSectionState>,
        sources: List<NeteaseHomeSongSource>
    ): List<HomeNeteaseSongSectionState> {
        val previousBySource = current.associateBy { it.source }
        return availableNeteaseHomeSongSources(sources, hasRecommendLogin).map { source ->
            val previous = previousBySource[source]?.section ?: HomeSectionState()
            HomeNeteaseSongSectionState(
                source = source,
                section = previous.copy(loading = true, error = null)
            )
        }
    }

    private fun buildPlaylistSectionsForRefresh(
        current: List<HomeNeteasePlaylistSectionState>
    ): List<HomeNeteasePlaylistSectionState> {
        val previousBySource = current.associateBy { it.source }
        return availableNeteaseHomePlaylistSources(NeteaseHomePlaylistSources, hasRecommendLogin)
            .map { source ->
                val previous = previousBySource[source]?.section ?: HomeSectionState()
                HomeNeteasePlaylistSectionState(
                    source = source,
                    section = previous.copy(loading = true, error = null)
                )
            }
    }

    private fun clearSongSectionLoading(
        sections: List<HomeNeteaseSongSectionState>
    ): List<HomeNeteaseSongSectionState> {
        return sections.map { sectionState ->
            sectionState.copy(section = sectionState.section.copy(loading = false, error = null))
        }
    }

    private fun clearPlaylistSectionLoading(
        sections: List<HomeNeteasePlaylistSectionState>
    ): List<HomeNeteasePlaylistSectionState> {
        return sections.map { sectionState ->
            sectionState.copy(section = sectionState.section.copy(loading = false, error = null))
        }
    }

    private fun replaceSongSection(
        sections: List<HomeNeteaseSongSectionState>,
        updated: HomeNeteaseSongSectionState
    ): List<HomeNeteaseSongSectionState> {
        return sections.map { sectionState ->
            if (sectionState.source == updated.source) updated else sectionState
        }
    }

    private fun replacePlaylistSection(
        sections: List<HomeNeteasePlaylistSectionState>,
        updated: HomeNeteasePlaylistSectionState
    ): List<HomeNeteasePlaylistSectionState> {
        return sections.map { sectionState ->
            if (sectionState.source == updated.source) updated else sectionState
        }
    }

    init {
        lastYouTubeAuthFingerprint = buildYouTubeAuthFingerprint(youtubeAuthRepo.getAuthOnce())

        // 观察国际化设置变化, 切换推荐源
        viewModelScope.launch {
            combine(
                AppContainer.settingsRepo.internationalizationEnabledFlow,
                AppContainer.settingsRepo.youtubeEnabledFlow
            ) { internationalizationEnabled, youtubeEnabled ->
                internationalizationEnabled to youtubeEnabled
            }.collect { (internationalizationEnabled, youtubeEnabled) ->
                val useYouTubeHome = internationalizationEnabled && youtubeEnabled
                NPLogger.d(
                    TAG,
                    "home source updated: international=$internationalizationEnabled, youtube=$youtubeEnabled"
                )
                _uiState.value = _uiState.value.copy(
                    internationalizationEnabled = useYouTubeHome
                )
                if (useYouTubeHome) {
                    refreshYtMusicPlaylists()
                    refreshYtMusicHomeFeed()
                } else {
                    cancelYouTubeHomeJobs()
                    _uiState.value = _uiState.value.copy(
                        ytMusicPlaylists = HomeSectionState(),
                        ytMusicHomeShelves = HomeSectionState()
                    )
                }
            }
        }

        viewModelScope.launch {
            AppContainer.youtubeAuthRepo.authFlow.drop(1).collect { bundle ->
                val nextFingerprint = buildYouTubeAuthFingerprint(bundle)
                if (nextFingerprint == lastYouTubeAuthFingerprint) {
                    return@collect
                }
                lastYouTubeAuthFingerprint = nextFingerprint
                NPLogger.d(
                    TAG,
                    "youtube auth changed: hasEffectiveAuth=${bundle.hasEffectiveAuth()}, hasCookieContext=${bundle.hasYouTubeMusicCookieContext()}, intl=${_uiState.value.internationalizationEnabled}"
                )
                if (!_uiState.value.internationalizationEnabled) {
                    return@collect
                }
                if (!bundle.hasYouTubeMusicCookieContext()) {
                    NPLogger.d(TAG, "youtube auth cleared, reset home YouTube sections")
                    _uiState.value = _uiState.value.copy(
                        ytMusicPlaylists = HomeSectionState(),
                        ytMusicHomeShelves = HomeSectionState()
                    )
                    return@collect
                }
                refreshYtMusicPlaylists()
                refreshYtMusicHomeFeed()
            }
        }

        // 登录后自动刷新首页推荐歌单
        viewModelScope.launch {
            repo.cookieFlow.drop(1).collect { raw ->
                val cookies = raw.toMutableMap()
                if (!cookies.containsKey("os")) cookies["os"] = "pc"
                NPLogger.d(TAG, "cookieFlow updated: keys=${cookies.keys.joinToString()}")
                val nextHasLogin = !cookies["MUSIC_U"].isNullOrBlank()
                val loginChanged = hasRecommendLogin != nextHasLogin
                hasRecommendLogin = nextHasLogin
                if (loginChanged) {
                    _uiState.value = _uiState.value.copy(hasLogin = nextHasLogin)
                    refreshNeteaseHome()
                }
                if (!homeRecommendationsBootstrapped) {
                    homeRecommendationsBootstrapped = true
                    refreshNeteaseHome()
                }
            }
        }
        viewModelScope.launch {
            delay(HOME_INITIAL_LOAD_DEFER_MS)
            if (!homeRecommendationsBootstrapped) {
                homeRecommendationsBootstrapped = true
                refreshNeteaseHome()
            }
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        if (offlineMode == enabled) return

        NPLogger.d(TAG, "setOfflineMode: $enabled")
        offlineMode = enabled
        if (!enabled) return

        cancelHomeNetworkJobs()
        _uiState.update { state ->
            state.copy(
                playlistSections = clearPlaylistSectionLoading(state.playlistSections),
                trendingSongSections = clearSongSectionLoading(state.trendingSongSections),
                radarSongSections = clearSongSectionLoading(state.radarSongSections),
                radarPlaylists = state.radarPlaylists.copy(loading = false, error = null),
                ytMusicPlaylists = state.ytMusicPlaylists.copy(loading = false, error = null),
                ytMusicHomeShelves = state.ytMusicHomeShelves.copy(loading = false, error = null)
            )
        }
    }

    private fun cancelHomeNetworkJobs() {
        playlistJob?.cancel()
        hotSongsJob?.cancel()
        radarSongsJob?.cancel()
        radarPlaylistsJob?.cancel()
        ytMusicPlaylistJob?.cancel()
        ytMusicHomeFeedJob?.cancel()
        ytMusicPlaylistRefreshPending = false
        ytMusicHomeFeedRefreshPending = false
    }

    private fun cancelYouTubeHomeJobs() {
        ytMusicPlaylistJob?.cancel()
        ytMusicHomeFeedJob?.cancel()
        ytMusicPlaylistJob = null
        ytMusicHomeFeedJob = null
        ytMusicPlaylistRefreshPending = false
        ytMusicHomeFeedRefreshPending = false
    }

    fun refreshNeteaseHome() {
        if (offlineMode) return

        refreshRecommend()
        loadHomeRecommendations(force = true)
        refreshRadarPlaylists()
    }

    /** 拉首页推荐歌单 */
    fun refreshRecommend() {
        if (offlineMode) return

        val sources = availableNeteaseHomePlaylistSources(
            candidates = NeteaseHomePlaylistSources,
            hasLogin = hasRecommendLogin
        )
        if (sources.isEmpty()) {
            _uiState.update { state -> state.copy(playlistSections = emptyList()) }
            return
        }
        NPLogger.d(TAG, "refreshRecommend start: sources=$sources, hasLogin=$hasRecommendLogin")
        playlistJob?.cancel()
        _uiState.update { state ->
            state.copy(
                playlistSections = buildPlaylistSectionsForRefresh(state.playlistSections),
                hasLogin = hasRecommendLogin
            )
        }
        playlistJob = viewModelScope.launch {
            sources.forEach { source ->
                val section = fetchPlaylistSection(source)
                _uiState.update { state ->
                    state.copy(
                        playlistSections = replacePlaylistSection(
                            state.playlistSections,
                            section
                        )
                    )
                }
            }
        }
    }

    fun loadHomeRecommendations(force: Boolean = false) {
        if (offlineMode) return

        val state = _uiState.value
        if (!force) {
            val songSections = state.radarSongSections + state.trendingSongSections
            val alreadyLoaded = songSections.isNotEmpty() &&
                songSections.all { it.section.items.isNotEmpty() || it.section.error != null }
            val loading = songSections.any { it.section.loading }
            if (alreadyLoaded || loading) return
        }

        refreshRadarSongs()
        refreshHotSongs()
    }

    private fun refreshHotSongs() {
        if (offlineMode) return

        val sources = availableNeteaseHomeSongSources(
            candidates = NeteaseHomeTrendingSongSources,
            hasLogin = hasRecommendLogin
        )
        if (sources.isEmpty()) {
            _uiState.update { state -> state.copy(trendingSongSections = emptyList()) }
            return
        }
        NPLogger.d(TAG, "refreshHotSongs start: sources=$sources")
        hotSongsJob?.cancel()
        _uiState.update { state ->
            state.copy(
                trendingSongSections = buildSongSectionsForRefresh(
                    current = state.trendingSongSections,
                    sources = NeteaseHomeTrendingSongSources
                ),
                hasLogin = hasRecommendLogin
            )
        }
        hotSongsJob = viewModelScope.launch {
            sources.forEach { source ->
                val section = fetchSongSection("refreshHotSongs", source)
                _uiState.update { state ->
                    state.copy(
                        trendingSongSections = replaceSongSection(
                            state.trendingSongSections,
                            section
                        )
                    )
                }
            }
        }
    }

    private fun refreshRadarSongs() {
        if (offlineMode) return

        val sources = availableNeteaseHomeSongSources(
            candidates = NeteaseHomeRadarSongSources,
            hasLogin = hasRecommendLogin
        )
        if (sources.isEmpty()) {
            _uiState.update { state -> state.copy(radarSongSections = emptyList()) }
            return
        }
        NPLogger.d(TAG, "refreshRadarSongs start: sources=$sources")
        radarSongsJob?.cancel()
        _uiState.update { state ->
            state.copy(
                radarSongSections = buildSongSectionsForRefresh(
                    current = state.radarSongSections,
                    sources = NeteaseHomeRadarSongSources
                ),
                hasLogin = hasRecommendLogin
            )
        }
        radarSongsJob = viewModelScope.launch {
            sources.forEach { source ->
                val section = fetchSongSection("refreshRadarSongs", source)
                _uiState.update { state ->
                    state.copy(
                        radarSongSections = replaceSongSection(
                            state.radarSongSections,
                            section
                        )
                    )
                }
            }
        }
    }

    private fun refreshRadarPlaylists() {
        if (offlineMode) return

        NPLogger.d(TAG, "refreshRadarPlaylists start")
        radarPlaylistsJob?.cancel()
        val previous = _uiState.value.radarPlaylists
        _uiState.value = _uiState.value.copy(
            radarPlaylists = previous.copy(loading = true, error = null)
        )
        radarPlaylistsJob = viewModelScope.launch {
            when (val result = fetchWithRetry("refreshRadarPlaylists") {
                loadRadarPlaylistSummaries()
            }) {
                is RetryLoadResult.Success -> {
                    NPLogger.d(TAG, "refreshRadarPlaylists success: count=${result.items.size}")
                    _uiState.value = _uiState.value.copy(
                        radarPlaylists = HomeSectionState(items = result.items)
                    )
                }
                is RetryLoadResult.Failure -> {
                    NPLogger.e(TAG, "refreshRadarPlaylists failed", result.throwable)
                    _uiState.value = _uiState.value.copy(
                        radarPlaylists = HomeSectionState(
                            items = NeteaseRadarPlaylistDefinitions.map { it.toPlaylistSummary() }
                        )
                    )
                }
            }
        }
    }

    /** 拉取 YouTube Music 歌单 */
    fun refreshYtMusicPlaylists() {
        if (offlineMode || !_uiState.value.internationalizationEnabled) return

        if (ytMusicPlaylistJob?.isActive == true) {
            ytMusicPlaylistRefreshPending = true
            NPLogger.d(TAG, "refreshYtMusicPlaylists coalesced while loading")
            return
        }
        ytMusicPlaylistRefreshPending = false
        NPLogger.d(TAG, "refreshYtMusicPlaylists start")
        _uiState.value = _uiState.value.copy(
            ytMusicPlaylists = _uiState.value.ytMusicPlaylists.copy(loading = true, error = null)
        )
        ytMusicPlaylistJob = viewModelScope.launch {
            try {
                when (val result = fetchWithRetry("refreshYtMusicPlaylists") {
                    val library = withContext(Dispatchers.IO) {
                        AppContainer.youtubeMusicClient.getHomePlaylistRecommendations()
                    }
                    library.map { pl ->
                        YouTubeMusicPlaylist(
                            browseId = pl.browseId,
                            playlistId = pl.playlistId,
                            title = pl.title,
                            subtitle = pl.subtitle,
                            coverUrl = pl.coverUrl,
                            trackCount = pl.trackCount ?: 0
                        )
                    }.take(HOME_YT_MUSIC_PLAYLIST_LIMIT)
                }) {
                    is RetryLoadResult.Success -> {
                        NPLogger.d(TAG, "refreshYtMusicPlaylists success: count=${result.items.size}")
                        _uiState.value = _uiState.value.copy(
                            ytMusicPlaylists = HomeSectionState(items = result.items)
                        )
                    }
                    is RetryLoadResult.Failure -> {
                        NPLogger.e(TAG, "refreshYtMusicPlaylists failed", result.throwable)
                        _uiState.value = _uiState.value.copy(
                            ytMusicPlaylists = _uiState.value.ytMusicPlaylists.copy(
                                loading = false,
                                error = buildHomeErrorMessage(result.throwable)
                            )
                        )
                    }
                }
            } finally {
                val completedJob = coroutineContext[Job]
                if (ytMusicPlaylistJob === completedJob) {
                    ytMusicPlaylistJob = null
                    if (
                        ytMusicPlaylistRefreshPending &&
                        !offlineMode &&
                        _uiState.value.internationalizationEnabled
                    ) {
                        ytMusicPlaylistRefreshPending = false
                        refreshYtMusicPlaylists()
                    }
                }
            }
        }
    }


    /** 拉取 YouTube Music 首页推荐 */
    fun refreshYtMusicHomeFeed() {
        if (offlineMode || !_uiState.value.internationalizationEnabled) return

        if (ytMusicHomeFeedJob?.isActive == true) {
            ytMusicHomeFeedRefreshPending = true
            NPLogger.d(TAG, "refreshYtMusicHomeFeed coalesced while loading")
            return
        }
        ytMusicHomeFeedRefreshPending = false
        NPLogger.d(TAG, "refreshYtMusicHomeFeed start")
        _uiState.value = _uiState.value.copy(
            ytMusicHomeShelves = _uiState.value.ytMusicHomeShelves.copy(loading = true, error = null)
        )
        ytMusicHomeFeedJob = viewModelScope.launch {
            try {
                when (val result = fetchWithRetry("refreshYtMusicHomeFeed") {
                    withContext(Dispatchers.IO) {
                        AppContainer.youtubeMusicClient.getHomeFeed(
                            fillShelfContinuations = false,
                            requireLogin = true
                        )
                    }
                }) {
                    is RetryLoadResult.Success -> {
                        NPLogger.d(TAG, "refreshYtMusicHomeFeed success: count=${result.items.size}")
                        _uiState.value = _uiState.value.copy(
                            ytMusicHomeShelves = HomeSectionState(items = result.items)
                        )
                    }
                    is RetryLoadResult.Failure -> {
                        NPLogger.e(TAG, "refreshYtMusicHomeFeed failed", result.throwable)
                        _uiState.value = _uiState.value.copy(
                            ytMusicHomeShelves = _uiState.value.ytMusicHomeShelves.copy(
                                loading = false,
                                error = buildHomeErrorMessage(result.throwable)
                            )
                        )
                    }
                }
            } finally {
                val completedJob = coroutineContext[Job]
                if (ytMusicHomeFeedJob === completedJob) {
                    ytMusicHomeFeedJob = null
                    if (
                        ytMusicHomeFeedRefreshPending &&
                        !offlineMode &&
                        _uiState.value.internationalizationEnabled
                    ) {
                        ytMusicHomeFeedRefreshPending = false
                        refreshYtMusicHomeFeed()
                    }
                }
            }
        }
    }

    private suspend fun <T> fetchWithRetry(
        name: String,
        fetch: suspend () -> List<T>
    ): RetryLoadResult<T> {
        var lastError: Throwable? = null
        repeat(HOME_MAX_FAILURE_BEFORE_WARNING) { attempt ->
            try {
                val items = fetch()
                if (attempt > 0) {
                    NPLogger.d(
                        TAG,
                        "$name recovered on attempt ${attempt + 1}: count=${items.size}"
                    )
                }
                return RetryLoadResult.Success(items)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                lastError = e
                NPLogger.w(
                    TAG,
                    "$name attempt ${attempt + 1}/$HOME_MAX_FAILURE_BEFORE_WARNING failed: ${e.message}"
                )
            }
        }
        return RetryLoadResult.Failure(lastError ?: IllegalStateException("Unknown error"))
    }

    private fun buildHomeErrorMessage(error: Throwable): String {
        val localizedContext = localizedAppContext()
        return when (error) {
            is IOException -> localizedContext.getString(
                R.string.home_error_network,
                error.message ?: error.javaClass.simpleName
            )
            is ApiCodeException -> {
                if (error.code == 50000005) {
                    localizedContext.getString(R.string.home_login_required)
                } else {
                    localizedContext.getString(R.string.error_api_code, error.code)
                }
            }
            else -> localizedContext.getString(
                R.string.home_error_unknown,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private suspend fun parseRecommendOnWorker(raw: String): List<PlaylistSummary> =
        withContext(Dispatchers.Default) {
            parseNeteaseHomePlaylists(raw, limit = HOME_NETEASE_PLAYLIST_LIMIT)
        }

    private suspend fun parseSongsOnWorker(raw: String): List<SongItem> =
        withContext(Dispatchers.Default) {
            parseNeteaseHomeSongs(raw, limit = HOME_NETEASE_SONG_LIMIT)
        }

    private suspend fun fetchSongSection(
        name: String,
        source: NeteaseHomeSongSource
    ): HomeNeteaseSongSectionState {
        return when (val result = fetchWithRetry("$name/$source") { fetchSongSource(source) }) {
            is RetryLoadResult.Success -> {
                NPLogger.d(
                    TAG,
                    "$name success: source=$source, count=${result.items.size}"
                )
                HomeNeteaseSongSectionState(
                    source = source,
                    section = HomeSectionState(items = result.items)
                )
            }
            is RetryLoadResult.Failure -> {
                NPLogger.e(TAG, "$name failed: source=$source", result.throwable)
                HomeNeteaseSongSectionState(
                    source = source,
                    section = HomeSectionState(error = buildHomeErrorMessage(result.throwable))
                )
            }
        }
    }

    private suspend fun fetchPlaylistSection(
        source: NeteaseHomePlaylistSource
    ): HomeNeteasePlaylistSectionState {
        return when (val result = fetchWithRetry("refreshRecommend/$source") {
            try {
                fetchPlaylistSource(source)
            } catch (e: ApiCodeException) {
                if (
                    source == NeteaseHomePlaylistSource.PERSONALIZED &&
                    hasRecommendLogin &&
                    shouldFallbackRecommend(e.code)
                ) {
                    NPLogger.w(
                        TAG,
                        "refreshRecommend fallback to anonymous due to api_code=${e.code}"
                    )
                    val fallbackRaw = withContext(Dispatchers.IO) {
                        client.getRecommendedPlaylists(
                            limit = HOME_NETEASE_PLAYLIST_LIMIT,
                            usePersistedCookies = false
                        )
                    }
                    parseRecommendOnWorker(fallbackRaw)
                } else {
                    throw e
                }
            }
        }) {
            is RetryLoadResult.Success -> {
                NPLogger.d(
                    TAG,
                    "refreshRecommend success: source=$source, count=${result.items.size}"
                )
                HomeNeteasePlaylistSectionState(
                    source = source,
                    section = HomeSectionState(items = result.items)
                )
            }
            is RetryLoadResult.Failure -> {
                NPLogger.e(TAG, "refreshRecommend failed: source=$source", result.throwable)
                HomeNeteasePlaylistSectionState(
                    source = source,
                    section = HomeSectionState(error = buildHomeErrorMessage(result.throwable))
                )
            }
        }
    }

    private suspend fun fetchSongSource(source: NeteaseHomeSongSource): List<SongItem> {
        if (source == NeteaseHomeSongSource.PRIVATE_FM) {
            return fetchPrivateFmSongs()
        }
        val raw = withContext(Dispatchers.IO) {
            fetchSongSourceRaw(source)
        }
        return parseSongsOnWorker(raw)
    }

    private fun fetchSongSourceRaw(source: NeteaseHomeSongSource): String {
        return when (source) {
            NeteaseHomeSongSource.TOP_SOARING -> client.getPlaylistDetail(
                playlistId = NETEASE_TOPLIST_SOARING_ID,
                n = HOME_NETEASE_SONG_LIMIT,
                s = 0
            )
            NeteaseHomeSongSource.PERSONAL_RADAR -> client.getPlaylistDetail(
                playlistId = NETEASE_PRIVATE_RADAR_PLAYLIST_ID,
                n = HOME_NETEASE_SONG_LIMIT,
                s = 0
            )
            NeteaseHomeSongSource.DAILY_RECOMMEND -> client.getDailyRecommendedSongs(
                afresh = true
            )
            NeteaseHomeSongSource.PRIVATE_FM -> client.getPersonalFmSongs()
            NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS -> client.getPersonalizedNewSongs(
                limit = HOME_NETEASE_SONG_LIMIT,
                usePersistedCookies = hasRecommendLogin
            )
            NeteaseHomeSongSource.TOP_HOT -> client.getPlaylistDetail(
                playlistId = NETEASE_TOPLIST_HOT_ID,
                n = HOME_NETEASE_SONG_LIMIT,
                s = 0
            )
            NeteaseHomeSongSource.TOP_NEW -> client.getPlaylistDetail(
                playlistId = NETEASE_TOPLIST_NEW_ID,
                n = HOME_NETEASE_SONG_LIMIT,
                s = 0
            )
        }
    }

    private suspend fun fetchPrivateFmSongs(): List<SongItem> {
        var songs = emptyList<SongItem>()
        for (batchIndex in 0 until HOME_PRIVATE_FM_MAX_BATCHES) {
            val raw = withContext(Dispatchers.IO) {
                client.getPersonalFmSongs()
            }
            val batch = parseSongsOnWorker(raw)
            if (batch.isEmpty()) break

            val merged = appendUniqueNeteaseHomeSongs(
                current = songs,
                next = batch,
                limit = HOME_NETEASE_SONG_LIMIT
            )
            if (merged.size == songs.size) {
                NPLogger.d(TAG, "private FM returned no new songs at batch=$batchIndex")
                break
            }
            songs = merged
            if (songs.size >= HOME_NETEASE_SONG_LIMIT) break
        }
        return songs
    }

    private suspend fun fetchPlaylistSource(
        source: NeteaseHomePlaylistSource
    ): List<PlaylistSummary> {
        val raw = withContext(Dispatchers.IO) {
            when (source) {
                NeteaseHomePlaylistSource.PERSONALIZED -> client.getRecommendedPlaylists(
                    limit = HOME_NETEASE_PLAYLIST_LIMIT,
                    usePersistedCookies = hasRecommendLogin
                )
                NeteaseHomePlaylistSource.DAILY_RESOURCE -> client.getDailyRecommendedPlaylists()
                NeteaseHomePlaylistSource.HIGH_QUALITY -> client.getHighQualityPlaylists(
                    cat = "全部",
                    limit = HOME_NETEASE_PLAYLIST_LIMIT,
                    before = 0L
                )
                NeteaseHomePlaylistSource.HOT_PLAYLISTS -> client.getTopPlaylists(
                    cat = "全部",
                    order = "hot",
                    limit = HOME_NETEASE_PLAYLIST_LIMIT,
                    usePersistedCookies = hasRecommendLogin
                )
                NeteaseHomePlaylistSource.ACG_PLAYLISTS -> client.getTopPlaylists(
                    cat = "ACG",
                    order = "hot",
                    limit = HOME_NETEASE_PLAYLIST_LIMIT,
                    usePersistedCookies = hasRecommendLogin
                )
            }
        }
        return parseRecommendOnWorker(raw)
    }

    private suspend fun loadRadarPlaylistSummaries(): List<PlaylistSummary> {
        return withContext(Dispatchers.IO) {
            NeteaseRadarPlaylistDefinitions.map { definition ->
                runCatching {
                    parseNeteasePlaylistDetailSummary(
                        raw = client.getPlaylistDetail(definition.id, n = 1, s = 0),
                        fallback = definition
                    )
                }.getOrElse {
                    definition.toPlaylistSummary()
                }
            }
        }
    }

    private fun buildYouTubeAuthFingerprint(bundle: YouTubeAuthBundle): String {
        return bundle.buildRefreshObserverFingerprint()
    }

    private fun YouTubeAuthBundle.hasYouTubeMusicCookieContext(): Boolean {
        return hasSavedAuthMaterial()
    }

    private sealed interface RetryLoadResult<out T> {
        data class Success<T>(val items: List<T>) : RetryLoadResult<T>
        data class Failure(val throwable: Throwable) : RetryLoadResult<Nothing>
    }
}
