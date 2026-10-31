package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.metadata.shouldReadManagedDownloadLyrics
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.lyrics.parser.resolveStoredLyricText
import moe.ouom.neriplayer.lyrics.parser.flattenWordTimedEntries

internal data class NowPlayingFastLyricsResult(
    val state: LoadedLyricsState,
    val localLyrics: LocalLyricsScanMetadata?,
    val downloadedLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?,
    val isManagedLocalDownload: Boolean,
    val canReadManagedDownloadLyrics: Boolean
)

internal data class NowPlayingInitialLyricsResult(
    val cachedPreferredLyrics: PreferredLyricSourceResult?,
    val state: LoadedLyricsState
)

private data class NowPlayingInitialLyricsKey(
    val song: SongItem?,
    val source: LyricSourcePreference,
    val preferWordTimedLyrics: Boolean,
    val preferenceRevision: Long,
    val cacheGeneration: Long,
    val providedCache: PreferredLyricSourceResult?
)

@Composable
internal fun rememberNowPlayingInitialLyrics(
    request: NowPlayingLyricsLoadRequest,
    preferenceRevision: Long,
    stages: NowPlayingLyricsStages
): NowPlayingInitialLyricsResult {
    val cacheKey = NowPlayingInitialLyricsKey(request.song, request.defaultLyricSource,
        request.preferWordTimedLyrics, preferenceRevision, stages.cacheGeneration, request.cachedPreferredLyrics)
    return remember(cacheKey, stages) { stages.readInitial(request) }
}

internal class NowPlayingLyricsLoadStages(
    private val sources: NowPlayingLyricsSources,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    override val snapshotCache: NowPlayingLyricsSnapshotCache = NowPlayingLyricsSnapshotCache()
) : NowPlayingLyricsStages {
    override val cacheGeneration: Long get() = sources.cacheGeneration

    override fun readInitial(request: NowPlayingLyricsLoadRequest): NowPlayingInitialLyricsResult {
        val cached = request.cachedPreferredLyrics ?: request.song?.let { song ->
            sources.cachedPreferred(song, request.defaultLyricSource, request.preferWordTimedLyrics)
        }
        val initial = buildNowPlayingCachedInitialLyricsState(request.song, cached)
        val state = if (request.song.hasConfirmedLyricOverride()) initial else withCachedOriginal(request, initial)
        return NowPlayingInitialLyricsResult(cached, state)
    }

    override suspend fun readFast(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult =
        withContext(ioDispatcher) { readFastOnIo(request) }

    private fun readFastOnIo(request: NowPlayingLyricsLoadRequest): NowPlayingFastLyricsResult {
        val song = request.song
        val managed = isManagedLocalLyricSong(song)
        if (managed) sources.scheduleManagedRefresh(request.context)
        val canReadDownloaded = canReadDownloadedLyrics(song, managed)
        val downloaded = readFastDownloaded(request, canReadDownloaded)
        val local = readFastLocal(request, managed)
        clearBiliCcOriginForSidecar(song, local, downloaded)
        val state = withCachedOriginal(request, resolveFastState(request, managed, local, downloaded))
        return NowPlayingFastLyricsResult(state, local, downloaded, managed, canReadDownloaded)
    }

    private fun clearBiliCcOriginForSidecar(
        song: SongItem?,
        local: LocalLyricsScanMetadata?,
        downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?
    ) {
        if (song == null) return
        // 只有原文侧载接管时才清除 CC 来源, 单独的翻译不会改变原文来源
        for (original in listOf(downloaded?.lyric, local?.lyric)) {
            if (!original.isNullOrBlank()) {
                sources.clearBiliCcSubtitleLyricsOrigin(song)
                return
            }
        }
    }

    private fun withCachedOriginal(
        request: NowPlayingLyricsLoadRequest,
        state: LoadedLyricsState
    ): LoadedLyricsState {
        if (request.defaultLyricSource != LyricSourcePreference.Automatic || state.hasOriginalSource()) return state
        val song = request.song ?: return state
        if (song.isLocalSong()) return state
        val cached = sources.cachedOriginal(song)?.takeIf { it.isNotEmpty() } ?: return state
        return overlayConfirmedUserLyrics(song, state.copy(
            lyrics = cached,
            plainLyrics = cached.flattenWordTimedEntries()
        ))
    }

    private fun LoadedLyricsState.hasOriginalSource(): Boolean =
        rawLyrics != null || lyrics.isNotEmpty() || preferredSource != null

    private fun isManagedLocalLyricSong(song: SongItem?): Boolean =
        song?.let { it.isLocalSong() && sources.hasManagedDownload(it) } == true

    private fun canReadDownloadedLyrics(song: SongItem?, managed: Boolean): Boolean =
        song?.let { shouldReadManagedDownloadLyrics(it, managed) } == true

    private fun resolveFastState(
        request: NowPlayingLyricsLoadRequest,
        managed: Boolean,
        local: LocalLyricsScanMetadata?,
        downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?
    ): LoadedLyricsState = request.cachedPreferredLyrics?.let {
        buildNowPlayingInitialLyricsState(request.song, it)
    }
        ?: buildFastState(request.song, managed, local, downloaded)

    private fun readFastDownloaded(
        request: NowPlayingLyricsLoadRequest,
        canRead: Boolean
    ): ManagedDownloadStorage.DownloadedLyricsBundle? {
        val song = request.song?.takeIf { canRead } ?: return null
        return runCatching { sources.fastDownloaded(request.context, song) }
            .onFailure { NPLogger.w("NowPlayingLyrics", "下载歌词内存索引读取失败: ${it.message}") }
            .getOrNull()
    }

    private fun readFastLocal(
        request: NowPlayingLyricsLoadRequest,
        managed: Boolean
    ): LocalLyricsScanMetadata? {
        val song = request.song?.takeIf { it.isLocalSong() && !managed } ?: return null
        return runCatching {
            sources.inspectLocal(request.context, song,
                shouldReadEmbeddedLyricsForNowPlayingFastStage()
            )
        }.onFailure { NPLogger.w("NowPlayingLyrics", "本地歌词首屏读取失败: ${it.message}") }
            .getOrNull()
    }

    override suspend fun readBackground(
        request: NowPlayingLyricsLoadRequest,
        fast: NowPlayingFastLyricsResult
    ): LoadedLyricsState = withContext(ioDispatcher) {
        readNowPlayingBackgroundLyrics(
            request,
            fast,
            sources
        )
    }
}

internal val DefaultNowPlayingLyricsStages: NowPlayingLyricsStages =
    NowPlayingLyricsLoadStages(PlatformNowPlayingLyricsSources)

internal fun buildFastState(
    song: SongItem?,
    managed: Boolean,
    local: LocalLyricsScanMetadata?,
    downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?
): LoadedLyricsState =
    overlayConfirmedUserLyrics(song, buildNowPlayingFastLyricsState(
        rawLyrics = resolveFastRawLyric(
            song,
            managed,
            local,
            downloaded,
            ManagedLyricVariant.ORIGINAL
        ),
        rawTranslatedLyrics = resolveFastRawLyric(
            song,
            managed,
            local,
            downloaded,
            ManagedLyricVariant.TRANSLATED
        ),
        rawPhoneticLyrics = resolveFastRawLyric(
            song,
            managed,
            local,
            downloaded,
            ManagedLyricVariant.ROMANIZED
        )
    ))

internal fun resolveFastRawLyric(
    song: SongItem?,
    managed: Boolean,
    local: LocalLyricsScanMetadata?,
    downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?,
    variant: ManagedLyricVariant
): String? {
    song.confirmedLyricFor(variant)?.let { return it }
    return resolveNowPlayingLyricText(
        isManagedLocalDownload = managed,
        localLyrics = local,
        downloadedLyrics = downloaded,
        localLyric = local.lyricFor(variant),
        storedLyric = song.storedLyricFor(variant),
        downloadedLyric = null,
        variant = variant
    )
}

internal fun SongItem?.confirmedLyricFor(variant: ManagedLyricVariant): String? {
    if (this?.lyricSyncEdited != true) return null
    return variant.matchedText(this)
}

internal fun SongItem?.storedLyricFor(variant: ManagedLyricVariant): String? =
    this?.let { resolveStoredLyricText(variant.matchedText(it), variant.legacyText(it)) }

internal fun LocalLyricsScanMetadata?.lyricFor(variant: ManagedLyricVariant): String? =
    this?.let(variant.localText)

internal fun ManagedDownloadStorage.DownloadedLyricsBundle?.lyricFor(variant: ManagedLyricVariant): String? =
    this?.let(variant.downloadedText)
