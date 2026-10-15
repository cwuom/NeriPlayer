package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.metadata.normalizeCustomMetadataValue
import moe.ouom.neriplayer.core.player.metadata.withUpdatedLyricsPreservingOriginal
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform

/** 一次写入歌曲文件的歌词与封面；字段为 null 表示该项保持不变 */
data class LocalSongAssetEmbedding(
    val lyrics: String? = null,
    val translatedLyrics: String? = null,
    val romanizedLyrics: String? = null,
    val userEditedLyrics: Boolean = true,
    val coverReference: String? = null,
    /**
     * 新歌词所属的平台曲目；这两个字段同时承担本地文件到网易云曲目的播放回退，
     * 只有能用 [MusicPlatform] 表示的来源才替换，为 null 时保持原值
     */
    val matchedLyricSource: MusicPlatform? = null,
    val matchedSongId: String? = null
) {
    val writesLyrics: Boolean get() = lyrics != null
    val writesCover: Boolean get() = coverReference != null
}

/**
 * 批量嵌入词图。耗时的文件复制与标签写入不占用全局元数据锁，不同文件可以并行，
 * 同一文件仍由本地媒体层按目标排队；锁内只基于最新副本合并歌词与封面字段后发布，
 * 不会覆盖期间对同一首歌的其他修改。
 */
internal suspend fun PlayerManager.embedLocalSongAssetsImpl(
    song: SongItem,
    embedding: LocalSongAssetEmbedding
): LocalMediaMetadataWriteOutcome {
    if (!embedding.writesLyrics && !embedding.writesCover) return LocalMediaMetadataWriteOutcome.SUCCESS
    val base = latestSongCopy(song)
    if (!LocalSongSupport.isLocalSong(base, application)) return LocalMediaMetadataWriteOutcome.NOT_WRITABLE
    // 原封面必须在改写文件前备份，写入后再取就只能拿到新封面
    val originalCoverBackup = if (embedding.writesCover) backupOriginalCoverBeforeEmbedding(base) else null
    val target = base.copy(
        matchedLyric = if (embedding.writesLyrics) embedding.lyrics else base.matchedLyric,
        matchedTranslatedLyric = if (embedding.writesLyrics) embedding.translatedLyrics else base.matchedTranslatedLyric,
        matchedRomanizedLyric = if (embedding.writesLyrics) embedding.romanizedLyrics else base.matchedRomanizedLyric,
        customCoverUrl = embedding.coverReference ?: base.customCoverUrl
    )
    val outcome = writeLocalEditableMetadata(
        song = target,
        coverReference = embedding.coverReference,
        writeCover = embedding.writesCover,
        writeLyrics = embedding.writesLyrics
    )
    if (outcome != LocalMediaMetadataWriteOutcome.SUCCESS) return outcome
    return runSongMetadataMutation {
        val latest = latestSongCopy(song)
        val updated = withEmbeddedAssets(latest, embedding, originalCoverBackup)
        val repairOutcome = if (latest != base) {
            rewriteTagsEditedDuringEmbedding(updated)
        } else {
            LocalMediaMetadataWriteOutcome.SUCCESS
        }
        // 词图已经写进文件，内存仍要同步；补写失败时把结果交给调用方标记失败，重试会按最新副本重写
        updateSongInAllPlaces(
            originalSong = song,
            updatedSong = updated,
            triggerSync = true,
            syncDownloadedMetadata = shouldSyncDownloadedMetadataAfterMetadataUpdate(
                writeLocalMetadata = true,
                isLocalSong = true
            ),
            fastLocalUsageSync = true
        )
        repairOutcome
    }
}

/**
 * 单曲编辑在全局锁内写文件，可能恰好发生在本次读取副本与写文件之间，
 * 文件里的标题、歌手等随后会被旧副本覆盖；这里在锁内按最新副本补写一次，
 * 刚嵌入的歌词与封面保持不动
 */
private suspend fun PlayerManager.rewriteTagsEditedDuringEmbedding(
    updated: SongItem
): LocalMediaMetadataWriteOutcome {
    val outcome = writeLocalEditableMetadata(song = updated, writeCover = false, writeLyrics = false)
    if (outcome != LocalMediaMetadataWriteOutcome.SUCCESS) {
        NPLogger.w(
            "NERI-PlayerManager",
            "rewrite tags edited during embedding failed: outcome=$outcome, song=${updated.name}"
        )
    }
    return outcome
}

private suspend fun PlayerManager.withEmbeddedAssets(
    latest: SongItem,
    embedding: LocalSongAssetEmbedding,
    originalCoverBackup: String?
): SongItem {
    val withLyrics = if (embedding.writesLyrics) {
        val revision = if (embedding.userEditedLyrics) {
            localRepo.nextUserLyricSyncRevision(latest)
        } else {
            latest.lyricSyncRevision
        }
        latest.withUpdatedLyricsPreservingOriginal(
            newLyrics = embedding.lyrics,
            newTranslatedLyric = embedding.translatedLyrics,
            newRomanizedLyric = embedding.romanizedLyrics,
            // 嵌入现有歌词不改变来源，用户改过的歌词仍要受保护，不能被之后的自动匹配或同步覆盖
            userEdited = embedding.userEditedLyrics || latest.lyricSyncEdited == true,
            revision = revision
        ).withLyricProvenance(embedding)
    } else {
        latest
    }
    if (!embedding.writesCover) return withLyrics
    return withLyrics.copy(
        customCoverUrl = normalizeCustomMetadataValue(embedding.coverReference, withLyrics.coverUrl),
        originalCoverUrl = originalCoverBackup ?: withLyrics.originalCoverUrl
    )
}

internal fun SongItem.withLyricProvenance(embedding: LocalSongAssetEmbedding): SongItem {
    val source = embedding.matchedLyricSource ?: return this
    val songId = embedding.matchedSongId?.takeIf(String::isNotBlank) ?: return this
    return copy(matchedLyricSource = source, matchedSongId = songId)
}

/** 批量任务可能持续很久，不在播放队列里的歌也要取歌单里的最新副本，避免用批次开始时的快照覆盖期间的编辑 */
private fun PlayerManager.latestSongCopy(song: SongItem): SongItem {
    return currentQueueSnapshot().playlist.firstOrNull { it.sameIdentityAs(song) }
        ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(song) }
        ?: localRepo.playlists.value.firstNotNullOfOrNull { playlist ->
            playlist.songs.firstOrNull { it.sameIdentityAs(song) }
        }
        ?: song
}

/** 与单曲编辑一致的原封面来源顺序；备份按歌曲身份只保留第一份，多次替换仍能恢复最初的封面 */
private suspend fun PlayerManager.backupOriginalCoverBeforeEmbedding(song: SongItem): String? {
    val discovered = withContext(Dispatchers.IO) {
        sequenceOf(
            { PlayerDependencies.downloads.peekLocalCoverUri(song) },
            { LocalMediaSupport.resolveNearbyCoverUri(application, song) },
            { LocalMediaSupport.peekCachedEmbeddedCoverUri(application, song) },
            { LocalMediaSupport.resolveCoverUri(application, song) }
        ).map { it() }.firstOrNull { reference ->
            !reference.isNullOrBlank() &&
                !CustomSongCoverStorage.isRemoteReference(reference) &&
                !isMediaStoreCoverReference(reference)
        }
    }
    return CustomSongCoverStorage.persistOriginalCover(application, song, discovered)
        ?.takeUnless(CustomSongCoverStorage::isRemoteReference)
}
