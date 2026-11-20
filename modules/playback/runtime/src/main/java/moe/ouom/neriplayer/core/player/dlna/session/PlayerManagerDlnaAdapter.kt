@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.dlna.session

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.handleTrackEnded
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueNavigationOwner
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.url.OFFLINE_CACHE_URL_PREFIX
import moe.ouom.neriplayer.core.player.url.resolveSongUrl
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.queue.QueueTrackCompletion
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import java.io.File
import java.io.FileOutputStream

/**
 * Bridges [DlnaCastManager] with [PlayerManager] and [AudioPlayerService].
 */
internal object PlayerManagerDlnaAdapter : DlnaCastManager.PlayerSyncHost {

    override fun onSwitchToDlnaPauseLocal() {
        val manager = PlayerManager
        if (!manager.initialized) return
        manager.runPlayerActionOnMainThread {
            manager.pauseImpl(
                forcePersist = false,
                commandSource = PlaybackCommandSource.REMOTE_SYNC,
                allowFadeOut = false,
                debugReason = "dlna_switch",
                flushPlayerOutput = true
            )
            AudioPlayerService.startSyncService(
                context = manager.application,
                source = "dlna_switch",
                forceForeground = true
            )
        }
    }

    override fun onSwitchBackToLocal(resumePositionMs: Long, wasPlaying: Boolean) {
        val manager = PlayerManager
        if (!manager.initialized) return
        manager.runPlayerActionOnMainThread {
            val playlist = manager.currentPlaylist
            val idx = manager.currentIndex
            if (playlist.isNotEmpty() && idx in playlist.indices) {
                manager.playAtIndex(
                    index = idx,
                    resumePositionMs = resumePositionMs.coerceAtLeast(0L),
                    commandSource = PlaybackCommandSource.LOCAL,
                    startPaused = !wasPlaying,
                    allowRememberedLongFormPosition = false
                )
            }
        }
    }

    override fun onDlnaPlaybackStateChanged(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val manager = PlayerManager
        if (!manager.initialized || !DlnaCastManager.isCastingActive()) return
        manager._isPlayingFlow.value = isPlaying
        manager._playWhenReadyFlow.value = isPlaying
        manager.updateResumePlaybackRequested(isPlaying)
        manager._playbackPositionMs.value = positionMs.coerceAtLeast(0L)
        if (durationMs > 0L) {
            manager.playbackProgressOwner.setDuration(durationMs)
        }
        if (manager.lyriconEnabled) {
            LyriconManager.setPlaybackState(isPlaying)
            LyriconManager.updatePlaybackProgress(
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                speed = 1f
            )
        }
        manager.updateExternalBluetoothLyricLine(positionMs.coerceAtLeast(0L))
        DlnaCastManager.castStateFlow.value.qualityReport?.let { report ->
            val currentInfo = manager._currentPlaybackAudioInfo.value
            if (currentInfo != null &&
                (currentInfo.sampleRateHz != report.sampleRateHz ||
                    currentInfo.bitDepth != report.bitDepth ||
                    currentInfo.codecLabel != report.resolvedFormat)
            ) {
                manager._currentPlaybackAudioInfo.value = currentInfo.copy(
                    codecLabel = report.resolvedFormat.ifBlank { currentInfo.codecLabel },
                    mimeType = report.mimeType.ifBlank { currentInfo.mimeType },
                    sampleRateHz = report.sampleRateHz ?: currentInfo.sampleRateHz,
                    bitDepth = report.bitDepth ?: currentInfo.bitDepth,
                    channelCount = report.channelCount ?: currentInfo.channelCount
                )
            }
        }
    }

    override fun onDlnaTrackEnded(generationId: Long) {
        val manager = PlayerManager
        if (!manager.initialized || !DlnaCastManager.isCastingActive()) return
        if (DlnaCastManager.castStateFlow.value.currentGenerationId != generationId) return
        manager.playbackStatsOwner.onTrackEnded(writesEnabled = manager.initialized)
        manager.handleTrackEnded()
    }

    override fun peekNextAutoTrack(): SongItem? {
        val manager = PlayerManager
        if (!manager.initialized || manager.currentPlaylist.isEmpty()) return null
        val completion = PlayerQueueNavigationOwner.trackCompletion(
            queueSize = manager.currentPlaylist.size,
            currentIndex = manager.currentIndex,
            repeatMode = manager.repeatModeSetting
        )
        return when (completion) {
            QueueTrackCompletion.STOP -> null
            QueueTrackCompletion.REPLAY_CURRENT ->
                manager.currentPlaylist.getOrNull(manager.currentIndex)
            QueueTrackCompletion.ADVANCE,
            QueueTrackCompletion.WRAP -> {
                val step = PlayerQueueNavigationOwner.nextStep(
                    queueSize = manager.currentPlaylist.size,
                    currentIndex = manager.currentIndex,
                    repeatMode = manager.repeatModeSetting,
                    force = (completion == QueueTrackCompletion.WRAP)
                ) ?: return null
                if (step.reshuffleAtWrap) null else manager.currentPlaylist.getOrNull(step.index)
            }
        }
    }

    override fun onDlnaGaplessTransitionedToNext(expectedSong: SongItem) {
        val manager = PlayerManager
        if (!manager.initialized || !DlnaCastManager.isCastingActive()) return
        manager.playbackStatsOwner.onTrackEnded(writesEnabled = manager.initialized)
        val completion = PlayerQueueNavigationOwner.trackCompletion(
            queueSize = manager.currentPlaylist.size,
            currentIndex = manager.currentIndex,
            repeatMode = manager.repeatModeSetting
        )
        when (completion) {
            QueueTrackCompletion.REPLAY_CURRENT -> {
                manager.markAutoTrackAdvance()
                manager.clearPendingSeekPosition()
                manager._playbackPositionMs.value = 0L
                manager.updateResumePlaybackRequested(true)
                manager.scheduleStatePersist(positionMs = 0L, shouldResumePlayback = true)
            }
            QueueTrackCompletion.ADVANCE,
            QueueTrackCompletion.WRAP -> {
                val step = PlayerQueueNavigationOwner.nextStep(
                    queueSize = manager.currentPlaylist.size,
                    currentIndex = manager.currentIndex,
                    repeatMode = manager.repeatModeSetting,
                    force = (completion == QueueTrackCompletion.WRAP)
                )
                val targetSong = step?.let { manager.currentPlaylist.getOrNull(it.index) }
                if (step != null && !step.reshuffleAtWrap && targetSong?.sameIdentityAs(expectedSong) == true) {
                    manager.markAutoTrackAdvance()
                    manager.clearPendingSeekPosition()
                    manager.currentIndex = step.index
                    manager.setCurrentSongForPlayback(targetSong, syncLyricon = true)
                    manager._playbackPositionMs.value = 0L
                    manager.updateResumePlaybackRequested(true)
                    manager.scheduleStatePersist(positionMs = 0L, shouldResumePlayback = true)
                    manager.bumpCurrentQueueDisplayRevision()
                } else {
                    manager.handleTrackEnded()
                }
            }
            QueueTrackCompletion.STOP -> {
                DlnaCastManager.onQueueFinishedStop()
            }
        }
    }

    internal fun onQueueStopRequested() {
        if (DlnaCastManager.isCastingActive()) {
            DlnaCastManager.onQueueFinishedStop()
        }
    }

    override fun onDlnaCastError(message: String) {
        val manager = PlayerManager
        if (!manager.initialized) return
        manager.postPlayerEvent(PlayerEvent.ShowError(message))
    }

    override suspend fun resolveSongUrlForDlna(song: SongItem): SongUrlResult {
        val manager = PlayerManager
        val initial = manager.resolveSongUrl(
            song = song,
            forceRefresh = false,
            allowGenericPrefetchCache = false,
            preferYouTubeM4a = true
        )
        if (initial is SongUrlResult.Success) {
            initial.audioInfo?.let { resolvedInfo ->
                manager.runPlayerActionOnMainThread {
                    if (manager._currentSongFlow.value?.id == song.id &&
                        manager._currentSongFlow.value?.album == song.album
                    ) {
                        manager._currentPlaybackAudioInfo.value = resolvedInfo
                    }
                }
            }
            if (initial.url.startsWith(OFFLINE_CACHE_URL_PREFIX)) {
                val cacheKey = initial.url.removePrefix(OFFLINE_CACHE_URL_PREFIX)
                val extractedFile = extractFromSimpleCache(cacheKey)
                if (extractedFile != null) {
                    return initial.copy(url = extractedFile.absolutePath)
                }
                return manager.resolveSongUrl(
                    song = song,
                    forceRefresh = true,
                    allowGenericPrefetchCache = false,
                    preferYouTubeM4a = true
                )
            }
        }
        return initial
    }

    override fun currentSong(): SongItem? = PlayerManager.currentSongFlow.value

    override fun currentPositionMs(): Long = PlayerManager.playbackPositionFlow.value

    override fun isLocalPlaying(): Boolean =
        PlayerManager.isPlayingFlow.value && !DlnaCastManager.isCastingActive()

    internal fun tryInterceptPlayAtIndex(
        song: SongItem,
        resumePositionMs: Long,
        startPaused: Boolean,
        requestToken: Long
    ): Boolean {
        if (!DlnaCastManager.isCastingActive()) return false
        val manager = PlayerManager
        runCatching {
            manager.player.playWhenReady = false
            manager.player.stop()
            manager.player.clearMediaItems()
        }
        manager.pendingMediaLoadActive = false
        manager._playWhenReadyFlow.value = !startPaused
        manager._isPlayingFlow.value = !startPaused
        manager._playbackPositionMs.value = resumePositionMs
        DlnaCastManager.castSong(
            song = song,
            startPositionMs = resumePositionMs,
            autoPlay = !startPaused
        )
        moe.ouom.neriplayer.core.player.audio.wake.PlaybackTransitionWakeLock.release(
            requestToken,
            "dlna_cast_handoff"
        )
        return true
    }

    internal fun tryInterceptPlay(commandSource: PlaybackCommandSource): Boolean {
        if (!DlnaCastManager.isCastingActive()) return false
        val manager = PlayerManager
        manager._playWhenReadyFlow.value = true
        DlnaCastManager.playRemote()
        manager.emitPlaybackCommand(
            type = "PLAY",
            source = commandSource,
            positionMs = manager._playbackPositionMs.value,
            currentIndex = manager.currentIndex
        )
        return true
    }

    internal fun tryInterceptPause(
        commandSource: PlaybackCommandSource,
        debugReason: String
    ): Boolean {
        if (debugReason.startsWith("dlna_switch") || !DlnaCastManager.isCastingActive()) return false
        val manager = PlayerManager
        manager.updateResumePlaybackRequested(false)
        manager._playWhenReadyFlow.value = false
        manager._isPlayingFlow.value = false
        DlnaCastManager.pauseRemote()
        manager.emitPlaybackCommand(
            type = "PAUSE",
            source = commandSource,
            positionMs = manager._playbackPositionMs.value,
            currentIndex = manager.currentIndex
        )
        return true
    }

    internal fun tryInterceptTogglePlayPause(): Boolean {
        if (!DlnaCastManager.isCastingActive()) return false
        if (DlnaCastManager.isRemotePlaying()) {
            PlayerManager.pauseImpl(allowFadeOut = false, debugReason = "dlna_toggle_pause")
        } else {
            PlayerManager.playImpl(allowFadeIn = false)
        }
        return true
    }

    internal fun tryInterceptSeek(
        positionMs: Long,
        commandSource: PlaybackCommandSource,
        syncSeekOutputs: (Long) -> Unit
    ): Boolean {
        if (!DlnaCastManager.isCastingActive()) return false
        syncSeekOutputs(positionMs)
        DlnaCastManager.seekRemote(positionMs)
        PlayerManager.emitPlaybackCommand(
            type = "SEEK",
            source = commandSource,
            positionMs = positionMs,
            currentIndex = PlayerManager.currentIndex
        )
        return true
    }

    private suspend fun extractFromSimpleCache(cacheKey: String): File? = withContext(Dispatchers.IO) {
        val mediaCache = PlayerManager.cache ?: return@withContext null
        val contentLength = ContentMetadata.getContentLength(mediaCache.getContentMetadata(cacheKey))
        if (contentLength <= 0L) return@withContext null
        val stagingDir = File(PlayerManager.application.cacheDir, "dlna_staging").apply { mkdirs() }
        val safeName = cacheKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val target = File(stagingDir, "exo_$safeName.audio")
        if (target.isFile && target.length() == contentLength) {
            return@withContext target
        }
        val tmp = File(stagingDir, "exo_$safeName.tmp")
        return@withContext runCatching {
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(mediaCache)
                .setUpstreamDataSourceFactory(null)
                .createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(android.net.Uri.EMPTY)
                .setKey(cacheKey)
                .setPosition(0L)
                .setLength(contentLength)
                .build()
            cacheDataSource.open(dataSpec)
            try {
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = cacheDataSource.read(buf, 0, buf.size)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            } finally {
                runCatching { cacheDataSource.close() }
            }
            if (tmp.length() != contentLength) {
                tmp.delete()
                return@withContext null
            }
            if (target.exists()) target.delete()
            if (tmp.renameTo(target)) target else null
        }.getOrNull()
    }
}
