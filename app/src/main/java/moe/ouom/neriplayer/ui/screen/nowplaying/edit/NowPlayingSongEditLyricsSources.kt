package moe.ouom.neriplayer.ui.screen.nowplaying.edit

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.core.lyrics.resolveLocalLyricsEditorSeed
import moe.ouom.neriplayer.core.lyrics.resolveStoredLyricText
import moe.ouom.neriplayer.ui.screen.nowplaying.hasCachedLocalDownload

internal enum class EditSongLyricVariant { ORIGINAL, TRANSLATED, ROMANIZED }

internal data class EditSongLyricsSources(
    val downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?,
    val local: LocalLyricsScanMetadata?,
    val embedded: LocalLyricsScanMetadata?
) {
    fun hasSidecar(variant: EditSongLyricVariant): Boolean =
        downloaded?.sidecarPresent(variant) == true || local?.sidecarPresent(variant) == true

    fun hasAnySidecar(): Boolean = EditSongLyricVariant.entries.any(::hasSidecar)

    fun sidecarText(variant: EditSongLyricVariant): String? {
        if (downloaded?.sidecarPresent(variant) == true) return downloaded.text(variant)
        if (local?.sidecarPresent(variant) == true) return local.text(variant)
        return null
    }

    fun embeddedText(variant: EditSongLyricVariant, stored: String?, displayed: String): String {
        val downloadedValue = downloaded?.takeUnless { it.sidecarPresent(variant) }?.text(variant)
        return downloadedValue ?: embedded?.text(variant) ?: stored ?: displayed
    }

    fun hasEmbeddedText(): Boolean = embedded?.hasEmbeddedLyricText() == true
}

internal fun LocalLyricsScanMetadata.hasEmbeddedLyricText(): Boolean = listOf(
    embeddedLyric,
    embeddedTranslatedLyric,
    embeddedRomanizedLyric
).any { !it.isNullOrBlank() }

private fun ManagedDownloadStorage.DownloadedLyricsBundle.sidecarPresent(
    variant: EditSongLyricVariant
): Boolean = when (variant) {
    EditSongLyricVariant.ORIGINAL -> hasOriginalSidecar
    EditSongLyricVariant.TRANSLATED -> hasTranslatedSidecar
    EditSongLyricVariant.ROMANIZED -> hasRomanizedSidecar
}

private fun LocalLyricsScanMetadata.sidecarPresent(variant: EditSongLyricVariant): Boolean =
    when (variant) {
        EditSongLyricVariant.ORIGINAL -> hasOriginalSidecar
        EditSongLyricVariant.TRANSLATED -> hasTranslatedSidecar
        EditSongLyricVariant.ROMANIZED -> hasRomanizedSidecar
    }

private fun ManagedDownloadStorage.DownloadedLyricsBundle.text(variant: EditSongLyricVariant): String? =
    when (variant) {
        EditSongLyricVariant.ORIGINAL -> lyric
        EditSongLyricVariant.TRANSLATED -> translatedLyric
        EditSongLyricVariant.ROMANIZED -> romanizedLyric
    }

private fun LocalLyricsScanMetadata.text(variant: EditSongLyricVariant): String? = when (variant) {
    EditSongLyricVariant.ORIGINAL -> lyric
    EditSongLyricVariant.TRANSLATED -> translatedLyric
    EditSongLyricVariant.ROMANIZED -> romanizedLyric
}

internal interface EditSongLyricsSourceReader {
    fun isManagedLocalDownload(song: SongItem): Boolean
    fun downloaded(context: Context, song: SongItem): ManagedDownloadStorage.DownloadedLyricsBundle?
    fun local(context: Context, song: SongItem, managed: Boolean): LocalLyricsScanMetadata?
    fun embedded(context: Context, song: SongItem): LocalLyricsScanMetadata?
}

internal object DefaultEditSongLyricsSourceReader : EditSongLyricsSourceReader {
    override fun isManagedLocalDownload(song: SongItem): Boolean = hasCachedLocalDownload(song)

    override fun downloaded(context: Context, song: SongItem) =
        AudioDownloadManager.getLyricsBundleFast(context, song)

    override fun local(context: Context, song: SongItem, managed: Boolean) =
        LocalMediaSupport.inspectLyricsFast(
            context = context,
            song = song,
            includeStoredFallback = !managed,
            includeEmbeddedFallback = false,
            forceRefresh = true
        )

    override fun embedded(context: Context, song: SongItem) =
        LocalMediaSupport.inspectLyricsFast(
            context = context,
            song = song,
            includeStoredFallback = false,
            includeEmbeddedFallback = true,
            forceRefresh = true
        )
}

internal fun shouldProbeEditSongLocalLyrics(
    managed: Boolean,
    downloaded: ManagedDownloadStorage.DownloadedLyricsBundle?
): Boolean = managed || downloaded == null ||
    !downloaded.hasOriginalSidecar || !downloaded.hasTranslatedSidecar ||
    !downloaded.hasRomanizedSidecar

internal suspend fun readEditSongLyricsSources(
    context: Context,
    song: SongItem,
    reader: EditSongLyricsSourceReader = DefaultEditSongLyricsSourceReader,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): EditSongLyricsSources = withContext(dispatcher) {
    val managed = reader.isManagedLocalDownload(song)
    val downloaded = if (managed) {
        readEditSongLyricsSource("下载歌词索引") { reader.downloaded(context, song) }
    } else null
    // 下载索引可能落后于 Lyrics/ 中的手动增删, 因此保留真实侧载探测
    val local = if (shouldProbeEditSongLocalLyrics(managed, downloaded)) {
        readEditSongLyricsSource("本地歌词") { reader.local(context, song, managed) }
    } else null
    val preliminary = EditSongLyricsSources(downloaded, local, null)
    val embedded = if (preliminary.hasAnySidecar()) null else {
        readEditSongLyricsSource("嵌入歌词") { reader.embedded(context, song) }
    }
    preliminary.copy(embedded = embedded)
}

private inline fun <T> readEditSongLyricsSource(label: String, block: () -> T): T? = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: SecurityException) {
    throw error
} catch (error: Exception) {
    NPLogger.w("NowPlayingLyrics", "编辑器读取${label}失败: ${error.message}")
    null
}

internal fun buildLocalEditSongLyricsEditorSeed(
    song: SongItem,
    sources: EditSongLyricsSources,
    displayedLyrics: String,
    displayedTranslatedLyrics: String,
    displayedRomanizedLyrics: String
): LyricsEditorSeed = resolveLocalLyricsEditorSeed(
    song = song,
    sidecarLyrics = sources.sidecarText(EditSongLyricVariant.ORIGINAL),
    sidecarTranslatedLyrics = sources.sidecarText(EditSongLyricVariant.TRANSLATED),
    sidecarRomanizedLyrics = sources.sidecarText(EditSongLyricVariant.ROMANIZED),
    embeddedLyrics = sources.embeddedText(
        EditSongLyricVariant.ORIGINAL,
        resolveStoredLyricText(song.matchedLyric, song.originalLyric),
        displayedLyrics
    ),
    embeddedTranslatedLyrics = sources.embeddedText(
        EditSongLyricVariant.TRANSLATED,
        resolveStoredLyricText(song.matchedTranslatedLyric, song.originalTranslatedLyric),
        displayedTranslatedLyrics
    ),
    embeddedRomanizedLyrics = sources.embeddedText(
        EditSongLyricVariant.ROMANIZED,
        resolveStoredLyricText(song.matchedRomanizedLyric, song.originalRomanizedLyric),
        displayedRomanizedLyrics
    ),
    hasOriginalSidecar = sources.hasSidecar(EditSongLyricVariant.ORIGINAL),
    hasTranslatedSidecar = sources.hasSidecar(EditSongLyricVariant.TRANSLATED),
    hasRomanizedSidecar = sources.hasSidecar(EditSongLyricVariant.ROMANIZED),
    hasEmbeddedLyrics = sources.hasEmbeddedText()
)

internal sealed interface EditSongEmbeddedReadResult {
    data class Loaded(val lyrics: LocalLyricsScanMetadata?) : EditSongEmbeddedReadResult
    data object PermissionLost : EditSongEmbeddedReadResult
}

internal suspend fun readEditSongEmbeddedLyrics(
    context: Context,
    song: SongItem,
    reader: (Context, SongItem) -> LocalLyricsScanMetadata? = LocalMediaSupport::inspectEmbeddedLyrics,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): EditSongEmbeddedReadResult = withContext(dispatcher) {
    try {
        EditSongEmbeddedReadResult.Loaded(reader(context, song))
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        NPLogger.e("NowPlayingLyrics", "补充读取嵌入歌词时目录授权失效", error)
        EditSongEmbeddedReadResult.PermissionLost
    } catch (error: Exception) {
        NPLogger.w("NowPlayingLyrics", "补充读取嵌入歌词失败: ${error.message}")
        EditSongEmbeddedReadResult.Loaded(null)
    }
}
