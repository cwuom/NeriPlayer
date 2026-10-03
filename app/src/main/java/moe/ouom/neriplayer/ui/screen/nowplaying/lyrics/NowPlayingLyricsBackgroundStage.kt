package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.metadata.shouldTryPreferredLyricSource
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.lyrics.parser.parseEmbeddedPhoneticLyrics

internal data class NowPlayingLyricsBackgroundInputs(
    val song: SongItem?,
    val local: LocalLyricsScanMetadata?,
    val downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?,
    val managed: Boolean,
    val currentMediaUrl: String?,
    val preferWordTimedLyrics: Boolean
)

internal suspend fun readNowPlayingBackgroundLyrics(
    request: NowPlayingLyricsLoadRequest,
    fast: NowPlayingFastLyricsResult,
    sources: NowPlayingLyricsSources
): LoadedLyricsState {
    readPreferredLyrics(request, sources)?.let { return it }
    val local = readFullLocalLyrics(request, fast, sources)
    val downloaded = readFullDownloadedLyrics(request, fast, sources)
    return resolveNowPlayingBackgroundLyrics(
        NowPlayingLyricsBackgroundInputs(
            song = request.song,
            local = local,
            downloaded = downloaded,
            managed = fast.isManagedLocalDownload,
            currentMediaUrl = request.currentMediaUrl,
            preferWordTimedLyrics = request.preferWordTimedLyrics
        ),
        sources
    )
}

private suspend fun readPreferredLyrics(
    request: NowPlayingLyricsLoadRequest,
    sources: NowPlayingLyricsSources
): LoadedLyricsState? {
    val song = request.song?.takeIf { shouldTryPreferredLyricSource(it, request.defaultLyricSource) }
        ?: return null
    val preferred = sources.preferred(song, request.defaultLyricSource)
    if (preferred != null) {
        NPLogger.d("NowPlayingLyrics", "使用偏好歌词源: source=${request.defaultLyricSource.storageValue}, song=${song.name}")
        return overlayConfirmedUserLyrics(song, buildPreferredLyricSourceState(preferred))
    }
    NPLogger.d("NowPlayingLyrics", "偏好歌词源未命中，回退已存或平台歌词: song=${song.name}")
    return null
}

private fun readFullLocalLyrics(
    request: NowPlayingLyricsLoadRequest,
    fast: NowPlayingFastLyricsResult,
    sources: NowPlayingLyricsSources
): LocalLyricsScanMetadata? {
    val song = eligibleBackgroundLocalSong(request.song, fast.isManagedLocalDownload) ?: return null
    val scan = fast.localLyrics
    if (scan?.hasResolvedSidecar() == true) return scan
    return runCatching { sources.inspectLocal(request.context, song, includeEmbedded = true) }
        .getOrElse { scan }
}

private fun eligibleBackgroundLocalSong(song: SongItem?, managed: Boolean): SongItem? {
    if (managed) return null
    return song?.takeIf(SongItem::isLocalSong)
}

private fun LocalLyricsScanMetadata.hasResolvedSidecar(): Boolean =
    sourceResolved && listOf(hasOriginalSidecar, hasTranslatedSidecar, hasRomanizedSidecar).any { it }

private fun readFullDownloadedLyrics(
    request: NowPlayingLyricsLoadRequest,
    fast: NowPlayingFastLyricsResult,
    sources: NowPlayingLyricsSources
): ManagedDownloadStorage.DownloadedLyricsBundle? {
    val shouldRead = shouldBackfillDownloadedLyricsAfterFastMiss(
        fast.isManagedLocalDownload,
        fast.canReadManagedDownloadLyrics,
        fast.downloadedLyrics
    )
    if (!shouldRead) return fast.downloadedLyrics
    return tryBackfillDownloadedLyrics(request, fast.downloadedLyrics, sources)
}

private fun tryBackfillDownloadedLyrics(
    request: NowPlayingLyricsLoadRequest,
    cached: ManagedDownloadStorage.DownloadedLyricsBundle?,
    sources: NowPlayingLyricsSources
): ManagedDownloadStorage.DownloadedLyricsBundle? {
    val song = request.song ?: return cached
    NPLogger.d("NowPlayingLyrics", "下载侧载快读不完整，执行一次后台补读: song=${song.name}")
    return runCatching { sources.downloaded(request.context, song) }
        .onFailure { NPLogger.w("NowPlayingLyrics", "下载歌词读取失败: ${it.message}") }
        .getOrNull() ?: cached
}

private suspend fun resolveNowPlayingBackgroundLyrics(
    inputs: NowPlayingLyricsBackgroundInputs,
    sources: NowPlayingLyricsSources
): LoadedLyricsState {
    val netease = readNeteaseFallback(inputs, sources)
    val raw = buildBackgroundRawLyrics(inputs, netease)
    val original = resolveBackgroundOriginal(inputs, raw, sources)
    val translated = resolveBackgroundTranslated(inputs, raw, sources)
    val phonetic = resolveBackgroundPhonetic(inputs, netease, sources)
    return overlayConfirmedUserLyrics(inputs.song, buildBackgroundLyricsState(raw, original, translated, phonetic))
}

internal data class NowPlayingNeteaseFallback(
    val original: String,
    val romanized: String
)

private suspend fun readNeteaseFallback(
    inputs: NowPlayingLyricsBackgroundInputs,
    sources: NowPlayingLyricsSources
): NowPlayingNeteaseFallback {
    val songId = inputs.song?.takeUnless(SongItem::isLocalSong)
        ?.let(::resolvePreferredNeteaseLyricSongId)
        ?: return NowPlayingNeteaseFallback("", "")
    val original = readNeteaseOriginal(inputs, songId, sources)
    val romanized = readNeteaseRomanized(inputs, songId, sources)
    return NowPlayingNeteaseFallback(original, romanized)
}

private suspend fun readNeteaseOriginal(
    inputs: NowPlayingLyricsBackgroundInputs,
    songId: Long,
    sources: NowPlayingLyricsSources
): String {
    if (!shouldReadNeteaseOriginal(inputs)) return ""
    return try {
        sources.neteaseOriginal(songId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }
}

private suspend fun readNeteaseRomanized(
    inputs: NowPlayingLyricsBackgroundInputs,
    songId: Long,
    sources: NowPlayingLyricsSources
): String {
    // 逐词模式可能改用 AMLL，音译交由选中的来源判断是否需要回退
    if (inputs.preferWordTimedLyrics || !shouldReadNeteaseRomanized(inputs)) return ""
    return try {
        sources.neteaseRomanized(songId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }
}

internal fun shouldReadNeteaseOriginal(inputs: NowPlayingLyricsBackgroundInputs): Boolean =
    listOf(
        inputs.local?.lyric,
        inputs.song.storedLyricFor(ManagedLyricVariant.ORIGINAL),
        inputs.downloaded?.lyric
    ).all { it == null }

internal fun shouldReadNeteaseRomanized(inputs: NowPlayingLyricsBackgroundInputs): Boolean =
    listOf(inputs.local?.romanizedLyric, inputs.song.storedLyricFor(ManagedLyricVariant.ROMANIZED),
        inputs.downloaded?.romanizedLyric).all { it == null } &&
        parseEmbeddedPhoneticLyrics(effectiveRawLyric(inputs, ManagedLyricVariant.ORIGINAL).orEmpty()).isEmpty()
