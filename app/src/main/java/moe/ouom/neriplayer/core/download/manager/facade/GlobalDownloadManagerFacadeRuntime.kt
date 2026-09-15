package moe.ouom.neriplayer.core.download

import android.content.Context
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionHosts
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.remoteDownloadIdentityOrNull
import moe.ouom.neriplayer.data.model.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.model.stableKey

internal fun GlobalDownloadManager.isDownloadAttemptActiveImpl(
    songKey: String,
    expectedAttemptId: Long? = null
): Boolean {
    return taskStore.isDownloadAttemptActive(
        songKey = songKey,
        expectedAttemptId = expectedAttemptId
    )
}

internal fun GlobalDownloadManager.resumeDownloadTaskImpl(context: Context, songKey: String) {
    val task = taskStore.findTask(songKey) ?: return
    if (
        task.status != DownloadStatus.CANCELLED &&
        task.status != DownloadStatus.FAILED &&
        task.status != DownloadStatus.WAITING_NETWORK
    ) {
        return
    }

    val appContext = context.applicationContext
    val requestedAdmissionTicket = downloadAdmissionGate.openTicketOrNull()
    scope.launch {
        if (task.status == DownloadStatus.CANCELLED) {
            DownloadExecutionHosts.default.cancelForSong(
                context = appContext,
                songKey = songKey
            )
            if (
                !awaitSongCancellationSettled(
                    songKey = songKey,
                    clearCancellationWhenSettled = false,
                    logProgress = false
                )
            ) {
                NPLogger.w(TAG, "取消中的下载仍未收敛，暂不恢复: song=${task.song.name}")
                return@launch
            }
            DownloadExecutionRoomStore.purgeCancelled(appContext, setOf(songKey))
        }
        clearSongCancelled(songKey)
        scheduleUserDownload(
            context = appContext,
            song = task.song,
            skipTrafficRiskPrompt = false,
            preserveStaging = task.status == DownloadStatus.WAITING_NETWORK,
            replacingAttemptId = task.attemptId,
            requestedAdmissionTicket = requestedAdmissionTicket
        )
    }
}

internal fun GlobalDownloadManager.buildOptimisticDownloadedSongImpl(
    song: SongItem,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    sidecarReferences: AudioDownloadManager.DownloadedSidecarReferences? = null
): DownloadedSong {
    val remoteSource = song.remoteDownloadIdentityOrNull()
        ?: song.remoteSourceIdentityOrNull()
        ?: song.takeUnless { LocalSongSupport.isLocalSong(it, null) }?.identity()
    val rawSourceChannel = song.channelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("local", ignoreCase = true) }
    val sourceChannel = rawSourceChannel ?: remoteSource?.album
    val sourceAudioId = song.audioId
        ?.trim()
        ?.takeIf { rawSourceChannel != null && it.isNotBlank() }
        ?: remoteSource
            ?.takeIf { sourceChannel.equals("netease", ignoreCase = true) }
            ?.id
            ?.toString()
    val sourceSubAudioId = song.subAudioId
        ?.trim()
        ?.takeIf { rawSourceChannel != null && it.isNotBlank() }
    val previousSong = downloadedSongsMutable.value.firstOrNull { downloadedSong ->
        downloadedSong.filePath == storedAudio.reference || matchesDownloadedSong(song, downloadedSong)
    }
    val resolvedDownloadTime = previousSong?.downloadTime
        ?: storedAudio.lastModifiedMs.takeIf { it > 0L }
        ?: System.currentTimeMillis()

    return DownloadedSong(
        id = song.id,
        name = song.name,
        artist = song.artist,
        album = song.album,
        filePath = storedAudio.reference,
        fileSize = storedAudio.sizeBytes.coerceAtLeast(0L),
        downloadTime = resolvedDownloadTime,
        coverPath = sidecarReferences?.coverReference ?: previousSong?.coverPath,
        coverUrl = song.coverUrl,
        matchedLyric = song.matchedLyric,
        matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric,
        matchedLyricSource = song.matchedLyricSource?.name,
        matchedSongId = song.matchedSongId,
        userLyricOffsetMs = song.userLyricOffsetMs,
        customCoverUrl = song.customCoverUrl,
        customName = song.customName,
        customArtist = song.customArtist,
        originalName = song.originalName,
        originalArtist = song.originalArtist,
        originalCoverUrl = song.originalCoverUrl,
        originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric,
        originalRomanizedLyric = song.originalRomanizedLyric,
        mediaUri = storedAudio.mediaUri,
        durationMs = song.durationMs.coerceAtLeast(0L),
        stableKey = remoteSource?.stableKey() ?: song.stableKey(),
        sourceIdentityAlbum = remoteSource?.album,
        sourceMediaUri = remoteSource?.mediaUri,
        sourceChannelId = sourceChannel,
        sourceAudioId = sourceAudioId,
        sourceSubAudioId = sourceSubAudioId,
        sourcePlaylistContextId = song.playlistContextId?.takeIf { remoteSource != null }
    )
}

internal fun GlobalDownloadManager.resolveSongLocationImpl(song: SongItem): String? {
    song.localFilePath
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val mediaUri = song.mediaUri?.takeIf { it.isNotBlank() } ?: return null
    return when {
        mediaUri.startsWith("/") -> mediaUri
        mediaUri.startsWith("file://") -> mediaUri
        mediaUri.startsWith("content://") -> mediaUri
        else -> null
    }
}

internal fun GlobalDownloadManager.matchesExpectedDownloadFileNameImpl(
    song: SongItem,
    audio: ManagedDownloadStorage.StoredEntry
): Boolean {
    val baseNames = ManagedDownloadStorage.buildCandidateBaseNames(song)
    val audioBaseName = audio.nameWithoutExtension
    val normalizedAudioBaseName = audioBaseName.replace(Regex(" \\(\\d+\\)$"), "")
    return baseNames.any { candidate ->
        candidate == audioBaseName || candidate == normalizedAudioBaseName
    }
}
