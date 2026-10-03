package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import moe.ouom.neriplayer.platform.lyrics.matching.hasCollapsedTimedLyricTimeline
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.metadata.resolveKnownNeteaseLyricSongId
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.core.player.metadata.resolveLyricTextForPlayback
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.buildPhoneticLyricEntries
import moe.ouom.neriplayer.lyrics.parser.flattenWordTimedEntries
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto

internal fun resolvePreferredNeteaseLyricSongId(song: SongItem?): Long? {
    return song?.let(::resolveKnownNeteaseLyricSongId)
}

internal data class LoadedLyricsState(
    val rawLyrics: String?,
    val rawTranslatedLyrics: String?,
    val rawPhoneticLyrics: String?,
    val lyrics: List<LyricEntry>,
    val translatedLyrics: List<LyricEntry>,
    val phoneticLyrics: List<LyricEntry>,
    val plainLyrics: List<LyricEntry>,
    val plainTranslatedLyrics: List<LyricEntry>,
    val embeddedPhoneticLyrics: List<LyricEntry>,
    val preferredSource: LyricSourcePreference? = null
)

internal enum class ManagedLyricVariant(
    val localText: (LocalLyricsScanMetadata) -> String?,
    val downloadedText: (ManagedDownloadStorage.DownloadedLyricsBundle) -> String?,
    val localSidecar: (LocalLyricsScanMetadata) -> Boolean,
    val downloadedSidecar: (ManagedDownloadStorage.DownloadedLyricsBundle) -> Boolean,
    val matchedText: (SongItem) -> String?,
    val legacyText: (SongItem) -> String?
) {
    ORIGINAL(
        { it.lyric }, { it.lyric }, { it.hasOriginalSidecar }, { it.hasOriginalSidecar },
        { it.matchedLyric }, { it.originalLyric }
    ),
    TRANSLATED(
        { it.translatedLyric }, { it.translatedLyric },
        { it.hasTranslatedSidecar }, { it.hasTranslatedSidecar },
        { it.matchedTranslatedLyric }, { it.originalTranslatedLyric }
    ),
    ROMANIZED(
        { it.romanizedLyric }, { it.romanizedLyric },
        { it.hasRomanizedSidecar }, { it.hasRomanizedSidecar },
        { it.matchedRomanizedLyric }, { it.originalRomanizedLyric }
    )
}

internal fun resolveManagedDownloadFastLyricText(
    localLyrics: LocalLyricsScanMetadata?,
    downloadedLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?,
    storedLyric: String?,
    variant: ManagedLyricVariant
): String? {
    val indexed = downloadedLyrics?.let {
        LyricSidecarCandidate(variant.downloadedSidecar(it), variant.downloadedText(it))
    }
    val local = localLyrics?.let {
        LyricSidecarCandidate(variant.localSidecar(it), variant.localText(it))
    }
    return chooseManagedLyricText(indexed, local, storedLyric)
}

private data class LyricSidecarCandidate(val hasSidecar: Boolean, val text: String?)

private fun chooseManagedLyricText(
    indexed: LyricSidecarCandidate?,
    local: LyricSidecarCandidate?,
    stored: String?
): String? {
    if (indexed?.hasSidecar == true) return indexed.text
    if (local?.hasSidecar == true) return local.text
    return fallbackManagedLyricText(indexed, local, stored)
}

private fun fallbackManagedLyricText(
    indexed: LyricSidecarCandidate?,
    local: LyricSidecarCandidate?,
    stored: String?
): String? = indexed?.text ?: local?.text ?: stored

internal fun resolveNowPlayingLyricText(
    isManagedLocalDownload: Boolean,
    localLyrics: LocalLyricsScanMetadata?,
    downloadedLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?,
    localLyric: String?,
    storedLyric: String?,
    downloadedLyric: String?,
    variant: ManagedLyricVariant
): String? {
    return if (isManagedLocalDownload) {
        resolveManagedDownloadFastLyricText(
            localLyrics = localLyrics,
            downloadedLyrics = downloadedLyrics,
            storedLyric = storedLyric,
            variant = variant
        )
    } else {
        resolveLyricTextForPlayback(
            isManagedLocalDownload = false,
            localLyric = localLyric,
            storedLyric = storedLyric,
            downloadedLyric = downloadedLyric
        )
    }
}

internal fun buildNowPlayingFastLyricsState(
    rawLyrics: String?,
    rawTranslatedLyrics: String?,
    rawPhoneticLyrics: String?
): LoadedLyricsState {
    val bypassRawLyrics = shouldBypassCollapsedStoredLyric(rawLyrics)
    val bypassTranslatedLyrics = shouldBypassCollapsedStoredLyric(rawTranslatedLyrics)
    val lyrics = parseFastLyric(rawLyrics, bypassRawLyrics)
    val translatedLyrics = parseFastLyric(rawTranslatedLyrics, bypassTranslatedLyrics)
    val phoneticLyrics = parseFastLyric(rawPhoneticLyrics, bypass = false)
    return LoadedLyricsState(
        rawLyrics = rawLyrics.takeUnless { bypassRawLyrics },
        rawTranslatedLyrics = rawTranslatedLyrics.takeUnless { bypassTranslatedLyrics },
        rawPhoneticLyrics = rawPhoneticLyrics,
        lyrics = lyrics,
        translatedLyrics = translatedLyrics,
        phoneticLyrics = phoneticLyrics,
        plainLyrics = lyrics.flattenWordTimedEntries(),
        plainTranslatedLyrics = translatedLyrics.flattenWordTimedEntries(),
        embeddedPhoneticLyrics = buildPhoneticLyricEntries(
            rawLyrics = rawLyrics,
            lyrics = lyrics
        )
    )
}

private fun parseFastLyric(raw: String?, bypass: Boolean): List<LyricEntry> {
    if (raw.isNullOrBlank() || bypass) return emptyList()
    return parseNeteaseLyricsAuto(raw)
}

internal fun buildPreferredLyricSourceState(
    result: PreferredLyricSourceResult
): LoadedLyricsState = LoadedLyricsState(
    rawLyrics = null,
    rawTranslatedLyrics = null,
    rawPhoneticLyrics = null,
    lyrics = result.lyrics,
    translatedLyrics = result.translatedLyrics,
    phoneticLyrics = result.romanizedLyrics,
    plainLyrics = result.lyrics.flattenWordTimedEntries(),
    plainTranslatedLyrics = result.translatedLyrics.flattenWordTimedEntries(),
    embeddedPhoneticLyrics = emptyList(),
    preferredSource = result.source
)

/**
 * 为当前曲目建立无需磁盘访问的首帧歌词快照
 */
internal fun buildNowPlayingImmediateLyricsState(song: SongItem?): LoadedLyricsState {
    return overlayConfirmedUserLyrics(song, buildNowPlayingFastLyricsState(
        rawLyrics = song.storedLyricFor(ManagedLyricVariant.ORIGINAL),
        rawTranslatedLyrics = song.storedLyricFor(ManagedLyricVariant.TRANSLATED),
        rawPhoneticLyrics = song.storedLyricFor(ManagedLyricVariant.ROMANIZED)
    ))
}

internal fun buildNowPlayingInitialLyricsState(
    song: SongItem?,
    cachedPreferredLyrics: PreferredLyricSourceResult?
): LoadedLyricsState {
    if (cachedPreferredLyrics == null) return buildNowPlayingImmediateLyricsState(song)
    return overlayConfirmedUserLyrics(song, buildPreferredLyricSourceState(cachedPreferredLyrics))
}

internal fun SongItem?.hasConfirmedLyricOverride(): Boolean = this?.lyricSyncEdited == true &&
    listOf(matchedLyric, matchedTranslatedLyric, matchedRomanizedLyric).any { it != null }

internal fun overlayConfirmedUserLyrics(song: SongItem?, fallback: LoadedLyricsState): LoadedLyricsState {
    if (song?.lyricSyncEdited != true) return fallback
    val original = overlayConfirmedOriginal(song.matchedLyric, fallback)
    val translated = overlayConfirmedTranslation(song.matchedTranslatedLyric, original)
    return overlayConfirmedPhonetic(song.matchedRomanizedLyric, translated)
}

private fun overlayConfirmedOriginal(text: String?, fallback: LoadedLyricsState): LoadedLyricsState {
    if (text == null) return fallback
    val parsed = parseFastLyric(text, bypass = false)
    return fallback.copy(rawLyrics = text, lyrics = parsed,
        plainLyrics = parsed.flattenWordTimedEntries(),
        embeddedPhoneticLyrics = buildPhoneticLyricEntries(text, parsed), preferredSource = null)
}

private fun overlayConfirmedTranslation(text: String?, fallback: LoadedLyricsState): LoadedLyricsState {
    if (text == null) return fallback
    val parsed = parseFastLyric(text, bypass = false)
    return fallback.copy(rawTranslatedLyrics = text, translatedLyrics = parsed,
        lyrics = fallback.lyrics.map { it.copy(translation = null) },
        plainLyrics = fallback.plainLyrics.map { it.copy(translation = null) },
        plainTranslatedLyrics = parsed.flattenWordTimedEntries(), preferredSource = null)
}

private fun overlayConfirmedPhonetic(text: String?, fallback: LoadedLyricsState): LoadedLyricsState {
    if (text == null) return fallback
    // 独立音译分轨一旦明确提供，也不能再从旧原文中回退嵌入音译
    return fallback.copy(rawPhoneticLyrics = text, phoneticLyrics = parseFastLyric(text, bypass = false),
        embeddedPhoneticLyrics = emptyList(), preferredSource = null)
}

internal fun shouldReplaceLyricsAfterRefresh(
    sameSong: Boolean,
    loadedHasLyrics: Boolean
): Boolean = !sameSong || loadedHasLyrics

internal fun shouldBackfillDownloadedLyricsAfterFastMiss(
    isManagedLocalDownload: Boolean,
    canReadManagedDownloadLyrics: Boolean,
    fastLyrics: ManagedDownloadStorage.DownloadedLyricsBundle?
): Boolean {
    if (!isManagedLocalDownload || !canReadManagedDownloadLyrics) {
        return false
    }
    // 音频快照命中不代表 Lyrics 索引已经完成, 首播也必须补齐过期或部分索引
    return fastLyrics?.let {
        listOf(it.hasOriginalSidecar, it.hasTranslatedSidecar, it.hasRomanizedSidecar).any { present -> !present }
    } ?: true
}

internal fun shouldReadEmbeddedLyricsForNowPlayingFastStage(): Boolean = false

internal fun resolveNowPlayingLyricsMediaReloadKey(
    song: SongItem?,
    currentMediaUrl: String?
): String? {
    // 本地音频的播放地址可能在首播期间从索引 URI 切换为可播放 URI, 不应取消歌词补读
    return currentMediaUrl.takeUnless { song?.isLocalSong() == true }
}

internal fun shouldBypassCollapsedStoredLyric(rawLyric: String?): Boolean {
    return rawLyric?.let(::hasCollapsedTimedLyricTimeline) == true
}
