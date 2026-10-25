package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.identity.sameIdentityAs
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.external.LocalSongFileRefreshResult
import moe.ouom.neriplayer.data.model.SongItem

private const val LOCAL_FILE_REFRESH_TAG = "PlayerManagerLocalFileRefresh"

/**
 * 外部标签编辑器（如 Lyrico）已经写回文件后，按文件内容刷新队列、歌单、下载列表与本地缓存。
 *
 * 文件是这次修改的唯一来源，因此应用内的自定义标题、艺术家与封面会被清除，
 * 否则 `displayName()` / `displayArtist()` 之类的展示层仍会继续显示旧值。
 * 文件里没有歌词时保留应用内已有歌词，避免一次标签编辑顺带清掉在线匹配结果。
 */
internal suspend fun PlayerManager.refreshLocalSongAfterExternalTagEditImpl(
    song: SongItem
): LocalSongFileRefreshResult {
    if (!LocalSongSupport.isLocalSong(song, application)) {
        return LocalSongFileRefreshResult.NOT_LOCAL_SONG
    }
    // 先让按旧文件内容建立的封面与歌词缓存失效，否则重新读取时仍会拿到修改前的内嵌封面
    withContext(Dispatchers.IO) {
        LocalMediaSupport.invalidateExternalTagEditCaches(application, song)
    }
    val fileSong = readExternalEditedLocalSong(song) ?: return LocalSongFileRefreshResult.UNAVAILABLE
    return try {
        runSongMetadataMutation {
            val latest = latestSongForExternalTagEdit(song)
            val updated = withExternalTagEditMetadata(latest, fileSong)
            if (updated == latest) {
                return@runSongMetadataMutation LocalSongFileRefreshResult.SUCCESS
            }
            updateSongInAllPlaces(
                originalSong = song,
                updatedSong = updated,
                triggerSync = true,
                syncDownloadedMetadata = true,
                fastLocalUsageSync = true
            )
            LocalSongFileRefreshResult.SUCCESS
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        NPLogger.e(LOCAL_FILE_REFRESH_TAG, "refresh local song after external tag edit failed: ${song.name}", error)
        LocalSongFileRefreshResult.FAILED
    }
}

private suspend fun PlayerManager.readExternalEditedLocalSong(song: SongItem): SongItem? =
    withContext(Dispatchers.IO) {
        runCatching { LocalMediaSupport.inspect(application, song) }
            .getOrElse { error ->
                NPLogger.w(LOCAL_FILE_REFRESH_TAG, "read edited local tags failed for ${song.name}: ${error.message}")
                null
            }
            ?.let { details -> LocalMediaSupport.toSongItem(details) }
    }

/** 与批量嵌入一致的取最新副本顺序：队列、当前歌曲、歌单 */
private fun PlayerManager.latestSongForExternalTagEdit(song: SongItem): SongItem {
    return currentQueueSnapshot().playlist.firstOrNull { it.sameIdentityAs(song) }
        ?: _currentSongFlow.value?.takeIf { it.sameIdentityAs(song) }
        ?: localRepo.playlists.value.firstNotNullOfOrNull { playlist ->
            playlist.songs.firstOrNull { it.sameIdentityAs(song) }
        }
        ?: song
}

/**
 * 把文件里读到的标签合并到最新歌曲快照上；身份字段（id、文件引用、来源键）始终沿用原歌曲，
 * 避免一次标签刷新把同一首歌拆成两条记录。
 */
private suspend fun PlayerManager.withExternalTagEditMetadata(
    latest: SongItem,
    fileSong: SongItem
): SongItem {
    val fileHasLyrics = fileSong.matchedLyric != null ||
        fileSong.matchedTranslatedLyric != null ||
        fileSong.matchedRomanizedLyric != null
    val lyricsChanged = fileHasLyrics && (
        fileSong.matchedLyric != latest.matchedLyric ||
            fileSong.matchedTranslatedLyric != latest.matchedTranslatedLyric ||
            fileSong.matchedRomanizedLyric != latest.matchedRomanizedLyric
        )
    return latest.copy(
        name = fileSong.name,
        artist = fileSong.artist,
        album = fileSong.album,
        durationMs = fileSong.durationMs.takeIf { it > 0L } ?: latest.durationMs,
        coverUrl = fileSong.coverUrl,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        matchedLyric = if (fileHasLyrics) fileSong.matchedLyric else latest.matchedLyric,
        matchedTranslatedLyric = if (fileHasLyrics) {
            fileSong.matchedTranslatedLyric
        } else {
            latest.matchedTranslatedLyric
        },
        matchedRomanizedLyric = if (fileHasLyrics) {
            fileSong.matchedRomanizedLyric
        } else {
            latest.matchedRomanizedLyric
        },
        // 歌词来自文件而不是在线匹配，旧来源不能再用于翻译对齐与回退
        matchedLyricSource = if (lyricsChanged) null else latest.matchedLyricSource,
        matchedSongId = if (lyricsChanged) null else latest.matchedSongId,
        lyricSyncEdited = if (lyricsChanged) true else latest.lyricSyncEdited,
        lyricSyncRevision = if (lyricsChanged) {
            localRepo.nextUserLyricSyncRevision(latest)
        } else {
            latest.lyricSyncRevision
        },
        localFileName = fileSong.localFileName ?: latest.localFileName,
        sourceStableKey = latest.sourceStableKey ?: fileSong.sourceStableKey
    )
}
