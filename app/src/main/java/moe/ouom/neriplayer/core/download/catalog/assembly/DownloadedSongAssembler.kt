package moe.ouom.neriplayer.core.download.catalog.assembly

import moe.ouom.neriplayer.core.download.model.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.catalog.fallbackDownloadedSongId
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.model.withRecoveredRemoteSourceStableKey
import moe.ouom.neriplayer.core.download.policy.shouldInspectDownloadedAudioDetails
import moe.ouom.neriplayer.data.local.media.LocalSongSupport

internal class DownloadedSongAssembler(
    metadata: DownloadedAudioMetadata?,
    private val file: DownloadedSongFileInfo,
    private val cover: DownloadedSongCoverInfo,
    private val lyrics: DownloadedSongLyricContent,
    private val loadLyricContents: Boolean,
    allowSlowLocalInspection: Boolean,
    private val defaultAlbum: () -> String,
    readLocalMetadata: () -> DownloadedSongLocalMetadata?
) {
    private val hasMetadata = metadata != null
    private val sourceMetadata = metadata
    private val metadata = metadata ?: DownloadedAudioMetadata()

    // 保留元数据缺失与字段为空的区别，慢速标签读取在整次组装中最多执行一次
    private val local by lazy(LazyThreadSafetyMode.NONE) {
        if (shouldInspectDownloadedAudioDetails(
                allowSlowLocalInspection,
                sourceMetadata,
                cover.reference,
                lyrics.needsLocalFallback(loadLyricContents, this.metadata)
            )
        ) {
            readLocalMetadata() ?: DownloadedSongLocalMetadata()
        } else {
            DownloadedSongLocalMetadata()
        }
    }

    fun assemble(): DownloadedSong {
        val coverReference = cover.reference ?: local.coverUri
        val resolvedLyrics = lyrics.resolve(loadLyricContents, metadata) { local.lyricContent }
        return DownloadedSong(
            id = metadata.songId ?: fallbackDownloadedSongId(file.reference),
            name = displayText(metadata.name, { local.title }, file.parsedTitle),
            artist = displayText(metadata.artist, { local.artist }, file.parsedArtist),
            album = resolveAlbum(),
            filePath = file.reference,
            fileSize = file.sizeBytes,
            downloadTime = file.downloadTime,
            coverPath = coverReference,
            coverUrl = cover.coverUrl,
            matchedLyric = resolvedLyrics.original,
            matchedTranslatedLyric = resolvedLyrics.translated,
            matchedRomanizedLyric = resolvedLyrics.romanized,
            matchedLyricSource = metadata.matchedLyricSource,
            matchedSongId = metadata.matchedSongId,
            userLyricOffsetMs = metadata.userLyricOffsetMs,
            customCoverUrl = cover.customCoverUrl,
            customName = metadata.customName,
            customArtist = metadata.customArtist,
            originalName = metadata.originalName ?: local.originalTitle,
            originalArtist = metadata.originalArtist ?: local.originalArtist,
            originalCoverUrl = cover.originalCoverUrl,
            originalLyric = metadata.originalLyric,
            originalTranslatedLyric = metadata.originalTranslatedLyric,
            originalRomanizedLyric = metadata.originalRomanizedLyric,
            mediaUri = file.playbackUri,
            durationMs = resolveDuration(),
            stableKey = metadata.stableKey ?: local.sourceStableKey,
            sourceIdentityAlbum = metadata.identityAlbum,
            sourceMediaUri = metadata.mediaUri,
            sourceChannelId = metadata.channelId,
            sourceAudioId = metadata.audioId,
            sourceSubAudioId = metadata.subAudioId,
            sourcePlaylistContextId = metadata.playlistContextId,
            localFileName = file.logicalName
        ).withRecoveredRemoteSourceStableKey()
    }

    private fun resolveAlbum(): String {
        val album = metadata.album
        if (!album.isNullOrBlank()) return album
        if (!hasMetadata && local.album.isNotBlank()) return local.album
        val identityAlbum = metadata.identityAlbum
        if (identityAlbum != null && identityAlbum != LocalSongSupport.LOCAL_ALBUM_IDENTITY) return identityAlbum
        return defaultAlbum()
    }

    private fun resolveDuration(): Long = metadata.durationMs.takeIf { it > 0L } ?: local.durationMs

    private fun displayText(value: String?, localValue: () -> String, fallback: String): String =
        value?.takeIf(String::isNotBlank) ?: localValue().takeIf(String::isNotBlank) ?: fallback
}
