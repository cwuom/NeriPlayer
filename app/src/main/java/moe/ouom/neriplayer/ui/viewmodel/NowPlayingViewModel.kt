package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/NowPlayingViewModel
 * Created: 2025/8/17
 */

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.api.search.SearchManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.BiliUploaderSummary
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistsFromSongDetail
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.R
import java.util.concurrent.atomic.AtomicLong

private const val YOUTUBE_MUSIC_CREATOR_SEARCH_LIMIT = 8

private val YouTubeMusicArtistSeparator = Regex(
    pattern = """\s+(?:/|&|x|feat\.?|featuring|ft\.?)\s+|[、,，;；]\s*""",
    option = RegexOption.IGNORE_CASE
)

internal fun splitYouTubeMusicArtistNames(artist: String): List<String> {
    return artist
        .split(YouTubeMusicArtistSeparator)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
}

internal fun findExactYouTubeMusicCreatorMatches(
    artistName: String,
    candidates: List<YouTubeMusicCreatorSummary>
): List<YouTubeMusicCreatorSummary> {
    val normalizedArtistName = normalizeYouTubeMusicCreatorName(artistName)
    if (normalizedArtistName.isBlank()) {
        return emptyList()
    }
    return candidates.filter { candidate ->
        normalizeYouTubeMusicCreatorName(candidate.title) == normalizedArtistName
    }
}

private fun normalizeYouTubeMusicCreatorName(value: String): String {
    return value
        .replace(Regex("""\s*-\s*topic\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()
        .replace(Regex("""\s+"""), " ")
        .lowercase()
}

data class ManualSearchState(
    val keyword: String = "",
    val selectedPlatform: MusicPlatform = MusicPlatform.QQ_MUSIC,
    val searchResults: List<SongSearchInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCloudMusicAvailable: Boolean = false,
    val isApplyingMetadata: Boolean = false
)

class NowPlayingViewModel : ViewModel() {

    private val searchRequestCoordinator = ManualSearchRequestCoordinator()
    private val originalInfoRequestId = AtomicLong(0L)
    private var searchJob: Job? = null
    private var nextSearchSessionId = 0L
    private var activeSearchSessionId = 0L

    private val _manualSearchState = MutableStateFlow(
        ManualSearchState(
            selectedPlatform = if (
                AppContainer.neteaseCookieRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing
            ) {
                MusicPlatform.QQ_MUSIC
            } else {
                MusicPlatform.CLOUD_MUSIC
            },
            isCloudMusicAvailable = AppContainer.neteaseCookieRepo.getAuthHealthOnce().state != SavedCookieAuthState.Missing
        )
    )
    val manualSearchState = _manualSearchState.asStateFlow()

    init {
        viewModelScope.launch {
            AppContainer.neteaseCookieRepo.authHealthFlow.collect { health ->
                val isCloudMusicAvailable = health.state != SavedCookieAuthState.Missing
                val shouldFallbackToQq = !isCloudMusicAvailable &&
                    _manualSearchState.value.selectedPlatform == MusicPlatform.CLOUD_MUSIC
                if (shouldFallbackToQq) {
                    cancelSearchRequest()
                }
                _manualSearchState.update { current ->
                    val nextPlatform = if (
                        !isCloudMusicAvailable && current.selectedPlatform == MusicPlatform.CLOUD_MUSIC
                    ) {
                        MusicPlatform.QQ_MUSIC
                    } else {
                        current.selectedPlatform
                    }
                    current.copy(
                        selectedPlatform = nextPlatform,
                        isCloudMusicAvailable = isCloudMusicAvailable,
                        searchResults = if (shouldFallbackToQq) {
                            emptyList()
                        } else {
                            current.searchResults
                        },
                        error = if (shouldFallbackToQq) null else current.error
                    )
                }
            }
        }
    }

    fun prepareForSearch(initialKeyword: String): Long {
        cancelSearchRequest()
        val sessionId = ++nextSearchSessionId
        activeSearchSessionId = sessionId
        _manualSearchState.update {
            it.copy(
                keyword = initialKeyword,
                searchResults = emptyList(), // 清空上次结果
                error = null
            )
        }
        return sessionId
    }

    fun onKeywordChange(newKeyword: String) {
        if (_manualSearchState.value.keyword == newKeyword) return
        cancelSearchRequest()
        _manualSearchState.update {
            it.copy(
                keyword = newKeyword,
                searchResults = emptyList(),
                error = null
            )
        }
    }

    fun selectPlatform(platform: MusicPlatform) {
        if (_manualSearchState.value.selectedPlatform == platform) return
        cancelSearchRequest()
        _manualSearchState.update {
            it.copy(
                selectedPlatform = platform,
                searchResults = emptyList(),
                error = null
            )
        }
        performSearch()
    }

    fun performSearch() {
        val state = _manualSearchState.value
        val keyword = state.keyword.trim()
        if (keyword.isBlank()) {
            cancelSearchRequest()
            return
        }
        if (
            state.selectedPlatform == MusicPlatform.CLOUD_MUSIC &&
            AppContainer.neteaseCookieRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing
        ) {
            cancelSearchRequest()
            _manualSearchState.update {
                it.copy(
                    isLoading = false,
                    searchResults = emptyList(),
                    error = AppContainer.applicationContext.getString(R.string.netease_login_required_search)
                )
            }
            return
        }

        val request = ManualSearchRequest(
            keyword = keyword,
            platform = state.selectedPlatform
        )
        val requestToken = searchRequestCoordinator.begin(request) ?: return
        searchJob?.cancel()
        _manualSearchState.update {
            it.copy(
                isLoading = true,
                searchResults = emptyList(),
                error = null
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val results = SearchManager.search(
                    keyword = request.keyword,
                    platform = request.platform,
                )
                if (!searchRequestCoordinator.isLatest(requestToken)) return@launch
                _manualSearchState.update { current ->
                    if (!current.matches(request)) current else current.copy(
                        searchResults = results,
                        error = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (searchRequestCoordinator.isLatest(requestToken)) {
                    _manualSearchState.update { current ->
                        if (!current.matches(request)) current else current.copy(
                            error = AppContainer.applicationContext.getString(
                                R.string.error_search_failed,
                                e.message.orEmpty()
                            )
                        )
                    }
                }
            } finally {
                if (searchRequestCoordinator.isLatest(requestToken)) {
                    searchRequestCoordinator.complete(requestToken)
                    searchJob = null
                    _manualSearchState.update { current ->
                        if (current.isLoading) current.copy(isLoading = false) else current
                    }
                }
            }
        }
    }

    fun finishSearchSession(sessionId: Long) {
        if (activeSearchSessionId != sessionId) return
        activeSearchSessionId = 0L
        cancelSearchRequest()
    }

    private fun cancelSearchRequest() {
        searchJob?.cancel()
        searchJob = null
        searchRequestCoordinator.invalidate()
        _manualSearchState.update { current ->
            if (current.isLoading) current.copy(isLoading = false) else current
        }
    }

    fun onSongSelected(
        originalSong: SongItem,
        selectedSong: SongSearchInfo,
        onComplete: (Boolean) -> Unit = {}
    ): Boolean {
        if (_manualSearchState.value.isApplyingMetadata) return false
        _manualSearchState.update { it.copy(isApplyingMetadata = true) }
        PlayerManager.replaceMetadataFromSearch(
            originalSong = originalSong,
            selectedSong = selectedSong,
            onComplete = { success ->
                _manualSearchState.update { it.copy(isApplyingMetadata = false) }
                onComplete(success)
            }
        )
        return true
    }

    fun downloadSong(context: Context, song: SongItem) {
        GlobalDownloadManager.startDownload(context, song)
    }

    fun cancelDownload(songKey: String) {
        GlobalDownloadManager.cancelDownloadTask(songKey)
    }

    fun resumeDownload(context: Context, songKey: String) {
        GlobalDownloadManager.resumeDownloadTask(context, songKey)
    }

    fun retryDownload(context: Context, song: SongItem) {
        GlobalDownloadManager.startDownload(context, song)
    }

    fun resolveNeteaseArtists(
        song: SongItem,
        onResult: (List<NeteaseArtistSummary>) -> Unit,
        onError: (Throwable) -> Unit = {}
    ) {
        val cachedArtists = song.neteaseArtists.orEmpty()
            .filter { it.id > 0L && it.name.isNotBlank() }
        if (cachedArtists.isNotEmpty()) {
            onResult(cachedArtists)
            return
        }

        val songId = song.matchedSongId?.toLongOrNull()?.takeIf { it > 0L }
            ?: song.audioId?.toLongOrNull()?.takeIf { it > 0L }
            ?: song.id.takeIf { it > 0L }
        if (songId == null) {
            onResult(emptyList())
            return
        }

        viewModelScope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    AppContainer.neteaseClient.getSongDetail(listOf(songId))
                }
                parseNeteaseArtistsFromSongDetail(raw)
            }.onSuccess(onResult).onFailure { error ->
                NPLogger.e("NowPlayingViewModel", "解析网易云歌手失败", error)
                onError(error)
            }
        }
    }

    fun resolveBiliUploader(
        song: SongItem,
        onResult: (BiliUploaderSummary) -> Unit,
        onUnavailable: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                val uploader = withContext(Dispatchers.IO) {
                    resolveBiliSong(song, AppContainer.biliClient)
                        ?.videoInfo
                        ?.takeIf { it.ownerMid > 0L }
                        ?.let { videoInfo ->
                            BiliUploaderSummary(
                                mid = videoInfo.ownerMid,
                                name = videoInfo.ownerName.ifBlank { song.artist },
                                avatarUrl = videoInfo.ownerFace
                            )
                        }
                }
                if (uploader != null) {
                    onResult(uploader)
                } else {
                    onUnavailable()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e("NowPlayingViewModel", "解析 B 站 UP 主失败", error)
                onError(error)
            }
        }
    }

    fun resolveYouTubeMusicCreators(
        song: SongItem,
        onResult: (List<YouTubeMusicCreatorSummary>) -> Unit,
        onError: (Throwable) -> Unit = {}
    ) {
        val artistNames = splitYouTubeMusicArtistNames(song.artist)
        if (artistNames.isEmpty()) {
            onResult(emptyList())
            return
        }

        viewModelScope.launch {
            try {
                val creators = withContext(Dispatchers.IO) {
                    artistNames.flatMap { artistName ->
                        val candidates = AppContainer.youtubeMusicClient.searchCreators(
                            query = artistName,
                            limit = YOUTUBE_MUSIC_CREATOR_SEARCH_LIMIT
                        )
                        findExactYouTubeMusicCreatorMatches(artistName, candidates)
                    }.distinctBy(YouTubeMusicCreatorSummary::browseId)
                }
                onResult(creators)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e("NowPlayingViewModel", "解析 YouTube 创作者失败", error)
                onError(error)
            }
        }
    }

    fun setPlaybackSpeed(speed: Float, persist: Boolean = true) {
        PlayerManager.setPlaybackSpeed(speed, persist)
    }

    fun setPlaybackPitch(pitch: Float, persist: Boolean = true) {
        PlayerManager.setPlaybackPitch(pitch, persist)
    }

    fun setPlaybackLoudnessGain(levelMb: Int, persist: Boolean = true) {
        PlayerManager.setPlaybackLoudnessGain(levelMb, persist)
    }

    fun setPlaybackEqualizerEnabled(enabled: Boolean, persist: Boolean = true) {
        PlayerManager.setPlaybackEqualizerEnabled(enabled, persist)
    }

    fun selectPlaybackEqualizerPreset(presetId: String, persist: Boolean = true) {
        PlayerManager.selectPlaybackEqualizerPreset(presetId, persist)
    }

    fun updatePlaybackEqualizerBandLevel(
        index: Int,
        levelMb: Int,
        persist: Boolean = true
    ) {
        PlayerManager.updatePlaybackEqualizerBandLevel(index, levelMb, persist)
    }

    fun resetPlaybackSoundSettings(persist: Boolean = true) {
        PlayerManager.resetPlaybackSoundSettings(persist)
    }

    fun fillLyrics(context: Context, song: SongItem, selectedSong: SongSearchInfo, onComplete: (Boolean, String) -> Unit) {
        if (
            selectedSong.source == MusicPlatform.CLOUD_MUSIC &&
            AppContainer.neteaseCookieRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing
        ) {
            onComplete(false, context.getString(R.string.netease_login_required_metadata))
            return
        }
        viewModelScope.launch {
            try {
                val platform = selectedSong.source
                val api = when (platform) {
                    MusicPlatform.CLOUD_MUSIC -> {
                        val client = AppContainer.neteaseClient
                        moe.ouom.neriplayer.core.api.search.CloudMusicSearchApi(client)
                    }
                    MusicPlatform.QQ_MUSIC -> moe.ouom.neriplayer.core.api.search.QQMusicSearchApi()
                }

                val songDetails = api.getSongInfo(selectedSong.id)

                if (!songDetails.lyric.isNullOrBlank()) {
                    // 一次性更新歌词和翻译歌词, 避免数据竞争
                    PlayerManager.updateSongLyricsAndTranslation(
                        song,
                        songDetails.lyric,
                        songDetails.translatedLyric
                    )
                    NPLogger.d("NowPlayingViewModel", "歌词已保存: songId=${song.id}, album=${song.album}, lyrics length=${songDetails.lyric.length}, hasTranslation=${!songDetails.translatedLyric.isNullOrBlank()}")
                    onComplete(true, context.getString(R.string.music_lyrics_filled_success))
                } else {
                    NPLogger.w("NowPlayingViewModel", "获取的歌词为空: searchSongId=${selectedSong.id}")
                    onComplete(false, context.getString(R.string.music_lyrics_empty))
                }
            } catch (e: Exception) {
                NPLogger.e("NowPlayingViewModel", "获取歌词失败", e)
                onComplete(false, context.getString(R.string.music_lyrics_fill_failed))
            }
        }
    }

    suspend fun updateSongInfo(
        originalSong: SongItem,
        newCoverUrl: String?,
        newName: String,
        newArtist: String,
        restoreBaseCover: Boolean = false,
        restoreBaseName: Boolean = false,
        restoreBaseArtist: Boolean = false,
        clearMatchedMetadata: Boolean = false,
        writeLocalMetadata: Boolean = false,
        writeLyrics: Boolean = false
    ): Boolean {
        return PlayerManager.updateSongCustomInfo(
            originalSong = originalSong,
            customCoverUrl = newCoverUrl,
            customName = newName,
            customArtist = newArtist,
            restoreBaseCover = restoreBaseCover,
            restoreBaseName = restoreBaseName,
            restoreBaseArtist = restoreBaseArtist,
            clearMatchedMetadata = clearMatchedMetadata,
            writeLocalMetadata = writeLocalMetadata,
            writeLyrics = writeLyrics
        )
    }

    data class OriginalSongInfo(
        val name: String,
        val artist: String,
        val coverUrl: String?,
        val shouldClearLyrics: Boolean = false,  // B站音源应该清除歌词
        val lyric: String? = null,  // 网易云音源的原始歌词
        val translatedLyric: String? = null,  // 网易云音源的原始翻译歌词
        val romanizedLyric: String? = null
    )

    fun fetchOriginalInfo(context: Context, originalSong: SongItem, onResult: (Boolean, OriginalSongInfo?, String) -> Unit) {
        val requestId = originalInfoRequestId.incrementAndGet()
        viewModelScope.launch {
            try {
                val isBili = originalSong.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

                if (!originalSong.mediaUri.isNullOrBlank()) {
                    val coverFallbackUrl = withContext(Dispatchers.IO) {
                        val nearbyCover = runCatching {
                            LocalMediaSupport.resolveNearbyCoverUri(context, originalSong)
                        }.getOrNull()
                        val storedCover = originalSong.originalCoverUrl
                            ?.takeIf { it.isNotBlank() && it != originalSong.customCoverUrl }
                            ?: originalSong.coverUrl
                                ?.takeIf { it.isNotBlank() && it != originalSong.customCoverUrl }
                        nearbyCover
                            ?: storedCover
                            ?: LocalMediaSupport.peekCachedEmbeddedCoverUri(context, originalSong)
                            ?: runCatching {
                                LocalMediaSupport.resolveCoverUri(context, originalSong)
                            }.getOrNull()
                    }
                    val info = buildLocalOriginalSongInfo(
                        song = originalSong,
                        coverFallbackUrl = coverFallbackUrl
                    )
                    if (originalInfoRequestId.get() == requestId) {
                        onResult(true, info, context.getString(R.string.music_restore_success))
                    }
                } else if (isBili) {
                    val resolved = resolveBiliSong(originalSong, AppContainer.biliClient)
                        ?: throw IllegalStateException("无法解析 B 站视频信息")

                    val coverUrl = resolved.videoInfo.coverUrl.let {
                        if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
                    }

                    val info = OriginalSongInfo(
                        name = resolved.pageInfo?.part ?: resolved.videoInfo.title,
                        artist = resolved.videoInfo.ownerName,
                        coverUrl = coverUrl,
                        shouldClearLyrics = true  // B站音源应该清除歌词
                    )
                    if (originalInfoRequestId.get() == requestId) {
                        onResult(true, info, context.getString(R.string.music_restore_success))
                    }
                } else {
                    // 网易云音乐: 从网易云获取原始信息
                    val appContainer = AppContainer
                    val songDetails = appContainer.cloudMusicSearchApi.getSongInfo(originalSong.id.toString())

                    val coverUrl = songDetails.coverUrl?.let {
                        if (it.startsWith("http://")) it.replaceFirst("http://", "https://") else it
                    }

                    val info = OriginalSongInfo(
                        name = songDetails.songName,
                        artist = songDetails.singer,
                        coverUrl = coverUrl,
                        shouldClearLyrics = false,  // 网易云音源不清除歌词
                        lyric = songDetails.lyric,  // 保存原始歌词
                        translatedLyric = songDetails.translatedLyric  // 保存原始翻译歌词
                    )
                    if (originalInfoRequestId.get() == requestId) {
                        onResult(true, info, context.getString(R.string.music_restore_success))
                    }
                }
            } catch (e: Exception) {
                NPLogger.e("NowPlayingViewModel", "获取原始信息失败", e)
                if (originalInfoRequestId.get() == requestId) {
                    onResult(false, null, context.getString(R.string.music_restore_failed))
                }
            }
        }
    }

}

private fun ManualSearchState.matches(request: ManualSearchRequest): Boolean {
    return keyword.trim() == request.keyword && selectedPlatform == request.platform
}

internal fun buildLocalOriginalSongInfo(
    song: SongItem,
    coverFallbackUrl: String? = null
): NowPlayingViewModel.OriginalSongInfo {
    val lyric = song.originalLyric ?: song.matchedLyric
    val translatedLyric = song.originalTranslatedLyric ?: song.matchedTranslatedLyric
    val romanizedLyric = song.originalRomanizedLyric ?: song.matchedRomanizedLyric
    val customCover = song.customCoverUrl?.trim()?.takeIf(String::isNotBlank)
    val originalCover = song.originalCoverUrl
        ?.takeIf { it.isNotBlank() && it != customCover }
    val baseCover = song.coverUrl
        ?.takeIf { it.isNotBlank() && it != customCover }
    val shouldClearLyrics = lyric.isNullOrBlank() &&
        translatedLyric.isNullOrBlank() &&
        romanizedLyric.isNullOrBlank()
    return NowPlayingViewModel.OriginalSongInfo(
        name = song.originalName ?: song.name,
        artist = song.originalArtist ?: song.artist,
        coverUrl = originalCover ?: baseCover ?: coverFallbackUrl,
        shouldClearLyrics = shouldClearLyrics,
        lyric = lyric,
        translatedLyric = translatedLyric,
        romanizedLyric = romanizedLyric
    )
}
