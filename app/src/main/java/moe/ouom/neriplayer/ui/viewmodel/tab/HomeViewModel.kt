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

import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.ui.viewmodel.feedbackText
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.netease.api.client.mergeNeteaseSessionCookies
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicHotVideo
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankPeriod
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankSong
import moe.ouom.neriplayer.data.model.bilibili.music.BiliMusicRankType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeShelf
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeCursor
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.platform.youtube.settings.YouTubeContentSettings
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.ui.viewmodel.tab.bili.BiliMusicHomeRepository
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.neteaseRadarCacheContext
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "NERI-HomeVM"
private const val HOME_NETEASE_SONG_LIMIT = 30
private const val HOME_NETEASE_PLAYLIST_LIMIT = 30
/** 二级页每次进入都重新请求，取比首页更多的条目。 */
private const val HOME_FULL_SONG_LIMIT = 100
private const val HOME_FULL_PLAYLIST_LIMIT = 100
private const val HOME_PRIVATE_FM_MAX_BATCHES = 10
private const val HOME_MAX_FAILURE_BEFORE_WARNING = 3
private const val HOME_INITIAL_LOAD_DEFER_MS = 250L
private const val HOME_SECTION_LOAD_PARALLELISM = 6
private const val HOME_SECTION_LOAD_PARALLELISM_PER_GROUP = 2

internal fun shouldFallbackRecommend(code: Int): Boolean = code == 301 || code == 50000005

internal fun homeSongFetchAttemptCount(source: NeteaseHomeSongSource): Int {
    return if (source == NeteaseHomeSongSource.PRIVATE_FM) {
        1
    } else {
        HOME_MAX_FAILURE_BEFORE_WARNING
    }
}

internal fun shouldRefreshNeteaseHome(
    loginChanged: Boolean,
    recommendationsBootstrapped: Boolean,
    accountContextChanged: Boolean = false
): Boolean = loginChanged || accountContextChanged || !recommendationsBootstrapped

internal fun shouldAcceptNeteaseRadarPlaylistLoadResult(
    requestGeneration: Long,
    activeGeneration: Long,
    requestRadarCacheContext: String,
    activeRadarCacheContext: String
): Boolean {
    return requestGeneration == activeGeneration &&
        requestRadarCacheContext == activeRadarCacheContext
}

internal fun appendYouTubeMusicHomeShelves(
    current: List<YouTubeMusicHomeShelf>,
    page: List<YouTubeMusicHomeShelf>
): List<YouTubeMusicHomeShelf> {
    val result = current.toMutableList()
    val keyedPositions = mutableMapOf<String, Int>()
    current.forEachIndexed { index, shelf ->
        shelf.continuationKey?.let { keyedPositions[it] = index }
    }
    val seenByTitle = mutableMapOf<String, MutableSet<Pair<String, String>>>()
    for (shelf in page) {
        val key = shelf.continuationKey
        if (key == null) {
            val seenInShelf = seenByTitle.getOrPut(shelf.title) {
                current.asSequence()
                    .filter { it.continuationKey == null && it.title == shelf.title }
                    .flatMap { it.items }
                    .mapTo(mutableSetOf()) { it.browseId to it.videoId }
            }
            val fresh = shelf.items.filter { seenInShelf.add(it.browseId to it.videoId) }
            if (fresh.isNotEmpty()) result += shelf.copy(items = fresh)
            continue
        }
        val position = keyedPositions.getOrPut(key) { result.size }
        if (position == result.size) {
            result += shelf
        } else {
            val existing = result[position]
            val items = (existing.items + shelf.items).distinctBy { it.browseId to it.videoId }
            result[position] = existing.copy(items = items)
        }
    }
    return result
}

internal fun shouldAcceptYouTubeMusicHomeLoadResult(
    requestGeneration: Long,
    activeGeneration: Long,
    requestAuthFingerprint: String,
    activeAuthFingerprint: String,
    internationalizationEnabled: Boolean
): Boolean {
    return requestGeneration == activeGeneration &&
        requestAuthFingerprint == activeAuthFingerprint &&
        internationalizationEnabled
}

internal fun shouldHandleInitialNeteaseHomeCookieEmission(
    isFirstEmission: Boolean,
    initialCookies: Map<String, String>,
    emittedCookies: Map<String, String>
): Boolean = !isFirstEmission || initialCookies != emittedCookies

internal enum class HomeSectionLoadGroup {
    PLAYLISTS,
    TRENDING_SONGS,
    RADAR_SONGS
}

/** 让首页三类分区并行加载时保持公平的网络预算和完成顺序 */
internal class HomeSectionLoadCoordinator(
    maxConcurrentLoads: Int = HOME_SECTION_LOAD_PARALLELISM,
    maxConcurrentLoadsPerGroup: Int = HOME_SECTION_LOAD_PARALLELISM_PER_GROUP
) {
    private val totalSemaphore = Semaphore(maxConcurrentLoads)
    private val groupSemaphores = HomeSectionLoadGroup.entries.associateWith { group ->
        Semaphore(maxConcurrentLoadsPerGroup)
    }

    /** 在来源完成时立即交给状态层，避免慢来源阻塞已完成分区 */
    suspend fun <S, T> load(
        group: HomeSectionLoadGroup,
        sources: List<S>,
        fetch: suspend (S) -> T,
        onResult: (T) -> Unit
    ) {
        coroutineScope {
            val pending = sources.map { source ->
                async {
                    groupSemaphores.getValue(group).withPermit {
                        totalSemaphore.withPermit {
                            fetch(source)
                        }
                    }
                }
            }.toMutableList()
            while (pending.isNotEmpty()) {
                select {
                    pending.forEach { deferred ->
                        deferred.onAwait { result ->
                            pending.remove(deferred)
                            onResult(result)
                        }
                    }
                }
            }
        }
    }
}

data class HomeSectionState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null
)

/** 首页 B 站版权音乐榜模块状态（榜单类型、期数与曲目始终同源） */
data class HomeBiliMusicRankState(
    val type: BiliMusicRankType = BiliMusicRankType.HOT,
    val period: BiliMusicRankPeriod? = null,
    val songs: List<BiliMusicRankSong> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null
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
    val ytMusicHomeShelves: HomeSectionState<YouTubeMusicHomeShelf> = HomeSectionState(),
    val biliMusicRank: HomeBiliMusicRankState = HomeBiliMusicRankState(),
    val biliMusicHot: HomeSectionState<BiliMusicHotVideo> = HomeSectionState(),
    val biliMusicHomeEnabled: Boolean = false,
    val hasLogin: Boolean = false,
    val internationalizationEnabled: Boolean = false,
    val homeSourceReady: Boolean = false,
    val youtubeHomeEnabled: Boolean = false,
    val internationalHomeLayout: InternationalHomeLayout = InternationalHomeLayout(),
    val loadingMoreYouTubeHome: Boolean = false,
    val youTubeHomeLoadMoreError: UiText? = null,
    /** 大/小模块的顺序与隐藏设置，来自设置页。 */
    val layout: HomeLayoutState = HomeLayoutState()
)

private data class HomeFeedSource(
    val international: Boolean,
    val youtubeEnabled: Boolean,
    val catalogKey: String? = null,
    val authFingerprint: String = ""
)

class HomeViewModel @JvmOverloads constructor(
    application: Application,
    initialOfflineMode: Boolean = false
) : AndroidViewModel(application) {

    private val repo = AppContainer.neteaseCookieRepo
    private val client = AppContainer.neteaseClient
    private val youtubeAuthRepo = AppContainer.youtubeAuthRepo

    private val initialRecommendCookies = repo.withCurrentCookies { cookies ->
        client.setPersistedCookies(cookies)
        cookies
    }
    private var hasRecommendLogin = !initialRecommendCookies["MUSIC_U"].isNullOrBlank()
    private val _uiState = MutableStateFlow(createHomeUiState(hasRecommendLogin, loading = true))
    val uiState: StateFlow<HomeUiState> = _uiState

    private var playlistJob: Job? = null
    private var hotSongsJob: Job? = null
    private var radarSongsJob: Job? = null
    private val homeSectionLoadCoordinator = HomeSectionLoadCoordinator()
    private var radarPlaylistsJob: Job? = null
    private var ytMusicHomeJob: Job? = null
    private var failedYouTubeHomeCursor: YouTubeMusicHomeCursor? = null
    private var homeFeedSource: HomeFeedSource? = null
    private var ytMusicHomeLoadGeneration: Long = 0L
    private val firstHomeSource = CompletableDeferred<Unit>()
    private var homeRecommendationsBootstrapped = false
    private var lastNeteaseRadarCacheContext = neteaseRadarCacheContext(repo.getCookiesOnce())
    private var radarPlaylistLoadGeneration: Long = 0L
    private var biliMusicRankJob: Job? = null
    private var biliMusicHotJob: Job? = null
    private var biliMusicRankGeneration: Long = 0L
    private var biliMusicHotGeneration: Long = 0L
    private var offlineMode = initialOfflineMode
    private val canLoadBiliMusicHome: Boolean
        get() = _uiState.value.run { homeSourceReady && !internationalizationEnabled && biliMusicHomeEnabled }


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
        viewModelScope.launch {
            combine(
                AppContainer.settingsRepo.homeCardBilibiliFlow,
                AppContainer.settingsRepo.internationalizationEnabledFlow
            ) { savedEnabled, internationalEnabled ->
                savedEnabled && !internationalEnabled
            }.collect { enabled ->
                if (_uiState.value.biliMusicHomeEnabled == enabled) return@collect
                if (enabled) {
                    _uiState.update { state -> state.copy(biliMusicHomeEnabled = true) }
                    if (homeRecommendationsBootstrapped) loadBiliMusicHome()
                } else {
                    cancelBiliMusicJobs()
                    _uiState.update { state ->
                        state.copy(
                            biliMusicHomeEnabled = false,
                            biliMusicRank = HomeBiliMusicRankState(type = state.biliMusicRank.type),
                            biliMusicHot = HomeSectionState()
                        )
                    }
                }
            }
        }

        // 观察国际化设置变化, 切换推荐源
        viewModelScope.launch {
            combine(
                AppContainer.settingsRepo.internationalizationEnabledFlow,
                AppContainer.settingsRepo.youtubeEnabledFlow,
                YouTubeContentSettings.catalogKeyFlow,
                youtubeAuthRepo.authFlow,
                AppContainer.settingsRepo.internationalHomeLayoutFlow
            ) { international, enabled, catalogKey, auth, layout ->
                val useYouTube = international && enabled
                val showFeed = useYouTube && layout.visibleSections.any { it != InternationalHomeSectionIds.CONTINUE }
                val source = if (showFeed) {
                    HomeFeedSource(true, true, catalogKey, auth.buildRefreshObserverFingerprint())
                } else HomeFeedSource(international, useYouTube)
                source to layout
            }.distinctUntilChanged().collect { (source, layout) ->
                if (source == homeFeedSource) {
                    _uiState.update { it.copy(internationalHomeLayout = layout) }
                    return@collect
                }
                val wasInternational = _uiState.value.internationalizationEnabled
                homeFeedSource = source
                cancelYouTubeHomeJobs()
                _uiState.update { state ->
                    state.copy(
                        homeSourceReady = true,
                        internationalizationEnabled = source.international,
                        internationalHomeLayout = layout,
                        youtubeHomeEnabled = source.youtubeEnabled,
                        loadingMoreYouTubeHome = false,
                        youTubeHomeLoadMoreError = null,
                        ytMusicHomeShelves = HomeSectionState()
                    )
                }
                firstHomeSource.complete(Unit)
                if (source.international) {
                    cancelNeteaseHomeJobs()
                    refreshYtMusicHome()
                } else if (wasInternational) {
                    homeRecommendationsBootstrapped = true
                    refreshNeteaseHome()
                }
            }
        }

        // 观察首页模块布局设置
        viewModelScope.launch {
            val settingsRepo = AppContainer.settingsRepo
            val sectionOrder = combine(
                settingsRepo.homeMajorOrderFlow,
                settingsRepo.homeTrendingOrderFlow,
                settingsRepo.homeTrendingHiddenFlow,
                settingsRepo.homeRadarOrderFlow,
                settingsRepo.homeRadarHiddenFlow
            ) { major, trending, trendingHidden, radar, radarHidden ->
                HomeLayoutState(
                    majorOrder = major,
                    trendingOrder = trending,
                    trendingHidden = trendingHidden,
                    radarOrder = radar,
                    radarHidden = radarHidden
                )
            }
            combine(
                sectionOrder,
                settingsRepo.homeRecommendedOrderFlow,
                settingsRepo.homeRecommendedHiddenFlow
            ) { layout, recommended, hidden ->
                layout.copy(
                    recommendedOrder = recommended,
                    recommendedHidden = hidden
                )
            }.collect { layout ->
                _uiState.update { state -> state.copy(layout = layout) }
            }
        }

        // 登录后自动刷新首页推荐歌单
        viewModelScope.launch {
            var isFirstCookieEmission = true
            repo.cookieFlow.collect { raw ->
                if (!repo.withCurrentCookiesIfMatches(raw) { currentCookies ->
                        client.setPersistedCookies(currentCookies)
                    }
                ) {
                    return@collect
                }
                val shouldHandleEmission = shouldHandleInitialNeteaseHomeCookieEmission(
                    isFirstEmission = isFirstCookieEmission,
                    initialCookies = initialRecommendCookies,
                    emittedCookies = raw
                )
                isFirstCookieEmission = false
                if (!shouldHandleEmission) return@collect
                NPLogger.d(TAG, "cookieFlow updated: keys=${raw.keys.joinToString()}")
                val nextHasLogin = !raw["MUSIC_U"].isNullOrBlank()
                val nextRadarCacheContext = neteaseRadarCacheContext(raw)
                val accountContextChanged =
                    lastNeteaseRadarCacheContext != nextRadarCacheContext
                lastNeteaseRadarCacheContext = nextRadarCacheContext
                val loginChanged = hasRecommendLogin != nextHasLogin
                hasRecommendLogin = nextHasLogin
                if (loginChanged) {
                    _uiState.value = _uiState.value.copy(hasLogin = nextHasLogin)
                }
                if (accountContextChanged) {
                    _uiState.update { state ->
                        state.copy(radarPlaylists = HomeSectionState())
                    }
                }
                if (firstHomeSource.isCompleted &&
                    shouldRefreshNeteaseHome(
                        loginChanged = loginChanged,
                        recommendationsBootstrapped = homeRecommendationsBootstrapped,
                        accountContextChanged = accountContextChanged
                    )
                ) {
                    homeRecommendationsBootstrapped = true
                    refreshNeteaseHome()
                }
            }
        }
        viewModelScope.launch {
            delay(HOME_INITIAL_LOAD_DEFER_MS.milliseconds)
            firstHomeSource.await()
            if (!homeRecommendationsBootstrapped) {
                homeRecommendationsBootstrapped = true
                refreshNeteaseHome()
            }
            // 非国际化首页按保存的开关独立加载 B 站音乐模块
            loadBiliMusicHome(force = false)
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        if (offlineMode == enabled) return

        NPLogger.d(TAG, "setOfflineMode: $enabled")
        offlineMode = enabled
        if (!enabled) {
            if (_uiState.value.internationalizationEnabled) refreshYtMusicHome() else refreshNeteaseHome()
            return
        }

        cancelHomeNetworkJobs()
        _uiState.update { state ->
            state.copy(
                playlistSections = clearPlaylistSectionLoading(state.playlistSections),
                trendingSongSections = clearSongSectionLoading(state.trendingSongSections),
                radarSongSections = clearSongSectionLoading(state.radarSongSections),
                radarPlaylists = state.radarPlaylists.copy(loading = false, error = null),
                ytMusicHomeShelves = state.ytMusicHomeShelves.copy(loading = false, error = null),
                loadingMoreYouTubeHome = false,
                youTubeHomeLoadMoreError = null,
                biliMusicRank = state.biliMusicRank.copy(loading = false, error = null),
                biliMusicHot = state.biliMusicHot.copy(loading = false, error = null)
            )
        }
    }

    private fun cancelHomeNetworkJobs() {
        cancelNeteaseHomeJobs()
        cancelYouTubeHomeJobs()
        cancelBiliMusicJobs()
    }

    private fun cancelNeteaseHomeJobs() {
        radarPlaylistLoadGeneration++
        playlistJob?.cancel()
        hotSongsJob?.cancel()
        radarSongsJob?.cancel()
        radarPlaylistsJob?.cancel()
    }

    private fun cancelYouTubeHomeJobs() {
        ytMusicHomeLoadGeneration += 1L
        ytMusicHomeJob?.cancel()
        ytMusicHomeJob = null
        failedYouTubeHomeCursor = null
    }

    private fun cancelBiliMusicJobs() {
        biliMusicRankGeneration += 1L
        biliMusicHotGeneration += 1L
        biliMusicRankJob?.cancel()
        biliMusicRankJob = null
        biliMusicHotJob?.cancel()
        biliMusicHotJob = null
    }

    /** 首页 B 站音乐两个模块的入口（互不阻塞） */
    fun loadBiliMusicHome(force: Boolean = false) {
        loadBiliMusicRank(force = force)
        loadBiliMusicHot(force = force)
    }

    /** 首页刷新复用正在加载的分区，避免重复请求和补全官方 MV */
    private fun loadBiliMusicHomeCoalesced() {
        if (biliMusicRankJob?.isActive != true) {
            loadBiliMusicRank(force = true)
        }
        if (biliMusicHotJob?.isActive != true) {
            loadBiliMusicHot(force = true)
        }
    }

    /** 切换版权榜类型：先清掉上一个榜的数据，避免串期显示 */
    fun selectBiliMusicRankType(type: BiliMusicRankType) {
        if (!canLoadBiliMusicHome) return
        val current = _uiState.value.biliMusicRank
        if (current.type == type && current.songs.isNotEmpty()) return

        // 脱机时同样先读缓存，且不置 loading：随后的 loadBiliMusicRank 会走仅缓存分支，
        // 若在这里把 loading 置为 true 会短暂闪一下加载态。
        val cached = BiliMusicHomeRepository.cachedRank(type)
        _uiState.update { state ->
            state.copy(
                biliMusicRank = HomeBiliMusicRankState(
                    type = type,
                    period = cached?.period,
                    songs = cached?.songs.orEmpty(),
                    loading = cached == null && !offlineMode,
                    error = null
                )
            )
        }
        loadBiliMusicRank(force = true)
    }

    fun loadBiliMusicRank(force: Boolean = false) {
        if (!canLoadBiliMusicHome) return
        val current = _uiState.value.biliMusicRank
        if (!force && (current.songs.isNotEmpty() || current.loading)) return

        if (offlineMode) {
            val cached = BiliMusicHomeRepository.cachedRank(current.type)
            _uiState.update { state ->
                if (state.biliMusicRank.type != current.type) {
                    state
                } else {
                    state.copy(
                        biliMusicRank = state.biliMusicRank.copy(
                            period = cached?.period ?: state.biliMusicRank.period,
                            songs = cached?.songs ?: state.biliMusicRank.songs,
                            loading = false,
                            error = null
                        )
                    )
                }
            }
            return
        }

        val type = current.type
        val generation = ++biliMusicRankGeneration
        biliMusicRankJob?.cancel()
        _uiState.update { state ->
            if (state.biliMusicRank.type != type) {
                state
            } else {
                state.copy(biliMusicRank = state.biliMusicRank.copy(loading = true, error = null))
            }
        }
        biliMusicRankJob = viewModelScope.launch {
            val result = runCatching { BiliMusicHomeRepository.loadRank(type, force) }
            if (generation != biliMusicRankGeneration) return@launch
            result.fold(
                onSuccess = { snapshot ->
                    _uiState.update { state ->
                        if (state.biliMusicRank.type != type) {
                            state
                        } else {
                            state.copy(
                                biliMusicRank = state.biliMusicRank.copy(
                                    period = snapshot.period,
                                    songs = snapshot.songs,
                                    loading = false,
                                    error = null
                                )
                            )
                        }
                    }
                },
                onFailure = { error ->
                    val message = buildHomeErrorText(error)
                    _uiState.update { state ->
                        if (state.biliMusicRank.type != type) {
                            state
                        } else {
                            state.copy(
                                biliMusicRank = state.biliMusicRank.copy(
                                    loading = false,
                                    error = message
                                )
                            )
                        }
                    }
                }
            )
        }
    }

    fun loadBiliMusicHot(force: Boolean = false) {
        if (!canLoadBiliMusicHome) return
        val current = _uiState.value.biliMusicHot
        if (!force && (current.items.isNotEmpty() || current.loading)) return

        if (offlineMode) {
            val cached = BiliMusicHomeRepository.cachedHot()
            _uiState.update { state ->
                state.copy(
                    biliMusicHot = state.biliMusicHot.copy(
                        items = cached ?: state.biliMusicHot.items,
                        loading = false,
                        error = null
                    )
                )
            }
            return
        }

        val generation = ++biliMusicHotGeneration
        biliMusicHotJob?.cancel()
        _uiState.update { state ->
            state.copy(biliMusicHot = state.biliMusicHot.copy(loading = true, error = null))
        }
        biliMusicHotJob = viewModelScope.launch {
            val result = runCatching { BiliMusicHomeRepository.loadHot(force) }
            if (generation != biliMusicHotGeneration) return@launch
            result.fold(
                onSuccess = { videos ->
                    _uiState.update { state ->
                        state.copy(
                            biliMusicHot = state.biliMusicHot.copy(
                                items = videos,
                                loading = false,
                                error = null
                            )
                        )
                    }
                },
                onFailure = { error ->
                    val message = buildHomeErrorText(error)
                    _uiState.update { state ->
                        state.copy(
                            biliMusicHot = state.biliMusicHot.copy(loading = false, error = message)
                        )
                    }
                }
            )
        }
    }

    fun refreshNeteaseHome() {
        if (!firstHomeSource.isCompleted || offlineMode || _uiState.value.internationalizationEnabled) return
        homeRecommendationsBootstrapped = true
        refreshRecommend()
        loadHomeRecommendations(force = true)
        refreshRadarPlaylists()
        loadBiliMusicHomeCoalesced()
    }

    /** 拉首页推荐歌单 */
    private fun refreshRecommend() {
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
            homeSectionLoadCoordinator.load(
                group = HomeSectionLoadGroup.PLAYLISTS,
                sources = sources,
                fetch = ::fetchPlaylistSection
            ) { section ->
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

    private fun loadHomeRecommendations(force: Boolean = false) {
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

    /** 刷新首页热门歌曲板块，保留各来源独立的加载和错误状态 */
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
            homeSectionLoadCoordinator.load(
                group = HomeSectionLoadGroup.TRENDING_SONGS,
                sources = sources,
                fetch = { source -> fetchSongSection("refreshHotSongs", source) }
            ) { section ->
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

    /** 刷新首页雷达歌曲板块，保留各来源独立的加载和错误状态 */
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
            homeSectionLoadCoordinator.load(
                group = HomeSectionLoadGroup.RADAR_SONGS,
                sources = sources,
                fetch = { source -> fetchSongSection("refreshRadarSongs", source) }
            ) { section ->
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
        radarPlaylistLoadGeneration += 1L
        val loadGeneration = radarPlaylistLoadGeneration
        val requestRadarCacheContext = lastNeteaseRadarCacheContext
        val previous = _uiState.value.radarPlaylists
        _uiState.value = _uiState.value.copy(
            radarPlaylists = previous.copy(loading = true, error = null)
        )
        radarPlaylistsJob = viewModelScope.launch {
            when (val result = fetchWithRetry("refreshRadarPlaylists") {
                loadRadarPlaylistSummaries(requestRadarCacheContext)
            }) {
                is RetryLoadResult.Success -> {
                    if (!isCurrentRadarPlaylistLoad(loadGeneration, requestRadarCacheContext)) {
                        return@launch
                    }
                    NPLogger.d(TAG, "refreshRadarPlaylists success: count=${result.items.size}")
                    _uiState.value = _uiState.value.copy(
                        radarPlaylists = HomeSectionState(items = result.items)
                    )
                }
                is RetryLoadResult.Failure -> {
                    if (!isCurrentRadarPlaylistLoad(loadGeneration, requestRadarCacheContext)) {
                        return@launch
                    }
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

    private fun isCurrentRadarPlaylistLoad(
        loadGeneration: Long,
        requestRadarCacheContext: String
    ): Boolean {
        return shouldAcceptNeteaseRadarPlaylistLoadResult(
            requestGeneration = loadGeneration,
            activeGeneration = radarPlaylistLoadGeneration,
            requestRadarCacheContext = requestRadarCacheContext,
            activeRadarCacheContext = neteaseRadarCacheContext(repo.getCookiesOnce())
        )
    }

    fun refreshYtMusicHome() {
        if (offlineMode || homeFeedSource?.catalogKey == null) return
        if (ytMusicHomeJob?.isActive == true && !_uiState.value.loadingMoreYouTubeHome) return
        ytMusicHomeJob?.cancel()
        loadYouTubeHomePages(null)
    }

    fun retryYouTubeHomeContinuation() {
        if (offlineMode || ytMusicHomeJob?.isActive == true) return
        failedYouTubeHomeCursor?.let(::loadYouTubeHomePages)
    }

    private fun loadYouTubeHomePages(initialCursor: YouTubeMusicHomeCursor?) {
        val source = homeFeedSource ?: return
        val generation = ++ytMusicHomeLoadGeneration
        failedYouTubeHomeCursor = null
        _uiState.update { state ->
            state.copy(
                ytMusicHomeShelves = state.ytMusicHomeShelves.copy(loading = initialCursor == null, error = null),
                loadingMoreYouTubeHome = initialCursor != null,
                youTubeHomeLoadMoreError = null
            )
        }
        ytMusicHomeJob = viewModelScope.launch {
            var cursor = initialCursor
            val requested = listOfNotNull(cursor?.requestIdentity).toMutableSet()
            try {
                do {
                    val page = AppContainer.youtubeMusicClient.getHomeFeedPage(cursor)
                    if (!isCurrentYouTubeHomePage(generation, source)) return@launch
                    val shelves = if (cursor == null) {
                        page.shelves
                    } else {
                        appendYouTubeMusicHomeShelves(_uiState.value.ytMusicHomeShelves.items, page.shelves)
                    }
                    cursor = page.next?.takeIf { it.token.isNotBlank() && requested.add(it.requestIdentity) }
                    _uiState.update { state ->
                        state.copy(
                            ytMusicHomeShelves = HomeSectionState(items = shelves),
                            loadingMoreYouTubeHome = cursor != null
                        )
                    }
                } while (cursor != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrentYouTubeHomePage(generation, source)) return@launch
                val message = buildHomeErrorText(error)
                failedYouTubeHomeCursor = cursor
                _uiState.update { state ->
                    state.copy(
                        ytMusicHomeShelves = state.ytMusicHomeShelves.copy(loading = false, error = message.takeIf { cursor == null }),
                        loadingMoreYouTubeHome = false,
                        youTubeHomeLoadMoreError = message.takeIf { cursor != null }
                    )
                }
            }
        }
    }

    private fun isCurrentYouTubeHomePage(generation: Long, source: HomeFeedSource): Boolean {
        return source.catalogKey == YouTubeContentSettings.catalogKey() &&
            shouldAcceptYouTubeMusicHomeLoadResult(
                requestGeneration = generation,
                activeGeneration = ytMusicHomeLoadGeneration,
                requestAuthFingerprint = source.authFingerprint,
                activeAuthFingerprint = youtubeAuthRepo.getAuthOnce().buildRefreshObserverFingerprint(),
                internationalizationEnabled = _uiState.value.youtubeHomeEnabled
            )
    }

    private suspend fun parseRecommendOnWorker(
        raw: String,
        limit: Int = HOME_NETEASE_PLAYLIST_LIMIT
    ): List<PlaylistSummary> =
        withContext(Dispatchers.Default) {
            parseNeteaseHomePlaylists(raw, limit = limit)
        }

    private suspend fun parseSongsOnWorker(
        raw: String,
        limit: Int = HOME_NETEASE_SONG_LIMIT
    ): List<SongItem> =
        withContext(Dispatchers.Default) {
            parseNeteaseHomeSongs(raw, limit = limit)
        }

    private suspend fun fetchSongSection(
        name: String,
        source: NeteaseHomeSongSource
    ): HomeNeteaseSongSectionState {
        return when (
            val result = fetchWithRetry(
                name = "$name/$source",
                maxAttempts = homeSongFetchAttemptCount(source)
            ) {
                fetchSongSource(source)
            }
        ) {
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
                    section = HomeSectionState(error = buildHomeErrorText(result.throwable))
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
                    section = HomeSectionState(error = buildHomeErrorText(result.throwable))
                )
            }
        }
    }

    /**
     * 拉取某个首页歌曲小模块的完整列表，供二级页使用。
     */
    suspend fun fetchFullSongSection(
        source: NeteaseHomeSongSource
    ): Result<List<SongItem>> {
        return runCatching {
            if (source == NeteaseHomeSongSource.PRIVATE_FM) {
                fetchPrivateFmSongs()
            } else {
                val raw = withContext(Dispatchers.IO) {
                    fetchFullSongSourceRaw(source)
                }
                parseSongsOnWorker(raw, limit = HOME_FULL_SONG_LIMIT)
            }
        }
    }

    /**
     * 拉取某个首页歌单分类的完整列表，供二级页使用。
     *
     * 与首页那份截断列表不同，这里按 [HOME_FULL_PLAYLIST_LIMIT] 请求并解析，
     * 否则会拿到上百条数据却只保留首页用的 30 条。
     */
    suspend fun fetchFullPlaylistSection(
        source: NeteaseHomePlaylistSource
    ): Result<List<PlaylistSummary>> {
        return runCatching {
            val raw = withContext(Dispatchers.IO) {
                fetchFullPlaylistSourceRaw(source)
            }
            parseRecommendOnWorker(raw, limit = HOME_FULL_PLAYLIST_LIMIT)
        }.recoverCatching { error ->
            // 与首页加载保持一致：cookie 过期导致 301 / 50000005 时退回匿名请求，
            // 否则用户在首页能正常看到该模块，点进二级页却只会看到错误页。
            if (
                error is ApiCodeException &&
                source == NeteaseHomePlaylistSource.PERSONALIZED &&
                hasRecommendLogin &&
                shouldFallbackRecommend(error.code)
            ) {
                NPLogger.w(
                    TAG,
                    "fetchFullPlaylistSection fallback to anonymous due to api_code=${error.code}"
                )
                val fallbackRaw = withContext(Dispatchers.IO) {
                    client.getRecommendedPlaylists(
                        limit = HOME_FULL_PLAYLIST_LIMIT,
                        usePersistedCookies = false
                    )
                }
                parseRecommendOnWorker(fallbackRaw, limit = HOME_FULL_PLAYLIST_LIMIT)
            } else {
                throw error
            }
        }
    }

    /** 二级页使用的完整歌单来源请求参数，比首页那份截断列表取更多。 */
    private fun fetchFullPlaylistSourceRaw(source: NeteaseHomePlaylistSource): String {
        return when (source) {
            NeteaseHomePlaylistSource.PERSONALIZED -> client.getRecommendedPlaylists(
                limit = HOME_FULL_PLAYLIST_LIMIT,
                usePersistedCookies = hasRecommendLogin
            )
            NeteaseHomePlaylistSource.DAILY_RESOURCE -> client.getDailyRecommendedPlaylists()
            NeteaseHomePlaylistSource.HIGH_QUALITY -> client.getHighQualityPlaylists(
                cat = "全部",
                limit = HOME_FULL_PLAYLIST_LIMIT,
                before = 0L
            )
            NeteaseHomePlaylistSource.HOT_PLAYLISTS -> client.getTopPlaylists(
                cat = "全部",
                order = "hot",
                limit = HOME_FULL_PLAYLIST_LIMIT,
                usePersistedCookies = hasRecommendLogin
            )
            NeteaseHomePlaylistSource.ACG_PLAYLISTS -> client.getTopPlaylists(
                cat = "ACG",
                order = "hot",
                limit = HOME_FULL_PLAYLIST_LIMIT,
                usePersistedCookies = hasRecommendLogin
            )
        }
    }

    /** 二级页使用的完整歌曲来源请求参数，比首页那份截断列表取更多。 */
    private fun fetchFullSongSourceRaw(source: NeteaseHomeSongSource): String {
        return when (source) {
            NeteaseHomeSongSource.TOP_SOARING -> client.getPlaylistDetail(
                playlistId = NETEASE_TOPLIST_SOARING_ID,
                n = HOME_FULL_SONG_LIMIT,
                s = 0
            )
            NeteaseHomeSongSource.PERSONAL_RADAR -> client.getPlaylistDetail(
                playlistId = NETEASE_PRIVATE_RADAR_PLAYLIST_ID,
                n = HOME_FULL_SONG_LIMIT,
                s = 0
            )
            NeteaseHomeSongSource.DAILY_RECOMMEND -> client.getDailyRecommendedSongs(
                afresh = true
            )
            NeteaseHomeSongSource.PRIVATE_FM -> client.getPersonalFmSongs()
            NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS -> client.getPersonalizedNewSongs(
                limit = HOME_FULL_SONG_LIMIT,
                usePersistedCookies = hasRecommendLogin
            )
            NeteaseHomeSongSource.TOP_HOT -> client.getPlaylistDetail(
                playlistId = NETEASE_TOPLIST_HOT_ID,
                n = HOME_FULL_SONG_LIMIT,
                s = 0
            )
            NeteaseHomeSongSource.TOP_NEW -> client.getPlaylistDetail(
                playlistId = NETEASE_TOPLIST_NEW_ID,
                n = HOME_FULL_SONG_LIMIT,
                s = 0
            )
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

    /** 将首页来源的请求参数集中在一起，避免登录态契约在调用点分散 */
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

    private suspend fun loadRadarPlaylistSummaries(
        expectedRadarCacheContext: String
    ): List<PlaylistSummary> {
        val hasLogin = hasRecommendLogin
        if (hasLogin) {
            prepareNeteaseRadarSession()
        }
        val summaries = loadNeteaseRadarPlaylistSummaries(
            definitions = NeteaseRadarPlaylistDefinitions,
            loadMetadata = { playlistId ->
                client.getRadarPlaylistMetadataCancellable(playlistId)
            },
            onLoadFailure = { definition, error ->
                NPLogger.w(TAG, "radar metadata failed: playlistId=${definition.id}, error=${error.message}")
            }
        )
        if (hasLogin) {
            persistNeteaseRadarSessionCookies(expectedRadarCacheContext)
        }
        return summaries
    }

    private suspend fun prepareNeteaseRadarSession() {
        try {
            withContext(Dispatchers.IO) {
                client.ensurePersonalizedSession()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "radar session preheat failed: ${error.message}")
        }
    }

    private fun persistNeteaseRadarSessionCookies(expectedRadarCacheContext: String) {
        val persisted = repo.getCookiesOnce()
        if (neteaseRadarCacheContext(persisted) != expectedRadarCacheContext) return
        val updated = mergeNeteaseSessionCookies(
            persistedCookies = persisted,
            runtimeCookies = client.getNeteaseRequestCookies()
        )
        if (
            updated != persisted &&
                repo.saveCookiesIfCurrent(
                    expectedCookies = persisted,
                    cookies = updated
                )
        ) {
            NPLogger.d(TAG, "persisted NetEase radar session context")
        }
    }

}

internal sealed interface RetryLoadResult<out T> {
    data class Success<T>(val items: List<T>) : RetryLoadResult<T>
    data class Failure(val throwable: Throwable) : RetryLoadResult<Nothing>
}

internal suspend fun <T> fetchWithRetry(
    name: String,
    maxAttempts: Int = HOME_MAX_FAILURE_BEFORE_WARNING,
    fetch: suspend () -> List<T>
): RetryLoadResult<T> {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    var lastError: Throwable? = null
    repeat(maxAttempts) { attempt ->
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
                "$name attempt ${attempt + 1}/$maxAttempts failed: ${e.message}"
            )
        }
    }
    return RetryLoadResult.Failure(lastError ?: IllegalStateException("Unknown error"))
}

internal fun buildHomeErrorMessage(error: Throwable, context: Context): String =
    buildHomeErrorText(error).resolve(context.resources)

internal fun buildHomeErrorText(error: Throwable): UiText {
    return when {
        error is IOException -> feedbackText(
            CoreCommonR.string.home_error_network,
            error.messageOrTypeName()
        )
        error is ApiCodeException && error.code == 50000005 ->
            feedbackText(CoreCommonR.string.home_login_required)
        error is ApiCodeException -> feedbackText(CoreCommonR.string.error_api_code, error.code)
        else -> feedbackText(
            CoreCommonR.string.home_error_unknown,
            error.messageOrTypeName()
        )
    }
}

private fun Throwable.messageOrTypeName(): String = message ?: javaClass.simpleName
