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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.api.search.SearchManager
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.auth.common.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.artist.parseNeteaseArtistsFromSongDetail
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.R

data class ManualSearchState(
    val keyword: String = "",
    val selectedPlatform: MusicPlatform = MusicPlatform.QQ_MUSIC,
    val searchResults: List<SongSearchInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCloudMusicAvailable: Boolean = false
)

class NowPlayingViewModel : ViewModel() {

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
                        isCloudMusicAvailable = isCloudMusicAvailable
                    )
                }
            }
        }
    }

    fun prepareForSearch(initialKeyword: String) {
        _manualSearchState.update {
            it.copy(
                keyword = initialKeyword,
                searchResults = emptyList(), // 清空上次结果
                error = null
            )
        }
    }

    fun onKeywordChange(newKeyword: String) {
        _manualSearchState.update { it.copy(keyword = newKeyword) }
    }

    fun selectPlatform(platform: MusicPlatform) {
        _manualSearchState.update { it.copy(selectedPlatform = platform) }
        performSearch()
    }

    fun performSearch() {
        if (_manualSearchState.value.keyword.isBlank()) return
        if (
            _manualSearchState.value.selectedPlatform == MusicPlatform.CLOUD_MUSIC &&
            AppContainer.neteaseCookieRepo.getAuthHealthOnce().state == SavedCookieAuthState.Missing
        ) {
            _manualSearchState.update {
                it.copy(
                    isLoading = false,
                    searchResults = emptyList(),
                    error = AppContainer.applicationContext.getString(R.string.netease_login_required_search)
                )
            }
            return
        }

        viewModelScope.launch {
            _manualSearchState.update { it.copy(isLoading = true, error = null) }
            try {
                val results = SearchManager.search(
                    keyword = _manualSearchState.value.keyword,
                    platform = _manualSearchState.value.selectedPlatform,
                )
                _manualSearchState.update { it.copy(isLoading = false, searchResults = results) }

            } catch (e: Exception) {
                _manualSearchState.update { it.copy(isLoading = false, error = "Search failed: ${e.message}") }  // Localized in UI
            }
        }
    }

    fun onSongSelected(originalSong: SongItem, selectedSong: SongSearchInfo) {
        PlayerManager.replaceMetadataFromSearch(originalSong, selectedSong)
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
                    // 一次性更新歌词和翻译歌词，避免数据竞争
                    PlayerManager.updateSongLyricsAndTranslation(
                        song,
                        songDetails.lyric!!,
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

    fun updateSongInfo(
        originalSong: SongItem,
        newCoverUrl: String?,
        newName: String,
        newArtist: String,
        restoreBaseCover: Boolean = false,
        restoreBaseName: Boolean = false,
        restoreBaseArtist: Boolean = false,
        clearMatchedMetadata: Boolean = false
    ) {
        PlayerManager.updateSongCustomInfo(
            originalSong = originalSong,
            customCoverUrl = newCoverUrl,
            customName = newName,
            customArtist = newArtist,
            restoreBaseCover = restoreBaseCover,
            restoreBaseName = restoreBaseName,
            restoreBaseArtist = restoreBaseArtist,
            clearMatchedMetadata = clearMatchedMetadata
        )
    }

    data class OriginalSongInfo(
        val name: String,
        val artist: String,
        val coverUrl: String?,
        val shouldClearLyrics: Boolean = false,  // B站音源应该清除歌词
        val lyric: String? = null,  // 网易云音源的原始歌词
        val translatedLyric: String? = null  // 网易云音源的原始翻译歌词
    )

    fun fetchOriginalInfo(context: Context, originalSong: SongItem, onResult: (Boolean, OriginalSongInfo?, String) -> Unit) {
        viewModelScope.launch {
            try {
                val isBili = originalSong.album.startsWith(PlayerManager.BILI_SOURCE_TAG)

                if (!originalSong.mediaUri.isNullOrBlank()) {
                    val info = buildLocalOriginalSongInfo(originalSong)
                    onResult(true, info, context.getString(R.string.music_restore_success))
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
                    onResult(true, info, context.getString(R.string.music_restore_success))
                } else {
                    // 网易云音乐：从网易云获取原始信息
                    val appContainer = AppContainer
                    val songDetails = appContainer.cloudMusicSearchApi?.getSongInfo(originalSong.id.toString())

                    if (songDetails != null) {
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
                        onResult(true, info, context.getString(R.string.music_restore_success))
                    } else {
                        onResult(false, null, context.getString(R.string.music_restore_failed))
                    }
                }
            } catch (e: Exception) {
                NPLogger.e("NowPlayingViewModel", "获取原始信息失败", e)
                onResult(false, null, context.getString(R.string.music_restore_failed))
            }
        }
    }

}

internal fun buildLocalOriginalSongInfo(song: SongItem): NowPlayingViewModel.OriginalSongInfo {
    val lyric = song.originalLyric ?: song.matchedLyric
    val translatedLyric = song.originalTranslatedLyric ?: song.matchedTranslatedLyric
    val shouldClearLyrics = lyric.isNullOrBlank() && translatedLyric.isNullOrBlank()
    return NowPlayingViewModel.OriginalSongInfo(
        name = song.originalName ?: song.name,
        artist = song.originalArtist ?: song.artist,
        coverUrl = song.originalCoverUrl ?: song.coverUrl,
        shouldClearLyrics = shouldClearLyrics,
        lyric = lyric,
        translatedLyric = translatedLyric
    )
}
