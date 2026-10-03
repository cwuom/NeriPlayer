package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.player.metadata.resolveLyricTextForPlayback
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.youtube.api.transport.extractYouTubeMusicVideoId
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.lyrics.buildPhoneticLyricEntries
import moe.ouom.neriplayer.lyrics.parser.flattenWordTimedEntries
import moe.ouom.neriplayer.lyrics.parser.hasWordTimedEntries
import moe.ouom.neriplayer.lyrics.parser.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.lyrics.parser.parseEmbeddedPhoneticLyrics
import moe.ouom.neriplayer.lyrics.parser.resolvePreferredLyricContent

internal data class NowPlayingBackgroundRawLyrics(
    val original: String?,
    val translated: String?,
    val phonetic: String?
)

internal fun buildBackgroundRawLyrics(
    inputs: NowPlayingLyricsBackgroundInputs,
    netease: NowPlayingNeteaseFallback
): NowPlayingBackgroundRawLyrics {
    val original = resolvePreferredLyricContent(
        matchedLyric = effectiveRawLyric(inputs, ManagedLyricVariant.ORIGINAL),
        preferredNeteaseLyric = netease.original,
        legacyLyric = null
    )
    val translated = effectiveRawLyric(inputs, ManagedLyricVariant.TRANSLATED)
    val phonetic = effectiveRawLyric(inputs, ManagedLyricVariant.ROMANIZED)
        ?: netease.romanized.takeIf {
            it.isNotBlank() && parseEmbeddedPhoneticLyrics(original.orEmpty()).isEmpty()
        }
    return NowPlayingBackgroundRawLyrics(original, translated, phonetic)
}

internal fun effectiveRawLyric(
    inputs: NowPlayingLyricsBackgroundInputs,
    variant: ManagedLyricVariant
): String? {
    inputs.song.confirmedLyricFor(variant)?.let { return it }
    val local = inputs.local.lyricFor(variant)
    val stored = inputs.song.storedLyricFor(variant)
    val downloaded = inputs.downloaded.lyricFor(variant)
    if (inputs.managed) {
        return resolveManagedDownloadFastLyricText(inputs.local, inputs.downloaded, stored, variant)
    }
    return resolveLyricTextForPlayback(false, local, stored, downloaded)
}

internal suspend fun resolveBackgroundOriginal(
    inputs: NowPlayingLyricsBackgroundInputs,
    raw: NowPlayingBackgroundRawLyrics,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    val song = inputs.song
    song.confirmedLyricFor(ManagedLyricVariant.ORIGINAL)?.let { return parseAvailableLyric(it) }
    if (song?.isLocalSong() == true) return resolveLocalOriginal(inputs, raw)
    return resolveRemoteOriginal(inputs, raw, sources)
}

private fun resolveLocalOriginal(
    inputs: NowPlayingLyricsBackgroundInputs,
    raw: NowPlayingBackgroundRawLyrics
): List<LyricEntry> = parseAvailableLyric(resolveLocalOriginalText(inputs, raw))

private fun resolveLocalOriginalText(
    inputs: NowPlayingLyricsBackgroundInputs,
    raw: NowPlayingBackgroundRawLyrics
): String? {
    if (inputs.managed) return raw.original
    return inputs.local?.lyric ?: raw.original
}

private fun parseAvailableLyric(text: String?): List<LyricEntry> {
    if (text.isNullOrBlank()) return emptyList()
    return parseNeteaseLyricsAuto(text)
}

private suspend fun resolveRemoteOriginal(
    inputs: NowPlayingLyricsBackgroundInputs,
    raw: NowPlayingBackgroundRawLyrics,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    if (shouldBypassCollapsedStoredLyric(raw.original)) {
        return readOnlineOriginal(inputs, sources)
    }
    val text = raw.original
    if (!text.isNullOrBlank()) return resolveStoredOriginalWithWordTiming(text, inputs, sources)
    return resolveMissingRemoteOriginal(inputs, sources)
}

private suspend fun readOnlineOriginal(
    inputs: NowPlayingLyricsBackgroundInputs,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    val song = inputs.song ?: return emptyList()
    return sources.onlineOriginal(song)
}

private suspend fun resolveMissingRemoteOriginal(
    inputs: NowPlayingLyricsBackgroundInputs,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    if (shouldDelayNowPlayingOnlineLyrics(inputs)) return emptyList()
    return readOnlineOriginal(inputs, sources)
}

private suspend fun resolveStoredOriginalWithWordTiming(
    text: String,
    inputs: NowPlayingLyricsBackgroundInputs,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    val parsed = parseNeteaseLyricsAuto(text)
    // 预取的网易云原文仍需确认当前选源，避免沿用此前 AMLL 的音译缓存
    val hasStoredOriginal = effectiveRawLyric(inputs, ManagedLyricVariant.ORIGINAL) != null
    if (!inputs.preferWordTimedLyrics || (parsed.hasWordTimedEntries() && hasStoredOriginal)) return parsed
    val song = inputs.song ?: return parsed
    return preferOnlineWordTimedOriginal(parsed, song, sources)
}

private suspend fun preferOnlineWordTimedOriginal(
    parsed: List<LyricEntry>,
    song: SongItem,
    sources: NowPlayingLyricsSources
): List<LyricEntry> = sources.onlineOriginal(song).takeIf { it.hasWordTimedEntries() } ?: parsed

internal fun shouldDelayNowPlayingOnlineLyrics(inputs: NowPlayingLyricsBackgroundInputs): Boolean {
    return shouldWaitForPlaybackMediaUrl(inputs.currentMediaUrl) &&
        isYouTubeMusicMediaUri(inputs.song?.mediaUri)
}

private fun shouldWaitForPlaybackMediaUrl(url: String?): Boolean = url.isNullOrBlank()

private fun isYouTubeMusicMediaUri(uri: String?): Boolean =
    uri?.let(::extractYouTubeMusicVideoId) != null

internal suspend fun resolveBackgroundTranslated(
    inputs: NowPlayingLyricsBackgroundInputs,
    raw: NowPlayingBackgroundRawLyrics,
    sources: NowPlayingLyricsSources
): List<LyricEntry> = try {
    resolveBackgroundTranslatedUnchecked(inputs, raw, sources)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

private suspend fun resolveBackgroundTranslatedUnchecked(
    inputs: NowPlayingLyricsBackgroundInputs,
    raw: NowPlayingBackgroundRawLyrics,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    inputs.song.confirmedLyricFor(ManagedLyricVariant.TRANSLATED)?.let { return parseAvailableLyric(it) }
    val localText = localTranslatedLyric(inputs)
    if (localText != null) return parseNeteaseLyricsAuto(localText)
    raw.translated?.let { return resolveRawTranslation(it, inputs.song, sources) }
    return readOnlineTranslationUnlessLocal(inputs, sources)
}

private fun localTranslatedLyric(inputs: NowPlayingLyricsBackgroundInputs): String? {
    if (inputs.managed || inputs.song?.isLocalSong() != true) return null
    return inputs.local?.translatedLyric
}

private suspend fun readOnlineTranslationUnlessLocal(
    inputs: NowPlayingLyricsBackgroundInputs,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    val song = inputs.song ?: return emptyList()
    if (song.isLocalSong()) return emptyList()
    return sources.onlineTranslated(song)
}

private suspend fun resolveRawTranslation(
    text: String,
    song: SongItem?,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    if (text.isBlank()) return emptyList()
    if (shouldBypassCollapsedStoredLyric(text)) {
        return song?.let { sources.onlineTranslated(it) }.orEmpty()
    }
    return parseNeteaseLyricsAuto(text)
}

internal suspend fun resolveBackgroundPhonetic(
    inputs: NowPlayingLyricsBackgroundInputs,
    netease: NowPlayingNeteaseFallback,
    sources: NowPlayingLyricsSources
): List<LyricEntry> = try {
    resolveBackgroundPhoneticUnchecked(inputs, netease, sources)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    emptyList()
}

private suspend fun resolveBackgroundPhoneticUnchecked(
    inputs: NowPlayingLyricsBackgroundInputs,
    netease: NowPlayingNeteaseFallback,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    val song = inputs.song
    song.confirmedLyricFor(ManagedLyricVariant.ROMANIZED)?.let { return parseAvailableLyric(it) }
    val text = effectiveRawLyric(inputs, ManagedLyricVariant.ROMANIZED)
    if (text != null) return parseNeteaseLyricsAuto(text)
    val embedded = parseEmbeddedPhoneticLyrics(effectiveRawLyric(inputs, ManagedLyricVariant.ORIGINAL).orEmpty())
    if (embedded.isNotEmpty()) return embedded
    if (netease.romanized.isNotBlank()) return parseNeteaseLyricsAuto(netease.romanized)
    return readOnlinePhoneticUnlessLocal(song, sources)
}

private suspend fun readOnlinePhoneticUnlessLocal(
    song: SongItem?,
    sources: NowPlayingLyricsSources
): List<LyricEntry> {
    if (song == null || song.isLocalSong()) return emptyList()
    return sources.onlineRomanized(song)
}

internal fun buildBackgroundLyricsState(
    raw: NowPlayingBackgroundRawLyrics,
    original: List<LyricEntry>,
    translated: List<LyricEntry>,
    phonetic: List<LyricEntry>
): LoadedLyricsState = LoadedLyricsState(
    rawLyrics = raw.original.takeUnless(::shouldBypassCollapsedStoredLyric),
    rawTranslatedLyrics = raw.translated.takeUnless(::shouldBypassCollapsedStoredLyric),
    rawPhoneticLyrics = raw.phonetic,
    lyrics = original,
    translatedLyrics = translated,
    phoneticLyrics = phonetic,
    plainLyrics = original.flattenWordTimedEntries(),
    plainTranslatedLyrics = translated.flattenWordTimedEntries(),
    embeddedPhoneticLyrics = buildPhoneticLyricEntries(raw.original, original)
)

internal fun LoadedLyricsState.hasDisplayableContent(): Boolean =
    hasRawLyricsContent() || hasParsedLyricsContent()

private fun LoadedLyricsState.hasRawLyricsContent(): Boolean =
    listOf(rawLyrics, rawTranslatedLyrics, rawPhoneticLyrics).any { !it.isNullOrBlank() }

private fun LoadedLyricsState.hasParsedLyricsContent(): Boolean =
    listOf(lyrics, translatedLyrics, phoneticLyrics).any { it.isNotEmpty() }
