@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.dlna.session

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.lan.LanPlaybackBinding
import moe.ouom.neriplayer.core.player.dlna.protocol.DlnaProtocolInfo
import moe.ouom.neriplayer.core.player.lyrics.updateExternalBluetoothLyricLine
import moe.ouom.neriplayer.core.player.persistence.scheduleStatePersist
import moe.ouom.neriplayer.core.player.playback.handleTrackEnded
import moe.ouom.neriplayer.core.player.playback.pauseImpl
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.playback.playImpl
import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueNavigationOwner
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshSideEffectGate
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.url.OFFLINE_CACHE_URL_PREFIX
import moe.ouom.neriplayer.core.player.url.readCachedPlaybackDescriptor
import moe.ouom.neriplayer.core.player.url.resolveSongUrl
import moe.ouom.neriplayer.core.player.url.tryReadVerifiedOfflineCacheResult
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.playback.PlayerEvent
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.data.model.playback.queue.QueueTrackCompletion
import moe.ouom.neriplayer.lyrics.lyricon.LyriconManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Bridges [DlnaCastManager] with [PlayerManager] and [AudioPlayerService].
 */
internal object PlayerManagerDlnaAdapter : PlayerSyncHost {

    override fun onSwitchToDlnaPauseLocal(willAutoPlay: Boolean) {
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
            if (willAutoPlay && DlnaCastManager.isCastingActive()) {
                manager.pendingMediaLoadActive = false
                manager.updateResumePlaybackRequested(true)
                manager._playWhenReadyFlow.value = true
                manager._isPlayingFlow.value = true
            }
            AudioPlayerService.startSyncService(
                context = manager.application,
                source = "dlna_switch",
                forceForeground = true
            )
        }
    }

    /**
     * 投送中本地 ExoPlayer 已停，音质刷新若走本地路径只会重载本地播放器，渲染器仍在播旧码流。
     * 这里改为按新音质重新投送当前曲目，保留远端进度和播放意图。
     */
    fun tryRefreshActiveCast(song: SongItem): Boolean {
        if (!DlnaCastManager.isCastingActive()) return false
        val positionMs = DlnaCastManager.castStateFlow.value.remotePositionMs.coerceAtLeast(0L)
        DlnaCastManager.castSong(song = song, startPositionMs = positionMs, autoPlay = DlnaCastManager.isRemotePlaying())
        return true
    }

    override fun castBlockedReason(): String? =
        if (LanPlaybackBinding.externalAudio) {
            PlayerManager.getLocalizedString(CoreCommonR.string.dlna_err_listen_together_active)
        } else {
            null
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
        manager.syncDlnaRemotePlaybackStats(isPlaying, positionMs)
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
        val isLastInPlaylist = manager.currentIndex >= manager.currentPlaylist.lastIndex
        if (manager.sleepTimerManager.shouldStopOnTrackEnd(isLastInPlaylist)) {
            return null
        }
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

    override fun onDlnaGaplessTransitionedToNext(expectedSong: SongItem, audioInfo: PlaybackAudioInfo?) {
        val manager = PlayerManager
        if (!manager.initialized || !DlnaCastManager.isCastingActive()) return
        manager.playbackStatsOwner.onTrackEnded(writesEnabled = manager.initialized)
        val isLastInPlaylist = manager.currentIndex >= manager.currentPlaylist.lastIndex
        if (manager.sleepTimerManager.shouldStopOnTrackEnd(isLastInPlaylist)) {
            manager.pause()
            manager.sleepTimerManager.cancel()
            DlnaCastManager.onQueueFinishedStop()
            return
        }
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
                manager._playWhenReadyFlow.value = true
                manager._isPlayingFlow.value = true
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
                    audioInfo?.let { manager._currentPlaybackAudioInfo.value = it }
                    manager._playbackPositionMs.value = 0L
                    manager.updateResumePlaybackRequested(true)
                    manager._playWhenReadyFlow.value = true
                    manager._isPlayingFlow.value = true
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

    override suspend fun resolveSongUrlForDlna(
        song: SongItem,
        forPrequeue: Boolean
    ): DlnaUrlResolution {
        val manager = PlayerManager
        val sinkInfo = DlnaCastManager.castStateFlow.value.activeDevice?.sinkProtocolInfo.orEmpty()
        val silentSideEffects = RefreshResolverSideEffects(RefreshSideEffectGate { false })

        // Check if YouTube normal-playback cache already holds a complete renderer-playable file
        var incompatibleCachedYouTubeMime: String? = null
        if (!manager.isLocalSong(song) && manager.isYouTubeMusicTrack(song)) {
            val m4aKey = manager.computeCacheKey(song, youtubePreferM4aOverride = true)
            val m4aCached = manager.tryReadVerifiedOfflineCacheResult(m4aKey, song)
            if (m4aCached == null) {
                val normalKey = manager.computeCacheKey(song, youtubePreferM4aOverride = false)
                val normalCached = if (normalKey != m4aKey) {
                    manager.tryReadVerifiedOfflineCacheResult(normalKey, song)
                } else null
                if (normalCached != null) {
                    if (DlnaProtocolInfo.supportsDeliveryMime(sinkInfo, normalCached.mimeType)) {
                        val extracted = extractFromSimpleCache(normalKey)
                        if (extracted != null) {
                            publishResolvedAudioInfoIfCurrent(manager, song, forPrequeue, normalCached)
                            return DlnaUrlResolution.Success(
                                normalCached.copy(url = extracted.absolutePath)
                            )
                        }
                    } else {
                        incompatibleCachedYouTubeMime = normalCached.mimeType ?: "cached"
                    }
                }
            }
        }

        // Suppress generic URL error if we want to report a more specific offline cache-format mismatch
        val resolverSideEffects = if (forPrequeue || incompatibleCachedYouTubeMime != null) {
            silentSideEffects
        } else {
            RefreshResolverSideEffects()
        }

        val initial = manager.resolveSongUrl(
            song = song,
            forceRefresh = false,
            sideEffects = resolverSideEffects,
            allowGenericPrefetchCache = false,
            preferYouTubeM4a = true,
            requireYouTubeDirect = true
        )
        return materializeDlnaUrlResult(
            manager = manager,
            song = song,
            forPrequeue = forPrequeue,
            sinkInfo = sinkInfo,
            initial = initial,
            incompatibleCachedMimeHint = incompatibleCachedYouTubeMime
        )
    }

    private suspend fun materializeDlnaUrlResult(
        manager: PlayerManager,
        song: SongItem,
        forPrequeue: Boolean,
        sinkInfo: String,
        initial: SongUrlResult,
        incompatibleCachedMimeHint: String?
    ): DlnaUrlResolution {
        when (initial) {
            is SongUrlResult.Success -> {
                if (initial.url.startsWith(OFFLINE_CACHE_URL_PREFIX)) {
                    val cacheKey = initial.url.removePrefix(OFFLINE_CACHE_URL_PREFIX)
                    val cachedMime = initial.mimeType ?: initial.audioInfo?.mimeType
                    val isLosslessEligibleForWav = cachedMime?.let {
                        val lower = it.lowercase()
                        lower.contains("flac") || lower.contains("alac") || lower.contains("wav")
                    } == true
                    val rendererCompatible = cachedMime.isNullOrBlank() ||
                        isLosslessEligibleForWav ||
                        DlnaProtocolInfo.supportsDeliveryMime(sinkInfo, cachedMime)

                    if (rendererCompatible) {
                        val extractedFile = extractFromSimpleCache(cacheKey)
                        if (extractedFile != null) {
                            publishResolvedAudioInfoIfCurrent(manager, song, forPrequeue, initial)
                            return DlnaUrlResolution.Success(
                                initial.copy(url = extractedFile.absolutePath)
                            )
                        }
                    }

                    val refreshed = manager.resolveSongUrl(
                        song = song,
                        forceRefresh = true,
                        sideEffects = RefreshResolverSideEffects(RefreshSideEffectGate { false }),
                        allowGenericPrefetchCache = false,
                        preferYouTubeM4a = true,
                        requireYouTubeDirect = true
                    )
                    if (refreshed is SongUrlResult.Success && !refreshed.url.startsWith(OFFLINE_CACHE_URL_PREFIX)) {
                        publishResolvedAudioInfoIfCurrent(manager, song, forPrequeue, refreshed)
                        return DlnaUrlResolution.Success(refreshed)
                    }
                    if (!rendererCompatible) {
                        val msg = manager.getLocalizedString(
                            CoreCommonR.string.dlna_err_cached_format_unsupported,
                            cachedMime.orEmpty()
                        )
                        return DlnaUrlResolution.Failure(msg)
                    }
                    return DlnaUrlResolution.Failure(null)
                }

                publishResolvedAudioInfoIfCurrent(manager, song, forPrequeue, initial)
                return DlnaUrlResolution.Success(initial)
            }
            SongUrlResult.RequiresLogin -> return DlnaUrlResolution.RequiresLogin
            else -> {
                val customMsg = incompatibleCachedMimeHint?.let { mime ->
                    manager.getLocalizedString(
                        CoreCommonR.string.dlna_err_cached_format_unsupported,
                        mime
                    )
                }
                return DlnaUrlResolution.Failure(customMsg)
            }
        }
    }

    private fun publishResolvedAudioInfoIfCurrent(
        manager: PlayerManager,
        song: SongItem,
        forPrequeue: Boolean,
        result: SongUrlResult.Success
    ) {
        if (forPrequeue) return
        val resolvedInfo = result.audioInfo ?: return
        manager.runPlayerActionOnMainThread {
            if (manager._currentSongFlow.value?.sameIdentityAs(song) == true) {
                manager._currentPlaybackAudioInfo.value = resolvedInfo
            }
        }
    }

    override fun currentSong(): SongItem? = PlayerManager.currentSongFlow.value

    override fun currentPositionMs(): Long = PlayerManager.playbackPositionFlow.value

    override fun isLocalPlaying(): Boolean =
        !DlnaCastManager.isCastingActive() && (
            PlayerManager.isPlayingFlow.value ||
                PlayerManager.playbackControlPlayingFlow.value ||
                PlayerManager.playWhenReadyFlow.value ||
                PlayerManager.resumePlaybackRequested
            )

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
        manager.updateResumePlaybackRequested(!startPaused)
        manager._playWhenReadyFlow.value = !startPaused
        manager._isPlayingFlow.value = !startPaused
        manager._playbackPositionMs.value = resumePositionMs
        DlnaCastManager.renewCastLocks(forceRenew = true)
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
        manager.updateResumePlaybackRequested(true)
        manager._playWhenReadyFlow.value = true
        manager._isPlayingFlow.value = true
        DlnaCastManager.renewCastLocks(forceRenew = true)
        DlnaCastManager.playRemote()
        manager.scheduleStatePersist(
            positionMs = manager._playbackPositionMs.value,
            shouldResumePlayback = true
        )
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
        val posMs = manager._playbackPositionMs.value.coerceAtLeast(0L)
        manager.updateResumePlaybackRequested(false)
        manager._playWhenReadyFlow.value = false
        manager._isPlayingFlow.value = false
        DlnaCastManager.pauseRemote()
        val currentSong = manager._currentSongFlow.value
        if (currentSong != null) {
            val durationMs = maxOf(
                currentSong.durationMs.coerceAtLeast(0L),
                manager.playbackProgressOwner.duration.value
            )
            manager.persistLongFormPlaybackProgress(currentSong, posMs, durationMs)
            manager.scheduleStatePersist(positionMs = posMs, shouldResumePlayback = false)
        }
        manager.emitPlaybackCommand(
            type = "PAUSE",
            source = commandSource,
            positionMs = posMs,
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

    internal fun cacheKeyToExportFileName(cacheKey: String, versionToken: String? = null): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray(Charsets.UTF_8))
        val hex = buildString(digest.size * 2) {
            for (b in digest) {
                append(String.format(java.util.Locale.US, "%02x", b.toInt() and 0xFF))
            }
        }
        return if (versionToken.isNullOrBlank()) {
            "exo_$hex.audio"
        } else {
            "exo_${hex}_$versionToken.audio"
        }
    }

    internal fun computeVerifiedCacheVersionToken(
        mediaCache: androidx.media3.datasource.cache.Cache,
        cacheKey: String,
        contentLength: Long
    ): String? {
        if (contentLength <= 0L) return null
        val descriptor = mediaCache.readCachedPlaybackDescriptor(cacheKey) ?: return null
        val spans = mediaCache.getCachedSpans(cacheKey).sortedBy { it.position }
        if (spans.isEmpty()) return null
        var covered = 0L
        val sb = StringBuilder(128)
        sb.append(descriptor.representationFingerprint).append('|').append(contentLength)
        for (span in spans) {
            if (!span.isCached || span.length <= 0L) return null
            val backingFile = span.file ?: return null
            covered += span.length
            sb.append('|')
                .append(span.position).append(':')
                .append(span.length).append(':')
                .append(backingFile.name).append(':')
                .append(backingFile.length())
        }
        if (covered < contentLength) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return buildString(16) {
            for (i in 0 until 8) {
                append(String.format(java.util.Locale.US, "%02x", digest[i].toInt() and 0xFF))
            }
        }
    }

    private suspend fun extractFromSimpleCache(cacheKey: String): File? = withContext(Dispatchers.IO) {
        val mediaCache = PlayerManager.cache ?: return@withContext null
        val contentLength = ContentMetadata.getContentLength(mediaCache.getContentMetadata(cacheKey))
        if (contentLength <= 0L) return@withContext null
        val stagingDir = File(PlayerManager.application.cacheDir, "dlna_staging").apply { mkdirs() }
        val keyPrefix = cacheKeyToExportFileName(cacheKey).removeSuffix(".audio")
        val versionToken = computeVerifiedCacheVersionToken(mediaCache, cacheKey, contentLength)
        val fileName = if (versionToken != null) {
            cacheKeyToExportFileName(cacheKey, versionToken)
        } else {
            "${keyPrefix}_fresh_${java.util.UUID.randomUUID().toString().take(8)}.audio"
        }
        val target = File(stagingDir, fileName)
        if (versionToken != null && target.isFile && target.length() == contentLength) {
            cleanupStaleCacheExports(stagingDir, keyPrefix, keepName = target.name)
            return@withContext target
        }
        val tmp = File(
            stagingDir,
            "${keyPrefix}_${java.util.UUID.randomUUID().toString().take(8)}.tmp"
        )
        var committed = false
        return@withContext try {
            runCatching {
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
                    return@runCatching null
                }
                if (target.exists()) target.delete()
                if (tmp.renameTo(target)) {
                    committed = true
                    cleanupStaleCacheExports(stagingDir, keyPrefix, keepName = target.name)
                    target
                } else if (versionToken != null && target.isFile && target.length() == contentLength) {
                    target
                } else {
                    null
                }
            }.getOrNull()
        } finally {
            if (!committed && tmp.exists()) {
                tmp.delete()
            }
        }
    }

    private fun cleanupStaleCacheExports(stagingDir: File, keyPrefix: String, keepName: String) {
        val siblings = stagingDir.listFiles() ?: return
        for (f in siblings) {
            val name = f.name
            if (!f.isFile || name == keepName || name.endsWith(".tmp")) continue
            if (name == "$keyPrefix.audio" || (name.startsWith("${keyPrefix}_") && name.endsWith(".audio"))) {
                runCatching { f.delete() }
            }
        }
    }
}

// 投送期间本地 ExoPlayer 回调被屏蔽，统计只能由远端状态驱动，否则整首经 DLNA 播放的歌不计时长和次数
internal fun PlayerManager.syncDlnaRemotePlaybackStats(isPlaying: Boolean, positionMs: Long) {
    syncPlaybackStatsPlayingState(isPlaying, reason = "dlna_remote")
    if (isPlaying) playbackStatsOwner.onProgress(positionMs.coerceAtLeast(0L), writesEnabled = initialized)
}
